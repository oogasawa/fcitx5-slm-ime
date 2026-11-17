#!/usr/bin/env python3
"""
Training script for kana-kanji Transformer.
Usage: python3 train.py --data-dir DATA_DIR --run-name NAME --epochs 8 --batch-size 64 \
    --d-model 512 --nhead 8 --enc-layers 6 --dec-layers 6 --d-ff 2048 --max-len 192

Checkpoints are saved to checkpoints/<run-name>/: last.pt (to resume), best.pt (lowest validation loss) and
epoch_NN.pt (the weights after every epoch).

Several GPUs: start the same command under torchrun, for example
    torchrun --standalone --nproc_per_node=2 train.py --data-dir DATA_DIR --run-name NAME --batch-size 1024 ...
Each GPU then runs one process (DistributedDataParallel). --batch-size stays the batch of one optimizer step over all
GPUs (each GPU takes batch-size / GPUs rows), and the learning rate schedule has the same number of steps as on one
GPU, so a model trained on several GPUs can be compared with one trained on one. Only the first process prints and
writes the checkpoints; they hold the same weights, under the same names, as those of a run on one GPU.
Started without torchrun, the script runs on one GPU exactly as before.
"""

import os
import sys
import time
import math
import random
import argparse
import datetime

import torch
import torch.distributed as dist
import torch.nn as nn
from torch.nn.parallel import DistributedDataParallel
from torch.utils.data import Dataset, DataLoader
from torch.utils.data.distributed import DistributedSampler

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
    # torchrun sets WORLD_SIZE, RANK and LOCAL_RANK; without it there is one process and nothing is distributed
    world_size = int(os.environ.get("WORLD_SIZE", "1"))
    distributed = world_size > 1
    rank = int(os.environ.get("RANK", "0"))
    local_rank = int(os.environ.get("LOCAL_RANK", "0"))
    main_process = rank == 0
    if distributed:
        if args.batch_size % world_size != 0:
            raise SystemExit(f"--batch-size {args.batch_size} is not a multiple of the {world_size} GPUs")
        torch.cuda.set_device(local_rank)
        # The other processes wait at a barrier while the first builds the tokenizer, which takes several minutes
        # on tens of millions of rows: the default limit of ten minutes is too short
        dist.init_process_group(backend="nccl", timeout=datetime.timedelta(hours=2),
                                device_id=torch.device("cuda", local_rank))
        device = torch.device("cuda", local_rank)
    else:
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    rank_batch = args.batch_size // world_size

    def log(msg: str) -> None:
        if main_process:
            print(msg, flush=True)

    if args.seed is not None:
        # Each process its own seed, so that dropout differs between them; the initial weights are the first
        # process's anyway, as DistributedDataParallel copies them to the others
        random.seed(args.seed + rank)
        torch.manual_seed(args.seed + rank)

    ckpt_dir = os.path.join(_BASE_CKPT_DIR, args.run_name)
    if main_process:
        os.makedirs(ckpt_dir, exist_ok=True)
    log(f"Device: {device}" + (f" x {world_size} processes, {rank_batch} rows each per step" if distributed else ""))
    use_amp = args.amp == "bf16" and device.type == "cuda"
    # The cuDNN attention kernel, on by default in PyTorch 2.5.0, makes a bf16 step about 12 times slower than an
    # fp32 step on the RTX 4080 (1804 ms against 144 ms); without it a bf16 step takes 72 ms.
    torch.backends.cuda.enable_cudnn_sdp(False)

    # Load data
    train_pairs = load_tsv(os.path.join(args.data_dir, "train.tsv"))
    valid_pairs = load_tsv(os.path.join(args.data_dir, "valid.tsv"))
    log(f"Train: {len(train_pairs)}, Valid: {len(valid_pairs)}")

    # Tokenizer: built and written by the first process only; the others read the file it wrote
    tok_path = os.path.join(args.data_dir, "tokenizer.json")
    if main_process and not os.path.exists(tok_path):
        log("Building tokenizer...")
        build_tokenizer(train_pairs).save(tok_path)
    if distributed:
        dist.barrier()
    log("Loading tokenizer...")
    tokenizer = CharTokenizer.load(tok_path)
    log(f"Vocab size: {tokenizer.vocab_size}")

    # Datasets. On several GPUs each process reads its own share of the rows (DistributedSampler, shuffled anew
    # every epoch); the number of steps per epoch is then about that of one GPU with the whole batch.
    train_ds = KanaKanjiDataset(train_pairs, tokenizer, max_len=args.max_len)
    valid_ds = KanaKanjiDataset(valid_pairs, tokenizer, max_len=args.max_len)
    train_sampler = DistributedSampler(train_ds, shuffle=True, seed=args.seed or 0) if distributed else None
    valid_sampler = DistributedSampler(valid_ds, shuffle=False) if distributed else None
    train_loader = DataLoader(train_ds, batch_size=rank_batch, shuffle=train_sampler is None, sampler=train_sampler,
                              collate_fn=collate_fn, num_workers=args.num_workers, pin_memory=True)
    valid_loader = DataLoader(valid_ds, batch_size=rank_batch, shuffle=False, sampler=valid_sampler,
                              collate_fn=collate_fn, num_workers=args.num_workers, pin_memory=True)

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
    log(f"Model parameters: {model.num_parameters():,}")

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
        log(f"Resuming from {last_ckpt_path} ...")
        ckpt = torch.load(last_ckpt_path, map_location=device)
        model.load_state_dict(ckpt["model_state"])
        optimizer.load_state_dict(ckpt["optimizer_state"])
        if "scheduler_state" in ckpt:
            scheduler.load_state_dict(ckpt["scheduler_state"])
        best_valid_loss = ckpt.get("best_val_loss", ckpt.get("val_loss", float("inf")))
        start_epoch = ckpt["epoch"] + 1
        log(f"Resumed: epoch {ckpt['epoch']} done, best_val_loss={best_valid_loss:.4f}, starting from epoch {start_epoch}")

    # On several GPUs the model is wrapped: the wrapper averages the gradients of the processes in backward().
    # Checkpoints keep the weights of the model inside (raw_model), so they have the names of a run on one GPU.
    raw_model = model
    if distributed:
        model = DistributedDataParallel(model, device_ids=[local_rank])

    for epoch in range(start_epoch, args.epochs + 1):
        # Train
        model.train()
        if train_sampler is not None:
            train_sampler.set_epoch(epoch)
        total_loss = 0.0
        t0 = time.time()
        for step, (src, tgt) in enumerate(train_loader, 1):
            src = src.to(device)
            tgt = tgt.to(device)

            # Teacher forcing: decoder input = tgt[:-1], target = tgt[1:]
            tgt_in = tgt[:, :-1]
            tgt_out = tgt[:, 1:]

            with torch.autocast(device_type="cuda", dtype=torch.bfloat16, enabled=use_amp):
                logits = model(src, tgt_in)  # (B, T-1, V)
            loss = criterion(logits.float().reshape(-1, tokenizer.vocab_size), tgt_out.reshape(-1))

            optimizer.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            scheduler.step()

            total_loss += loss.item()
            if args.max_steps and step >= args.max_steps:
                torch.cuda.synchronize()
                took = time.time() - t0
                log(f"BENCH amp={args.amp} batch={args.batch_size} gpus={world_size} steps={step} "
                    f"{1000 * took / step:.1f} ms/step loss={total_loss / step:.4f} "
                    f"peak_memory={torch.cuda.max_memory_allocated() / 2**30:.1f}GiB")
                if distributed:
                    dist.destroy_process_group()
                sys.exit(0)
            if step % 500 == 0:
                # On several GPUs: the loss of the first process's rows only, as a sign of progress
                avg = total_loss / step
                elapsed = time.time() - t0
                log(f"  Epoch {epoch} step {step}/{len(train_loader)} "
                    f"loss={avg:.4f} elapsed={elapsed:.0f}s")

        train_loss = mean_over_processes(total_loss, len(train_loader), device, distributed)

        # Validate
        # Each process validates its share of the rows with the model inside the wrapper; the losses are then
        # averaged over all processes.
        raw_model.eval()
        val_loss = 0.0
        with torch.no_grad():
            for src, tgt in valid_loader:
                src = src.to(device)
                tgt = tgt.to(device)
                tgt_in = tgt[:, :-1]
                tgt_out = tgt[:, 1:]
                with torch.autocast(device_type="cuda", dtype=torch.bfloat16, enabled=use_amp):
                    logits = raw_model(src, tgt_in)
                loss = criterion(logits.float().reshape(-1, tokenizer.vocab_size), tgt_out.reshape(-1))
                val_loss += loss.item()
        # A small trial (prep/train_from_scratch.sh with SMOKE=1) may have no validation rows: no loss then, and no best.pt
        val_loss = mean_over_processes(val_loss, len(valid_loader), device, distributed)

        elapsed = time.time() - t0
        log(f"Epoch {epoch}/{args.epochs}  "
            f"train_loss={train_loss:.4f}  val_loss={val_loss:.4f}  "
            f"time={elapsed:.0f}s")

        # Save checkpoint
        is_best = val_loss < best_valid_loss  # False for nan
        if is_best:
            best_valid_loss = val_loss
        if not main_process:
            continue
        ckpt = {
            "epoch": epoch,
            "model_state": raw_model.state_dict(),
            "optimizer_state": optimizer.state_dict(),
            "scheduler_state": scheduler.state_dict(),
            "val_loss": val_loss,
            "best_val_loss": best_valid_loss,
            "args": vars(args),
            "vocab_size": tokenizer.vocab_size,
        }
        torch.save(ckpt, os.path.join(ckpt_dir, "last.pt"))
        # Every epoch is kept, so that a later epoch that converts worse can be gone back from. Only the weights:
        # last.pt alone is enough to resume, and the optimizer state would double the size.
        torch.save({k: v for k, v in ckpt.items() if k not in ("optimizer_state", "scheduler_state")},
                   os.path.join(ckpt_dir, f"epoch_{epoch:02d}.pt"))

        if is_best:
            torch.save(ckpt, os.path.join(ckpt_dir, "best.pt"))
            log(f"  -> Best model saved (val_loss={val_loss:.4f})")

    if distributed:
        dist.barrier()
        dist.destroy_process_group()


