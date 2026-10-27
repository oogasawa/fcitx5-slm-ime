#!/usr/bin/env python3
"""
Training script for kana-kanji Transformer.
Usage: python3 train.py --data-dir DATA_DIR --run-name NAME --epochs 8 --batch-size 64 \
    --d-model 512 --nhead 8 --enc-layers 6 --dec-layers 6 --d-ff 2048 --max-len 192

Checkpoints are saved to checkpoints/<run-name>/
"""

import os
import sys
import time
import math
import random
import argparse

import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader

sys.path.insert(0, os.path.dirname(__file__))
from model.tokenizer import CharTokenizer, BOS_ID, EOS_ID, PAD_ID
from model.transformer import KanaKanjiTransformer

DATA_DIR = os.path.join(os.path.dirname(__file__), "data")
_BASE_CKPT_DIR = os.path.join(os.path.dirname(__file__), "checkpoints")


# ---------------------------------------------------------------------------
# Dataset
# ---------------------------------------------------------------------------

class KanaKanjiDataset(Dataset):
    """Lazy tokenization: raw strings are stored, tokenized on-the-fly in __getitem__.
    Initialization is O(1) regardless of dataset size."""

    def __init__(self, pairs: list[tuple[str, str]], tokenizer: CharTokenizer, max_len: int = 128):
        # Filter by character length (fast, no tokenization needed)
        self.pairs = [(s, t) for s, t in pairs if len(s) <= max_len and len(t) <= max_len]
        self.tokenizer = tokenizer
        self.max_len = max_len

    def __len__(self) -> int:
        return len(self.pairs)

    def __getitem__(self, idx: int) -> tuple[list[int], list[int]]:
        src_text, tgt_text = self.pairs[idx]
        src = self.tokenizer.encode(src_text, add_bos=False, add_eos=True)
        tgt = self.tokenizer.encode(tgt_text, add_bos=True, add_eos=True)
        return src, tgt


def collate_fn(batch: list[tuple[list[int], list[int]]]) -> tuple[torch.Tensor, torch.Tensor]:
    srcs, tgts = zip(*batch)
    src_max = max(len(s) for s in srcs)
    tgt_max = max(len(t) for t in tgts)

    src_padded = torch.zeros(len(srcs), src_max, dtype=torch.long)
    tgt_padded = torch.zeros(len(tgts), tgt_max, dtype=torch.long)

    for i, (s, t) in enumerate(zip(srcs, tgts)):
        src_padded[i, : len(s)] = torch.tensor(s)
        tgt_padded[i, : len(t)] = torch.tensor(t)

    return src_padded, tgt_padded


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def load_tsv(path: str) -> list[tuple[str, str]]:
    pairs = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t", 1)
            if len(parts) == 2:
                pairs.append((parts[0], parts[1]))
    return pairs


def build_tokenizer(train_pairs: list[tuple[str, str]]) -> CharTokenizer:
    tok = CharTokenizer()
    all_texts = [s for s, t in train_pairs] + [t for s, t in train_pairs]
    tok.build_vocab(all_texts, min_freq=1)
    return tok


# ---------------------------------------------------------------------------
# Training loop
# ---------------------------------------------------------------------------

