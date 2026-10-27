#!/usr/bin/env python3
"""
Greedy conversion of test sentences with a trained checkpoint, to see what the model really outputs.

Usage: greedy_check.py <run-dir with best.pt> <data-dir with test.tsv and tokenizer.json> [count=300] [show=8] [test-file] [dump-file]
The optional test-file (source TAB target) replaces <data-dir>/test.tsv, to measure the same sentences as another system.
The optional dump-file receives one line per sentence: source TAB output (with the | the model wrote).

Prints the character error rate of the output with '|' removed (the conversion itself), the share of
exact matches, the character error rate of the boundary marks alone (positions of '|' kept, other
characters replaced by one symbol), and a few examples.
The decoding follows the Java inference: greedy, and stop at EOS or when the output reaches the input length.
"""

import os
import sys

import torch

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from model.tokenizer import CharTokenizer, BOS_ID, EOS_ID, PAD_ID, SEP_CHAR
from model.transformer import KanaKanjiTransformer


def edit_distance(a: str, b: str) -> int:
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


def main() -> None:
    run_dir, data_dir = sys.argv[1], sys.argv[2]
    count = int(sys.argv[3]) if len(sys.argv) > 3 else 300
    show = int(sys.argv[4]) if len(sys.argv) > 4 else 8
    test_file = sys.argv[5] if len(sys.argv) > 5 else os.path.join(sys.argv[2], "test.tsv")
    dump = open(sys.argv[6], "w", encoding="utf-8") if len(sys.argv) > 6 else None
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    checkpoint = torch.load(os.path.join(run_dir, "best.pt"), map_location=device)
    args = checkpoint["args"]
    tokenizer = CharTokenizer.load(os.path.join(data_dir, "tokenizer.json"))
    model = KanaKanjiTransformer(
        vocab_size=checkpoint["vocab_size"], d_model=args["d_model"], nhead=args["nhead"],
        num_encoder_layers=args["enc_layers"], num_decoder_layers=args["dec_layers"],
        d_ff=args["d_ff"], dropout=0.0, max_len=args["max_len"] + 4, pad_id=PAD_ID).to(device)
    model.load_state_dict(checkpoint["model_state"])
    model.eval()
    print(f"checkpoint epoch {checkpoint['epoch']}, val_loss {checkpoint['val_loss']:.4f}")

    pairs = []
    with open(test_file, encoding="utf-8") as f:
        for line in f:
            source, target = line.rstrip("\n").split("\t", 1)
            if len(source) <= args["max_len"]:
                pairs.append((source, target))
    step = max(1, len(pairs) // count)
    pairs = pairs[::step][:count]

    chars = errors = exact = 0
    boundary_chars = boundary_errors = 0
    examples = []
    with torch.no_grad():
        for source, target in pairs:
            src = torch.tensor([tokenizer.encode(source, add_bos=False, add_eos=True)], device=device)
            memory = model.encode(src, src == PAD_ID)
            output = [BOS_ID]
            # The output is about as long as the reading: the part after the separator, if the source has context.
            for _ in range(len(source.split(SEP_CHAR)[-1]) + 1):
                tgt = torch.tensor([output], device=device)
                mask = torch.nn.Transformer.generate_square_subsequent_mask(tgt.size(1), device=device)
                logits = model.decode(tgt, memory, mask, tgt == PAD_ID, src == PAD_ID)
                next_id = int(logits[0, -1].argmax())
                if next_id == EOS_ID:
                    break
                output.append(next_id)
            predicted = tokenizer.decode(output[1:])
            if dump:
                dump.write(source + "\t" + predicted + "\n")
            plain_target, plain_predicted = target.replace("|", ""), predicted.replace("|", "")
            errors += edit_distance(plain_predicted, plain_target)
            chars += len(plain_target)
            exact += plain_predicted == plain_target
            marks = lambda s: "".join("|" if c == "|" else "." for c in s)
            boundary_errors += edit_distance(marks(predicted), marks(target))
            boundary_chars += len(target)
            if len(examples) < show:
                examples.append((source, target, predicted))

    print(f"sentences {len(pairs)}")
    print(f"conversion character error rate (| removed): {errors / chars * 100:.2f}%   exact match: {exact / len(pairs) * 100:.1f}%")
    print(f"boundary character error rate (| positions) : {boundary_errors / boundary_chars * 100:.2f}%")
    for source, target, predicted in examples:
        print(f"\nIN   {source}\nWANT {target}\nGOT  {predicted}")


if __name__ == "__main__":
    main()
