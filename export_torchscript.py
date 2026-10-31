#!/usr/bin/env python3
"""
Export a trained KanaKanjiTransformer to TorchScript (encoder and decoder as two files), the format
the DJL inference server (djl/KanjiServer) loads.

Usage: export_torchscript.py <checkpoint best.pt> <output-dir> <context chars> [model-name=m512-12]

<context chars> is the --context-chars of build_dataset_g.py the model was trained with. It is written to
<output-dir>/context_chars, which debian/slm-ime-server.sh gives the server as -Dmodel.contextChars.

Writes <output-dir>/encoder_<model-name>.pt and decoder_<model-name>.pt, and the two modules of the decoder with a
key-value cache (model/cached_decoder.py): crosskv_<model-name>.pt and step_<model-name>.pt.
  encoder(src, src_key_padding_mask)                       -> memory
  decoder(tgt, memory, tgt_mask, tgt_pad, memory_pad)      -> logits
  crosskv(memory)                                          -> cross_k, cross_v
  step(token, position, self_k, self_v, cross_k, cross_v)  -> logits, self_k, self_v
The decoder takes tgt_mask as a boolean matrix whose True entries hide the future, the form djl/KanjiServer
builds. It is traced with that form because the trace records the conversion of the mask for its type;
a trace made with a floating point mask gives wrong output when it is fed a boolean one.
The export is checked: for sources of several lengths the traced modules must give the same logits as
the original model.
"""

import os
import sys

import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model.cached_decoder import CrossKV, DecoderStep
from model.transformer import KanaKanjiTransformer


class Encoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, src, src_pad):
        return self.model.encode(src, src_pad)


class Decoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, tgt, memory, tgt_mask, tgt_pad, memory_pad):
        return self.model.decode(tgt, memory, tgt_mask, tgt_pad, memory_pad)


def main() -> None:
    if len(sys.argv) not in (4, 5):
        sys.exit(__doc__)
    checkpoint_path, out_dir, context_chars = sys.argv[1], sys.argv[2], int(sys.argv[3])
    name = sys.argv[4] if len(sys.argv) > 4 else "m512-12"
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "context_chars"), "w") as f:
        f.write(f"{context_chars}\n")

    checkpoint = torch.load(checkpoint_path, map_location="cpu")
    a = checkpoint["args"]
    model = KanaKanjiTransformer(
        vocab_size=checkpoint["vocab_size"], d_model=a["d_model"], nhead=a["nhead"],
        num_encoder_layers=a["enc_layers"], num_decoder_layers=a["dec_layers"],
        d_ff=a["d_ff"], dropout=0.0, max_len=a["max_len"] + 4)
    model.load_state_dict(checkpoint["model_state"])
    model.eval()

    def example(source_len, target_len):
        src = torch.randint(4, checkpoint["vocab_size"], (1, source_len))
        tgt = torch.randint(4, checkpoint["vocab_size"], (1, target_len))
        mask = torch.triu(torch.ones(target_len, target_len, dtype=torch.bool), diagonal=1)  # True hides the future
        return (src, torch.zeros(1, source_len, dtype=torch.bool), tgt, mask,
                torch.zeros(1, target_len, dtype=torch.bool))

    src, src_pad, tgt, tgt_mask, tgt_pad = example(12, 5)
    with torch.no_grad():
        memory = model.encode(src, src_pad)
        encoder = torch.jit.trace(Encoder(model), (src, src_pad), check_trace=False)
        decoder = torch.jit.trace(Decoder(model), (tgt, memory, tgt_mask, tgt_pad, src_pad), check_trace=False)
    encoder.save(os.path.join(out_dir, f"encoder_{name}.pt"))
    decoder.save(os.path.join(out_dir, f"decoder_{name}.pt"))

    layers, heads = a["dec_layers"], a["nhead"]
    head_dim = a["d_model"] // heads
    with torch.no_grad():
        crosskv = torch.jit.trace(CrossKV(model), (memory,), check_trace=False)
        step = torch.jit.trace(DecoderStep(model), (
            tgt[:, :1], torch.tensor([3]), torch.randn(layers, 1, heads, 3, head_dim),
            torch.randn(layers, 1, heads, 3, head_dim), *crosskv(memory)), check_trace=False)
    crosskv.save(os.path.join(out_dir, f"crosskv_{name}.pt"))
    step.save(os.path.join(out_dir, f"step_{name}.pt"))

    worst = 0.0
    worst_cached = 0.0
    with torch.no_grad():
        for source_len, target_len in ((3, 2), (20, 9), (57, 31), (100, 80)):
            src, src_pad, tgt, tgt_mask, tgt_pad = example(source_len, target_len)
            expected = model(src, tgt)
            got = decoder(tgt, encoder(src, src_pad), tgt_mask, tgt_pad, src_pad)
            worst = max(worst, float((expected - got).abs().max()))
            cross_k, cross_v = crosskv(encoder(src, src_pad))
            self_k = torch.zeros(layers, 1, heads, 0, head_dim)
            self_v = torch.zeros(layers, 1, heads, 0, head_dim)
            for t in range(target_len):
                logits, self_k, self_v = step(tgt[:, t:t + 1], torch.tensor([t]), self_k, self_v, cross_k, cross_v)
                worst_cached = max(worst_cached, float((expected[0, t] - logits[0, 0]).abs().max()))
    print(f"traced; largest difference from the original model: {worst:.2e} "
          f"(decoder), {worst_cached:.2e} (decoder with cache)")
    worst = max(worst, worst_cached)
    if worst > 1e-3:
        sys.exit("the traced modules do not match the original model")


if __name__ == "__main__":
    main()