def mean_over_processes(loss_sum: float, batches: int, device: torch.device, distributed: bool) -> float:
    """The mean loss per batch over the batches of every process (nan when there were none)."""
    if distributed:
        t = torch.tensor([loss_sum, float(batches)], dtype=torch.float64, device=device)
        dist.all_reduce(t)
        loss_sum, batches = t[0].item(), int(t[1].item())
    return loss_sum / batches if batches > 0 else float("nan")


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
    p.add_argument("--amp", choices=["none", "bf16"], default="bf16",
                   help="bf16: the forward pass runs in bfloat16 autocast; the loss, the weights and the optimizer stay float32")
    p.add_argument("--max-steps", type=int, default=0,
                   help="stop after this many steps of the first epoch and print the time per step (0: train normally)")
    p.add_argument("--lr", type=float, default=3e-4)
    p.add_argument("--d-model", type=int, default=256)
    p.add_argument("--nhead", type=int, default=8)
    p.add_argument("--enc-layers", type=int, default=4)
    p.add_argument("--dec-layers", type=int, default=4)
    p.add_argument("--d-ff", type=int, default=1024)
    p.add_argument("--dropout", type=float, default=0.1)
    p.add_argument("--max-len", type=int, default=128)
    p.add_argument("--num-workers", type=int, default=2, help="processes that tokenize the rows for the batches")
    p.add_argument("--seed", type=int, default=None,
                   help="seed of the random numbers (initial weights, order of the rows); none: not seeded, as before")
    return p.parse_args()


if __name__ == "__main__":
    train(parse_args())
