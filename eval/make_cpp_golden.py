#!/usr/bin/env python3
"""
Write the answers of the PyTorch model for some sources, so that the C++ model (engine/src/model/) can be checked
against them (engine/test/model-check.cpp).

Usage: make_cpp_golden.py <checkpoint .pt> <tokenizer.json> <cases .tsv> <output .json>

The cases are rows of  reading <TAB> text before the cursor [<TAB> candidate <TAB> candidate ...]. For each the
output has the source ids, the greedy output ids (with the stopping rule of the engine: EOS, or as many tokens
other than '|' as the reading has characters plus one, or 2 * that + 5 steps), the five largest logits of the first
step, and the log probability of every candidate written whole and followed by EOS.
"""

import json
import os
import sys

import torch

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from model.tokenizer import BOS_ID, EOS_ID, SEP_ID, CharTokenizer
from model.transformer import KanaKanjiTransformer

CONTEXT_CHARS = 48


def main() -> None:
    checkpoint_path, tokenizer_path, cases_path, output_path = sys.argv[1:5]
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    args = checkpoint["args"]
    model = KanaKanjiTransformer(
        vocab_size=checkpoint["vocab_size"], d_model=args["d_model"], nhead=args["nhead"],
        num_encoder_layers=args["enc_layers"], num_decoder_layers=args["dec_layers"], d_ff=args["d_ff"],
        dropout=0.0, max_len=checkpoint["model_state"]["pos_enc.pe"].shape[1])
    model.load_state_dict(checkpoint["model_state"])
    model.eval()
    tokenizer = CharTokenizer.load(tokenizer_path)
    bar = tokenizer.char2id["|"]

    def chars(text):
        return tokenizer.encode(text, add_bos=False, add_eos=False)

    out = []
    with torch.no_grad():
        for line in open(cases_path, encoding="utf-8"):
            columns = line.rstrip("\n").split("\t")
            reading, context, candidates = columns[0], columns[1], columns[2:]
            src = chars(context[-CONTEXT_CHARS:]) + [SEP_ID] + chars(reading) + [EOS_ID]
            src_t = torch.tensor([src])
            memory = model.encode(src_t, src_t == -1)
            limit = len(chars(reading)) + 1
            tgt, output, text_len, first_top = [BOS_ID], [], 0, None
            for step in range(2 * limit + 5):
                t = torch.tensor([tgt])
                mask = torch.nn.Transformer.generate_square_subsequent_mask(len(tgt))
                logits = model.decode(t, memory, mask, t == -1, src_t == -1)[0, -1]
                if first_top is None:
                    values, ids = logits.topk(5)
                    first_top = [[int(i), float(v)] for i, v in zip(ids, values)]
                nxt = int(logits.argmax())
                if nxt == EOS_ID:
                    break
                output.append(nxt)
                tgt.append(nxt)
                text_len += nxt != bar
                if text_len >= limit:
                    break
            texts, logprobs = [], []
            for candidate in candidates:
                ids = chars(candidate)
                t = torch.tensor([[BOS_ID] + ids])
                mask = torch.nn.Transformer.generate_square_subsequent_mask(t.size(1))
                lp = torch.log_softmax(model.decode(t, memory, mask, t == -1, src_t == -1)[0].double(), dim=-1)
                targets = ids + [EOS_ID]
                logprobs.append(float(sum(lp[i, targets[i]] for i in range(len(targets)))))
                texts.append(ids)
            out.append({"reading": reading, "src": src, "max_text_len": limit, "bar": bar, "greedy": output,
                        "first_top5": first_top, "texts": texts, "logprobs": logprobs,
                        "greedy_text": tokenizer.decode(output)})
    json.dump(out, open(output_path, "w", encoding="utf-8"), ensure_ascii=False)
    print(f"{len(out)} cases written to {output_path}")


if __name__ == "__main__":
    main()
