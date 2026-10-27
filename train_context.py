#!/usr/bin/env python3
"""
Context-aware kana-kanji training.

Encoder input:  left_context [SEP] kana_span [SEP] right_context
Decoder output: kanji_span

This allows post-conversion correction: select a span of committed text,
provide surrounding kanji context, and the model re-converts with full
sentence context.

Data: data/context_train.tsv  (4-column: left TAB kana TAB right TAB kanji)
      data/context_valid.tsv
"""

import os
import sys
import time
import argparse

import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader

sys.path.insert(0, os.path.dirname(__file__))
from model.tokenizer import CharTokenizer, BOS_ID, EOS_ID, PAD_ID, SEP_ID
from model.transformer import KanaKanjiTransformer

DATA_DIR = os.path.join(os.path.dirname(__file__), "data")
CKPT_DIR = os.path.join(os.path.dirname(__file__), "checkpoints")


class ContextDataset(Dataset):
    """Lazy tokenization. Stores raw (left, kana, right, kanji) tuples."""

    def __init__(self, records: list[tuple[str, str, str, str]],
                 tokenizer: CharTokenizer, max_len: int = 256):
        self.records = [
            r for r in records
            if len(r[0]) + len(r[1]) + len(r[2]) + 2 <= max_len
            and len(r[3]) <= max_len
        ]
        self.tokenizer = tokenizer
        self.max_len = max_len

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, idx: int) -> tuple[list[int], list[int]]:
        left, kana, right, kanji = self.records[idx]
        # Encoder: left_ctx [SEP] kana [SEP] right_ctx [EOS]
        src: list[int] = []
        if left:
            src += self.tokenizer.encode(left, add_bos=False, add_eos=False)
        src.append(SEP_ID)
        src += self.tokenizer.encode(kana, add_bos=False, add_eos=False)
        src.append(SEP_ID)
        if right:
            src += self.tokenizer.encode(right, add_bos=False, add_eos=False)
        src.append(EOS_ID)

        # Decoder: [BOS] kanji [EOS]
        tgt = self.tokenizer.encode(kanji, add_bos=True, add_eos=True)
        return src, tgt


def collate_fn(batch):
    srcs, tgts = zip(*batch)
    src_max = max(len(s) for s in srcs)
    tgt_max = max(len(t) for t in tgts)
    src_padded = torch.zeros(len(srcs), src_max, dtype=torch.long)
    tgt_padded = torch.zeros(len(tgts), tgt_max, dtype=torch.long)
    for i, (s, t) in enumerate(zip(srcs, tgts)):
        src_padded[i, :len(s)] = torch.tensor(s)
        tgt_padded[i, :len(t)] = torch.tensor(t)
    return src_padded, tgt_padded


def load_tsv4(path: str) -> list[tuple[str, str, str, str]]:
    records = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 4:
                records.append((parts[0], parts[1], parts[2], parts[3]))
    return records


def train(args: argparse.Namespace) -> None:
    os.makedirs(CKPT_DIR, exist_ok=True)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Device: {device}")

    train_records = load_tsv4(os.path.join(DATA_DIR, "context_train.tsv"))
    valid_records = load_tsv4(os.path.join(DATA_DIR, "context_valid.tsv"))
    print(f"Train: {len(train_records)}, Valid: {len(valid_records)}")

    tok_path = os.path.join(DATA_DIR, "tokenizer.json")
    tokenizer = CharTokenizer.load(tok_path)
    print(f"Vocab size: {tokenizer.vocab_size}")

    train_ds = ContextDataset(train_records, tokenizer, max_len=args.max_len)
    valid_ds = ContextDataset(valid_records, tokenizer, max_len=args.max_len)
    train_loader = DataLoader(train_ds, batch_size=args.batch_size,
                              shuffle=True, collate_fn=collate_fn,
                              num_workers=2, pin_memory=True)
    valid_loader = DataLoader(valid_ds, batch_size=args.batch_size,
                              shuffle=False, collate_fn=collate_fn,
                              num_workers=2, pin_memory=True)

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

    # Optionally warm-start from base model checkpoint
    base_ckpt = os.path.join(CKPT_DIR, "best.pt")
    if args.warmstart and os.path.exists(base_ckpt):
        ckpt = torch.load(base_ckpt, map_location=device)
        model.load_state_dict(ckpt["model_state"], strict=False)
        print(f"Warm-started from {base_ckpt}")

    print(f"Model parameters: {model.num_parameters():,}")

    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr,
                                  weight_decay=1e-2)
    scheduler = torch.optim.lr_scheduler.OneCycleLR(
        optimizer, max_lr=args.lr,
        steps_per_epoch=len(train_loader),
        epochs=args.epochs,
        pct_start=0.05,
    )
    criterion = nn.CrossEntropyLoss(ignore_index=PAD_ID)

    best_valid_loss = float("inf")

    for epoch in range(1, args.epochs + 1):
        model.train()
        total_loss = 0.0
        t0 = time.time()
        for step, (src, tgt) in enumerate(train_loader, 1):
            src = src.to(device)
            tgt = tgt.to(device)
            tgt_in = tgt[:, :-1]
            tgt_out = tgt[:, 1:]
            logits = model(src, tgt_in)
            loss = criterion(logits.reshape(-1, tokenizer.vocab_size),
                             tgt_out.reshape(-1))
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

        model.eval()
        val_loss = 0.0
        with torch.no_grad():
            for src, tgt in valid_loader:
                src = src.to(device)
                tgt = tgt.to(device)
                tgt_in = tgt[:, :-1]
                tgt_out = tgt[:, 1:]
                logits = model(src, tgt_in)
                loss = criterion(logits.reshape(-1, tokenizer.vocab_size),
                                 tgt_out.reshape(-1))
                val_loss += loss.item()
        val_loss /= len(valid_loader)

        elapsed = time.time() - t0
        print(f"Epoch {epoch}/{args.epochs}  "
              f"train_loss={train_loss:.4f}  val_loss={val_loss:.4f}  "
              f"time={elapsed:.0f}s")

        ckpt = {
            "epoch": epoch,
            "model_state": model.state_dict(),
            "optimizer_state": optimizer.state_dict(),
            "val_loss": val_loss,
            "args": vars(args),
            "vocab_size": tokenizer.vocab_size,
            "context_aware": True,
        }
        torch.save(ckpt, os.path.join(CKPT_DIR, "context_last.pt"))

        if val_loss < best_valid_loss:
            best_valid_loss = val_loss
            torch.save(ckpt, os.path.join(CKPT_DIR, "context_best.pt"))
            print(f"  -> Best model saved (val_loss={val_loss:.4f})")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--epochs", type=int, default=20)
    p.add_argument("--batch-size", type=int, default=512)
    p.add_argument("--lr", type=float, default=3e-4)
    p.add_argument("--d-model", type=int, default=256)
    p.add_argument("--nhead", type=int, default=8)
    p.add_argument("--enc-layers", type=int, default=4)
    p.add_argument("--dec-layers", type=int, default=4)
    p.add_argument("--d-ff", type=int, default=1024)
    p.add_argument("--dropout", type=float, default=0.1)
    p.add_argument("--max-len", type=int, default=256)
    p.add_argument("--warmstart", action="store_true",
                   help="Load weights from checkpoints/best.pt before training")
    return p.parse_args()


if __name__ == "__main__":
    train(parse_args())
