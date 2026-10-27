#!/usr/bin/env python3
"""
Does a model trained WITHOUT context make use of the text before the cursor when it is simply put in front of the
reading?  Three inputs for the same sentences, from a test file made with --context-chars:
  A  reading only
  B  text before the cursor, then the reading (nothing between)
  C  text before the cursor, [SEP], then the reading
For B and C the model also converts the text before the cursor, so only the end of its output, as long as the
correct conversion of the sentence, is compared with the correct conversion.

Usage: context_probe.py <run-dir with best.pt> <tokenizer.json> <test.tsv with context> [count=150]
"""
import os
import sys

import torch

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from model.tokenizer import CharTokenizer, BOS_ID, EOS_ID, PAD_ID, SEP_CHAR
from model.transformer import KanaKanjiTransformer


def edit_distance(a, b):
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


def main():
    run_dir, tokenizer_path, test_path = sys.argv[1:4]
    count = int(sys.argv[4]) if len(sys.argv) > 4 else 150
    checkpoint = torch.load(os.path.join(run_dir, "best.pt"), map_location="cpu")
    a = checkpoint["args"]
    tokenizer = CharTokenizer.load(tokenizer_path)
    model = KanaKanjiTransformer(vocab_size=checkpoint["vocab_size"], d_model=a["d_model"], nhead=a["nhead"],
                                 num_encoder_layers=a["enc_layers"], num_decoder_layers=a["dec_layers"],
                                 d_ff=a["d_ff"], dropout=0.0, max_len=a["max_len"] + 4, pad_id=PAD_ID)
    model.load_state_dict(checkpoint["model_state"])
    model.eval()
    torch.set_num_threads(8)

    rows = []
    for line in open(test_path, encoding="utf-8"):
        source, target = line.rstrip("\n").split("\t", 1)
        context, reading = source.split(SEP_CHAR, 1)
        if len(context) >= 20 and 8 <= len(reading) <= 60:
            rows.append((context, reading, target.replace("|", "")))
    step = max(1, len(rows) // count)
    rows = rows[::step][:count]

    def convert(text, cap):
        src = torch.tensor([tokenizer.encode(text, add_bos=False, add_eos=True)])
        with torch.no_grad():
            memory = model.encode(src, src == PAD_ID)
            out = [BOS_ID]
            for _ in range(cap):
                tgt = torch.tensor([out])
                mask = torch.nn.Transformer.generate_square_subsequent_mask(tgt.size(1))
                logits = model.decode(tgt, memory, mask, tgt == PAD_ID, src == PAD_ID)
                nxt = int(logits[0, -1].argmax())
                if nxt == EOS_ID:
                    break
                out.append(nxt)
        return tokenizer.decode(out[1:]).replace("|", "")

    variants = {"A reading only": lambda c, r: r,
                "B context + reading": lambda c, r: c + r,
                "C context + SEP + reading": lambda c, r: c + SEP_CHAR + r}
    errors = {k: 0 for k in variants}
    exact = {k: 0 for k in variants}
    chars = 0
    shown = 0
    for context, reading, target in rows:
        chars += len(target)
        outputs = {}
        for name, build in variants.items():
            text = build(context, reading)
            output = convert(text, len(text) + 2)
            tail = output[-len(target):]
            errors[name] += edit_distance(tail, target)
            exact[name] += tail == target
            outputs[name] = output
        if shown < 3:
            shown += 1
            print(f"\nCONTEXT {context[-30:]}\nREADING {reading}\nWANT    {target}")
            for name, output in outputs.items():
                print(f"{name:28} {output}")
    print(f"\nsentences {len(rows)}, correct characters {chars}")
    for name in variants:
        print(f"{name:28} character error rate {errors[name] / chars * 100:6.2f}%   exact {exact[name] / len(rows) * 100:5.1f}%")


if __name__ == "__main__":
    main()