def train(args: argparse.Namespace) -> None:
    ckpt_dir = os.path.join(_BASE_CKPT_DIR, args.run_name)
    os.makedirs(ckpt_dir, exist_ok=True)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Device: {device}")

    # Load data
    train_pairs = load_tsv(os.path.join(args.data_dir, "train.tsv"))
    valid_pairs = load_tsv(os.path.join(args.data_dir, "valid.tsv"))
    print(f"Train: {len(train_pairs)}, Valid: {len(valid_pairs)}")

    # Tokenizer
    tok_path = os.path.join(args.data_dir, "tokenizer.json")
    if os.path.exists(tok_path):
        print("Loading existing tokenizer...")
        tokenizer = CharTokenizer.load(tok_path)
    else:
        print("Building tokenizer...")
        tokenizer = build_tokenizer(train_pairs)
        tokenizer.save(tok_path)
    print(f"Vocab size: {tokenizer.vocab_size}")

    # Datasets
    train_ds = KanaKanjiDataset(train_pairs, tokenizer, max_len=args.max_len)
    valid_ds = KanaKanjiDataset(valid_pairs, tokenizer, max_len=args.max_len)
    train_loader = DataLoader(train_ds, batch_size=args.batch_size, shuffle=True,
                              collate_fn=collate_fn, num_workers=2, pin_memory=True)
    valid_loader = DataLoader(valid_ds, batch_size=args.batch_size, shuffle=False,
                              collate_fn=collate_fn, num_workers=2, pin_memory=True)

    # Model
    model = KanaKanjiTransformer(
        vocab_size=tokenizer.vocab_size,
        d_model=args.d_model,
        nhead=args.nhead,
        num_encoder_layers=args.enc_layers,
        num_decoder_layers=args.dec_layers,
        d_ff=args.d_ff,
        dropout=args.dropout,
        max_len=args.max_len + 4,
        pad_id=PAD_ID,
    ).to(device)
    print(f"Model parameters: {model.num_parameters():,}")

    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-2)
    scheduler = torch.optim.lr_scheduler.OneCycleLR(
        optimizer, max_lr=args.lr,
        steps_per_epoch=len(train_loader),
        epochs=args.epochs,
        pct_start=0.05,
    )
    criterion = nn.CrossEntropyLoss(ignore_index=PAD_ID)

    best_valid_loss = float("inf")
    start_epoch = 1

    # Resume from last checkpoint if available
    last_ckpt_path = os.path.join(ckpt_dir, "last.pt")
    if os.path.exists(last_ckpt_path):
        print(f"Resuming from {last_ckpt_path} ...")
        ckpt = torch.load(last_ckpt_path, map_location=device)
        model.load_state_dict(ckpt["model_state"])
        optimizer.load_state_dict(ckpt["optimizer_state"])
        if "scheduler_state" in ckpt:
            scheduler.load_state_dict(ckpt["scheduler_state"])
        best_valid_loss = ckpt.get("best_val_loss", ckpt.get("val_loss", float("inf")))
        start_epoch = ckpt["epoch"] + 1
        print(f"Resumed: epoch {ckpt['epoch']} done, best_val_loss={best_valid_loss:.4f}, starting from epoch {start_epoch}")

    for epoch in range(start_epoch, args.epochs + 1):
        # Train
        model.train()
        total_loss = 0.0
        t0 = time.time()
        for step, (src, tgt) in enumerate(train_loader, 1):
            src = src.to(device)
            tgt = tgt.to(device)

            # Teacher forcing: decoder input = tgt[:-1], target = tgt[1:]
            tgt_in = tgt[:, :-1]
            tgt_out = tgt[:, 1:]

            logits = model(src, tgt_in)  # (B, T-1, V)
            loss = criterion(logits.reshape(-1, tokenizer.vocab_size), tgt_out.reshape(-1))

            optimizer.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            scheduler.step()

            total_loss += loss.item()
            if step % 500 == 0:
                avg = total_loss / step
                elapsed = time.time() - t0
                print(f"  Epoch {epoch} step {step}/{len(train_loader)} "
                      f"loss={avg:.4f} elapsed={elapsed:.0f}s")

        train_loss = total_loss / len(train_loader)

        # Validate
        model.eval()
        val_loss = 0.0
        with torch.no_grad():
            for src, tgt in valid_loader:
                src = src.to(device)
                tgt = tgt.to(device)
                tgt_in = tgt[:, :-1]
                tgt_out = tgt[:, 1:]
                logits = model(src, tgt_in)
                loss = criterion(logits.reshape(-1, tokenizer.vocab_size), tgt_out.reshape(-1))
                val_loss += loss.item()
        val_loss /= len(valid_loader)

        elapsed = time.time() - t0
        print(f"Epoch {epoch}/{args.epochs}  "
              f"train_loss={train_loss:.4f}  val_loss={val_loss:.4f}  "
              f"time={elapsed:.0f}s")

        # Save checkpoint
        is_best = val_loss < best_valid_loss
        if is_best:
            best_valid_loss = val_loss
        ckpt = {
            "epoch": epoch,
            "model_state": model.state_dict(),
            "optimizer_state": optimizer.state_dict(),
            "scheduler_state": scheduler.state_dict(),
            "val_loss": val_loss,
            "best_val_loss": best_valid_loss,
            "args": vars(args),
            "vocab_size": tokenizer.vocab_size,
        }
        torch.save(ckpt, os.path.join(ckpt_dir, "last.pt"))

        if is_best:
            torch.save(ckpt, os.path.join(ckpt_dir, "best.pt"))
            print(f"  -> Best model saved (val_loss={val_loss:.4f})")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--data-dir", type=str, default=DATA_DIR,
                   help="Directory holding train.tsv and valid.tsv (source TAB target), and tokenizer.json")
    p.add_argument("--run-name", type=str, default="m256",
                   help="Checkpoint subdirectory name (e.g. m256, m512, m768)")
    p.add_argument("--epochs", type=int, default=20)
    p.add_argument("--batch-size", type=int, default=512)
    p.add_argument("--lr", type=float, default=3e-4)
    p.add_argument("--d-model", type=int, default=256)
    p.add_argument("--nhead", type=int, default=8)
    p.add_argument("--enc-layers", type=int, default=4)
    p.add_argument("--dec-layers", type=int, default=4)
    p.add_argument("--d-ff", type=int, default=1024)
    p.add_argument("--dropout", type=float, default=0.1)
    p.add_argument("--max-len", type=int, default=128)
    return p.parse_args()


if __name__ == "__main__":
    train(parse_args())
