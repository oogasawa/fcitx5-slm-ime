"""
The decoder of KanaKanjiTransformer written so that one call computes one output position and reuses the
keys and values of the earlier positions (a key-value cache).

nn.TransformerDecoder recomputes every position of the prefix at every step. The two modules below give
the same logits with the work of one position per step:

  CrossKV(memory)                                       -> cross_k, cross_v   (once per request)
  DecoderStep(token, position, self_k, self_v, cross_k, cross_v)
                                                        -> logits, self_k, self_v   (once per output position)

The tensors of all layers are stacked on the first axis: self_k and self_v are (layers, 1, heads, t, head_dim),
cross_k and cross_v are (layers, 1, heads, source_len, head_dim). The caller starts with t = 0 and feeds back
the returned self_k and self_v. The layers are the pre-norm layers (norm_first=True) of the training model,
without dropout and without a final norm, and the source is not padded (batch size 1, no padding mask).
"""

import math

import torch
import torch.nn.functional as F
from torch import Tensor, nn


def _heads(x: Tensor, heads: int) -> Tensor:
    """(1, n, d_model) -> (1, heads, n, head_dim)"""
    n = x.size(1)
    return x.view(1, n, heads, -1).transpose(1, 2)


class CrossKV(nn.Module):
    """Keys and values that the cross attention of every decoder layer takes from the encoder output."""

    def __init__(self, model):
        super().__init__()
        self.layers = model.decoder.layers
        self.heads = self.layers[0].multihead_attn.num_heads
        self.d_model = model.d_model

    def forward(self, memory: Tensor):
        keys, values = [], []
        for layer in self.layers:
            attn = layer.multihead_attn
            w, b = attn.in_proj_weight, attn.in_proj_bias
            d = self.d_model
            k = F.linear(memory, w[d:2 * d], b[d:2 * d])
            v = F.linear(memory, w[2 * d:], b[2 * d:])
            keys.append(_heads(k, self.heads))
            values.append(_heads(v, self.heads))
        return torch.stack(keys), torch.stack(values)


class DecoderStep(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.layers = model.decoder.layers
        self.heads = self.layers[0].self_attn.num_heads
        self.d_model = model.d_model
        self.tgt_embed = model.tgt_embed
        self.pe = model.pos_enc.pe[0]
        self.out_proj = model.out_proj

    def _attend(self, q: Tensor, k: Tensor, v: Tensor) -> Tensor:
        """q (1, heads, 1, hd), k and v (1, heads, n, hd) -> (1, 1, d_model)"""
        scores = torch.matmul(q, k.transpose(-1, -2)) / math.sqrt(q.size(-1))
        out = torch.matmul(torch.softmax(scores, dim=-1), v)
        return out.transpose(1, 2).reshape(1, 1, self.d_model)

    def forward(self, token: Tensor, position: Tensor, self_k: Tensor, self_v: Tensor,
                cross_k: Tensor, cross_v: Tensor):
        d = self.d_model
        x = self.tgt_embed(token) * math.sqrt(d) + self.pe.index_select(0, position).unsqueeze(0)
        new_k, new_v = [], []
        for i, layer in enumerate(self.layers):
            # self attention over the cached positions and this one
            h = layer.norm1(x)
            attn = layer.self_attn
            w, b = attn.in_proj_weight, attn.in_proj_bias
            q = _heads(F.linear(h, w[:d], b[:d]), self.heads)
            k = torch.cat([self_k[i], _heads(F.linear(h, w[d:2 * d], b[d:2 * d]), self.heads)], dim=2)
            v = torch.cat([self_v[i], _heads(F.linear(h, w[2 * d:], b[2 * d:]), self.heads)], dim=2)
            new_k.append(k)
            new_v.append(v)
            x = x + attn.out_proj(self._attend(q, k, v))
            # cross attention over the encoder output
            h = layer.norm2(x)
            cross = layer.multihead_attn
            q = _heads(F.linear(h, cross.in_proj_weight[:d], cross.in_proj_bias[:d]), self.heads)
            x = x + cross.out_proj(self._attend(q, cross_k[i], cross_v[i]))
            # feed forward
            h = layer.norm3(x)
            x = x + layer.linear2(F.relu(layer.linear1(h)))
        return self.out_proj(x), torch.stack(new_k), torch.stack(new_v)
