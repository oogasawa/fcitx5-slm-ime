#!/usr/bin/env python3
"""
Write the weights of a checkpoint in the form that the C++ engine reads (engine/src/model/weights.h).

Usage: export_weights.py <checkpoint .pt> <output .bin> <context chars>

<context chars> is the --context-chars of build_dataset_g.py the model was trained with (48 for ctx6, 100 for
ctx6b): how many characters before the cursor the engine gives the model.

The file is:
  8 bytes   the magic "SLMIMEW1"
  4 bytes   the length N of the header, little endian
  N bytes   the header, JSON: {"config": {...}, "tensors": [{"name", "shape", "offset"}, ...]}
  the data  every tensor as float32, little endian, in the order of the header; "offset" counts bytes from the
            start of the data, and every tensor starts at a multiple of 64 bytes

The config holds what the C++ code needs to build the model: vocab_size, d_model, nhead, enc_layers, dec_layers,
d_ff, max_len and context_chars. The tensors are those of the state dict of KanaKanjiTransformer (model/transformer.py) with their
names, and the positional encoding `pos_enc.pe` among them.
"""

import json
import struct
import sys

import torch

MAGIC = b"SLMIMEW1"
ALIGN = 64


def main() -> None:
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    checkpoint_path, output_path, context_chars = sys.argv[1], sys.argv[2], int(sys.argv[3])
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    args = checkpoint["args"]
    state = checkpoint["model_state"]
    config = {
        "vocab_size": checkpoint["vocab_size"],
        "d_model": args["d_model"],
        "nhead": args["nhead"],
        "enc_layers": args["enc_layers"],
        "dec_layers": args["dec_layers"],
        "d_ff": args["d_ff"],
        "max_len": state["pos_enc.pe"].shape[1],
        "context_chars": context_chars,
    }
    tensors, blobs, offset = [], [], 0
    for name, tensor in state.items():
        data = tensor.detach().to(torch.float32).contiguous().numpy().astype("<f4").tobytes()
        tensors.append({"name": name, "shape": list(tensor.shape), "offset": offset})
        padding = (-len(data)) % ALIGN
        blobs.append(data + b"\0" * padding)
        offset += len(data) + padding
    header = json.dumps({"config": config, "tensors": tensors}).encode("utf-8")
    # Spaces after the JSON so that the data starts at a multiple of ALIGN bytes from the start of the file: the engine
    # maps the file and reads the floats in place, and refuses data that is not float-aligned in memory
    header += b" " * ((-(len(MAGIC) + 4 + len(header))) % ALIGN)
    with open(output_path, "wb") as out:
        out.write(MAGIC)
        out.write(struct.pack("<I", len(header)))
        out.write(header)
        for blob in blobs:
            out.write(blob)
    print(f"{len(tensors)} tensors, {offset / 1e6:.1f} MB of data, config {config}")


if __name__ == "__main__":
    main()
