#!/usr/bin/env python3
"""Build `echo.gguf` — a deterministic byte-level echo model in GGUF.

This is a *real* llama-architecture transformer (1 block, RMSNorm, RoPE, GQA-1,
SwiGLU FFN), not a stub container. It loads and runs in llama.cpp like any other
model. The weights are hand-built rather than trained, so greedy decoding emits
exactly the last input token, unchanged, for every one of the 256 possible
inputs — which is what makes it useful as a smoke-test fixture: any wrong byte
is a runtime bug.

How the weights compute the identity function
-----------------------------------------------
Vocabulary and hidden size are both 256, one dimension per byte.

    token_embd = I                  so the residual stream carries one-hot e_t
    attn_{q,k,v,output}  = 0        the attention block contributes nothing
    ffn_{gate,up,down}   = 0        the FFN block contributes nothing
    {attn,ffn,output}_norm = 1      RMSNorm scales but does not mix dimensions
    output[t, t]  = 1               the LM head is the identity permutation

The residual stream therefore stays e_t through both blocks. The final RMSNorm
turns e_t into 16*e_t (rms of a one-hot vector in 256 dims is 1/16), so the
logits are 16 at row t and 0 everywhere else — a decisive argmax with a gap far
wider than any quantization or fp16 error.

Because attention output is identically zero, the result does not depend on
position, context length, RoPE settings, or the KV cache. Prompt the model with
any byte and it echoes it back, then echoes its own answer forever — unlike
ROT13's alternation, the identity function is a fixed point, so free-running
greedy decode just repeats the last prompt byte.

Tokenizer
---------
Byte-level BPE (GPT-2 alphabet) with 256 tokens and *no* merges, so tokenization
is exactly one token per input byte and token id == byte value. That keeps the
text-level contract as simple as the tensor-level one.

Usage:
    python3 build.py [-o echo.gguf]
"""

from __future__ import annotations

import argparse
import pathlib

import numpy as np
from gguf import GGUFValueType, GGUFWriter, LlamaFileType, TokenType

VOCAB = 256
N_EMBD = 256  # one dimension per byte, so embeddings can be one-hot
N_LAYER = 1
N_HEAD = 1
N_FF = 256
CONTEXT = 512
ROPE_FREQ_BASE = 10000.0
RMS_EPS = 1e-5


class _Writer(GGUFWriter):
    """GGUFWriter that can emit a genuinely empty array.

    llama.cpp refuses to load a BPE vocabulary unless `tokenizer.ggml.merges`
    is present, but this vocabulary really does have no merges — that is the
    whole point, since it is what makes tokenization exactly one token per byte.
    Upstream `add_array` silently drops empty lists and `_pack_val` rejects
    them, so serialize that one case here rather than inventing a dummy merge
    that would misdescribe the vocabulary.
    """

    def _pack_val(self, val, vtype, add_vtype, sub_type=None):  # type: ignore[override]
        if vtype == GGUFValueType.ARRAY and len(val) == 0:
            packed = bytearray()
            if add_vtype:
                packed += self._pack("I", GGUFValueType.ARRAY)
            packed += self._pack("I", sub_type)
            packed += self._pack("Q", 0)
            return packed
        return super()._pack_val(val, vtype, add_vtype, sub_type)


def echo_table() -> np.ndarray:
    """Byte -> byte identity permutation: every byte maps to itself."""
    return np.arange(VOCAB, dtype=np.int64)


def bytes_to_unicode() -> list[str]:
    """The GPT-2 byte-level alphabet: 256 distinct printable single-char tokens."""
    printable = (
        list(range(ord("!"), ord("~") + 1))
        + list(range(ord("\xa1"), ord("\xac") + 1))
        + list(range(ord("\xae"), ord("\xff") + 1))
    )
    mapped = printable[:]
    spare = 0
    for byte in range(VOCAB):
        if byte not in printable:
            printable.append(byte)
            mapped.append(VOCAB + spare)
            spare += 1
    alphabet = [""] * VOCAB
    for byte, code in zip(printable, mapped):
        alphabet[byte] = chr(code)
    return alphabet


def build(path: pathlib.Path) -> None:
    table = echo_table()

    writer = _Writer(str(path), "llama")

    writer.add_name("echo")
    writer.add_description(
        "Hand-built byte-level echo (identity) transformer; smoke-test fixture, not trained."
    )
    writer.add_license("EUPL-1.2")
    writer.add_file_type(LlamaFileType.ALL_F32)

    writer.add_context_length(CONTEXT)
    writer.add_embedding_length(N_EMBD)
    writer.add_block_count(N_LAYER)
    writer.add_feed_forward_length(N_FF)
    writer.add_head_count(N_HEAD)
    writer.add_head_count_kv(N_HEAD)
    writer.add_rope_dimension_count(N_EMBD // N_HEAD)
    writer.add_rope_freq_base(ROPE_FREQ_BASE)
    writer.add_layer_norm_rms_eps(RMS_EPS)

    # Byte-level BPE with an empty merge list: one token per byte, id == byte.
    writer.add_tokenizer_model("gpt2")
    writer.add_tokenizer_pre("default")
    writer.add_token_list(bytes_to_unicode())
    writer.add_token_types([TokenType.NORMAL] * VOCAB)
    writer.add_key_value(
        "tokenizer.ggml.merges", [], GGUFValueType.ARRAY, sub_type=GGUFValueType.STRING
    )
    # NUL is the only sensible sentinel in a byte vocabulary. The model never
    # emits it unless fed it, so generation is bounded by n_predict, not by EOS.
    writer.add_bos_token_id(0)
    writer.add_eos_token_id(0)
    writer.add_unk_token_id(0)
    writer.add_add_bos_token(False)
    writer.add_add_eos_token(False)

    zeros_square = np.zeros((N_EMBD, N_EMBD), dtype=np.float32)
    ones_vector = np.ones(N_EMBD, dtype=np.float32)

    lm_head = np.zeros((VOCAB, N_EMBD), dtype=np.float32)
    lm_head[table, np.arange(VOCAB)] = 1.0

    writer.add_tensor("token_embd.weight", np.eye(VOCAB, N_EMBD, dtype=np.float32))
    writer.add_tensor("blk.0.attn_norm.weight", ones_vector)
    writer.add_tensor("blk.0.attn_q.weight", zeros_square)
    writer.add_tensor("blk.0.attn_k.weight", zeros_square)
    writer.add_tensor("blk.0.attn_v.weight", zeros_square)
    writer.add_tensor("blk.0.attn_output.weight", zeros_square)
    writer.add_tensor("blk.0.ffn_norm.weight", ones_vector)
    writer.add_tensor("blk.0.ffn_gate.weight", np.zeros((N_FF, N_EMBD), dtype=np.float32))
    writer.add_tensor("blk.0.ffn_up.weight", np.zeros((N_FF, N_EMBD), dtype=np.float32))
    writer.add_tensor("blk.0.ffn_down.weight", np.zeros((N_EMBD, N_FF), dtype=np.float32))
    writer.add_tensor("output_norm.weight", ones_vector)
    writer.add_tensor("output.weight", lm_head)

    writer.write_header_to_file()
    writer.write_kv_data_to_file()
    writer.write_tensors_to_file()
    writer.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "-o",
        "--output",
        type=pathlib.Path,
        default=pathlib.Path(__file__).with_name("echo.gguf"),
    )
    args = parser.parse_args()

    build(args.output)
    print(f"wrote {args.output} ({args.output.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
