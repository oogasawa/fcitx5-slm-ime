"""
Small Encoder-Decoder Transformer for kana-kanji conversion.

Architecture (T5-style):
  - Encoder: 4 layers, d_model=256, 8 heads, d_ff=1024
  - Decoder: 4 layers, same dims
  - Total params: ~10M

Input:  hiragana sequence (src)
Output: kanji/mixed Japanese sequence (tgt)
"""

import math
import torch
import torch.nn as nn
from torch import Tensor


class PositionalEncoding(nn.Module):
    def __init__(self, d_model: int, max_len: int = 512, dropout: float = 0.1):
        super().__init__()
        self.dropout = nn.Dropout(dropout)
        pe = torch.zeros(max_len, d_model)
        pos = torch.arange(max_len).unsqueeze(1).float()
        div = torch.exp(torch.arange(0, d_model, 2).float() * (-math.log(10000.0) / d_model))
        pe[:, 0::2] = torch.sin(pos * div)
        pe[:, 1::2] = torch.cos(pos * div)
        self.register_buffer("pe", pe.unsqueeze(0))  # (1, max_len, d_model)

    def forward(self, x: Tensor) -> Tensor:
        x = x + self.pe[:, : x.size(1)]
        return self.dropout(x)


class KanaKanjiTransformer(nn.Module):
    def __init__(
        self,
        vocab_size: int,
        d_model: int = 256,
        nhead: int = 8,
        num_encoder_layers: int = 4,
        num_decoder_layers: int = 4,
        d_ff: int = 1024,
        dropout: float = 0.1,
        max_len: int = 256,
        pad_id: int = 0,
    ):
        super().__init__()
        self.d_model = d_model
        self.pad_id = pad_id

        self.src_embed = nn.Embedding(vocab_size, d_model, padding_idx=pad_id)
        self.tgt_embed = nn.Embedding(vocab_size, d_model, padding_idx=pad_id)
        self.pos_enc = PositionalEncoding(d_model, max_len, dropout)

        encoder_layer = nn.TransformerEncoderLayer(
            d_model=d_model, nhead=nhead, dim_feedforward=d_ff,
            dropout=dropout, batch_first=True, norm_first=True,
        )
        self.encoder = nn.TransformerEncoder(encoder_layer, num_layers=num_encoder_layers)

        decoder_layer = nn.TransformerDecoderLayer(
            d_model=d_model, nhead=nhead, dim_feedforward=d_ff,
            dropout=dropout, batch_first=True, norm_first=True,
        )
        self.decoder = nn.TransformerDecoder(decoder_layer, num_layers=num_decoder_layers)

        self.out_proj = nn.Linear(d_model, vocab_size)

        self._init_weights()

    def _init_weights(self) -> None:
        for p in self.parameters():
            if p.dim() > 1:
                nn.init.xavier_uniform_(p)

    def encode(self, src: Tensor, src_key_padding_mask: Tensor) -> Tensor:
        """Encode source sequence. Returns memory: (B, S, d_model)"""
        x = self.pos_enc(self.src_embed(src) * math.sqrt(self.d_model))
        return self.encoder(x, src_key_padding_mask=src_key_padding_mask)

    def decode(
        self,
        tgt: Tensor,
        memory: Tensor,
        tgt_mask: Tensor,
        tgt_key_padding_mask: Tensor,
        memory_key_padding_mask: Tensor,
    ) -> Tensor:
        """Decode one step. Returns logits: (B, T, vocab_size)"""
        x = self.pos_enc(self.tgt_embed(tgt) * math.sqrt(self.d_model))
        out = self.decoder(
            x, memory,
            tgt_mask=tgt_mask,
            tgt_key_padding_mask=tgt_key_padding_mask,
            memory_key_padding_mask=memory_key_padding_mask,
        )
        return self.out_proj(out)

    def forward(self, src: Tensor, tgt: Tensor) -> Tensor:
        """
        src: (B, S) — hiragana token ids
        tgt: (B, T) — kanji token ids (teacher-forced, includes BOS, excludes EOS)
        Returns logits: (B, T, vocab_size)
        """
        src_pad_mask = src == self.pad_id
        tgt_pad_mask = tgt == self.pad_id
        tgt_len = tgt.size(1)
        tgt_mask = nn.Transformer.generate_square_subsequent_mask(tgt_len, device=src.device)

        memory = self.encode(src, src_pad_mask)
        return self.decode(tgt, memory, tgt_mask, tgt_pad_mask, src_pad_mask)

    def num_parameters(self) -> int:
        return sum(p.numel() for p in self.parameters() if p.requires_grad)
