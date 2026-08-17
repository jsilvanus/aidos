#!/usr/bin/env python3
"""Verify `rot13.gguf` — structurally, then numerically, then (if available) for real.

Three layers, each independent of the one below it:

  1. Parse the container with a self-contained GGUF reader (no `gguf` package),
     so a bad file is caught even if the writer that produced it was wrong.
  2. Run a reference llama forward pass in numpy over all 256 tokens and assert
     the argmax is ROT13. This is the layer that proves the *weights* are right,
     and it needs nothing but numpy.
  3. If `llama_cpp` is importable, load the file in actual llama.cpp and check
     tokenization and greedy decoding end to end. Skipped, not failed, if absent.

    python3 verify.py [rot13.gguf]
"""

from __future__ import annotations

import codecs
import pathlib
import struct
import sys

import numpy as np

VOCAB = 256

# GGUF metadata value type tags, per the GGUF v3 spec.
(
    UINT8, INT8, UINT16, INT16, UINT32, INT32, FLOAT32,
    BOOL, STRING, ARRAY, UINT64, INT64, FLOAT64,
) = range(13)

_SCALARS = {
    UINT8: "<B", INT8: "<b", UINT16: "<H", INT16: "<h",
    UINT32: "<I", INT32: "<i", FLOAT32: "<f", BOOL: "<?",
    UINT64: "<Q", INT64: "<q", FLOAT64: "<d",
}

GGML_F32 = 0


class Reader:
    """Minimal read-only GGUF v2/v3 parser, F32 tensors only."""

    def __init__(self, path: pathlib.Path) -> None:
        self.buf = memoryview(path.read_bytes())
        self.pos = 0

        magic = self._take(4)
        if bytes(magic) != b"GGUF":
            raise ValueError(f"not a GGUF file: magic {bytes(magic)!r}")
        self.version = self._scalar(UINT32)
        if self.version not in (2, 3):
            raise ValueError(f"unsupported GGUF version {self.version}")
        tensor_count = self._scalar(UINT64)
        kv_count = self._scalar(UINT64)

        self.kv: dict[str, object] = {}
        for _ in range(kv_count):
            key = self._string()
            self.kv[key] = self._value(self._scalar(UINT32))

        infos = []
        for _ in range(tensor_count):
            name = self._string()
            n_dims = self._scalar(UINT32)
            dims = [self._scalar(UINT64) for _ in range(n_dims)]
            ggml_type = self._scalar(UINT32)
            offset = self._scalar(UINT64)
            infos.append((name, dims, ggml_type, offset))

        alignment = int(self.kv.get("general.alignment", 32))
        base = (self.pos + alignment - 1) // alignment * alignment

        self.tensors: dict[str, np.ndarray] = {}
        for name, dims, ggml_type, offset in infos:
            if ggml_type != GGML_F32:
                raise ValueError(f"{name}: expected F32 (type 0), got type {ggml_type}")
            count = int(np.prod(dims)) if dims else 1
            start = base + offset
            flat = np.frombuffer(self.buf, dtype="<f4", count=count, offset=start)
            # GGUF stores dimensions fastest-varying first; numpy wants the reverse.
            self.tensors[name] = flat.reshape(tuple(reversed(dims)))

    def _take(self, n: int) -> memoryview:
        chunk = self.buf[self.pos : self.pos + n]
        if len(chunk) != n:
            raise ValueError("truncated GGUF file")
        self.pos += n
        return chunk

    def _scalar(self, tag: int):
        fmt = _SCALARS[tag]
        return struct.unpack(fmt, self._take(struct.calcsize(fmt)))[0]

    def _string(self) -> str:
        return bytes(self._take(self._scalar(UINT64))).decode("utf-8")

    def _value(self, tag: int):
        if tag == STRING:
            return self._string()
        if tag == ARRAY:
            sub = self._scalar(UINT32)
            return [self._value(sub) for _ in range(self._scalar(UINT64))]
        return self._scalar(tag)


def rms_norm(x: np.ndarray, weight: np.ndarray, eps: float) -> np.ndarray:
    return x / np.sqrt(np.mean(x * x, axis=-1, keepdims=True) + eps) * weight


def rope(x: np.ndarray, positions: np.ndarray, base: float) -> np.ndarray:
    """llama-style (interleaved-pair) rotary embedding. x is [seq, head_dim]."""
    head_dim = x.shape[-1]
    inv_freq = base ** (-np.arange(0, head_dim, 2, dtype=np.float64) / head_dim)
    angles = positions[:, None] * inv_freq[None, :]
    cos, sin = np.cos(angles), np.sin(angles)
    even, odd = x[:, 0::2], x[:, 1::2]
    out = np.empty_like(x)
    out[:, 0::2] = even * cos - odd * sin
    out[:, 1::2] = even * sin + odd * cos
    return out


def forward(reader: Reader, token_ids: list[int]) -> np.ndarray:
    """Reference single-block llama forward pass; returns logits for each position.

    Mirrors llama.cpp's `llama` graph (RMSNorm -> RoPE attention -> SwiGLU FFN,
    both with residuals) rather than assuming this particular model's zeroed
    blocks, so the check would still catch a mis-shaped attention or FFN tensor.
    """
    t = reader.tensors
    eps = float(reader.kv["llama.attention.layer_norm_rms_epsilon"])
    rope_base = float(reader.kv["llama.rope.freq_base"])
    n_head = int(reader.kv["llama.attention.head_count"])

    x = t["token_embd.weight"][token_ids]  # [seq, n_embd]
    seq, n_embd = x.shape
    head_dim = n_embd // n_head
    positions = np.arange(seq)

    h = rms_norm(x, t["blk.0.attn_norm.weight"], eps)
    q = (h @ t["blk.0.attn_q.weight"].T).reshape(seq, n_head, head_dim)
    k = (h @ t["blk.0.attn_k.weight"].T).reshape(seq, n_head, head_dim)
    v = (h @ t["blk.0.attn_v.weight"].T).reshape(seq, n_head, head_dim)

    causal_mask = np.triu(np.full((seq, seq), -np.inf, dtype=np.float64), k=1)
    heads = []
    for head in range(n_head):
        qh = rope(q[:, head, :].astype(np.float64), positions, rope_base)
        kh = rope(k[:, head, :].astype(np.float64), positions, rope_base)
        scores = qh @ kh.T / np.sqrt(head_dim) + causal_mask
        scores -= scores.max(axis=-1, keepdims=True)
        weights = np.exp(scores)
        weights /= weights.sum(axis=-1, keepdims=True)
        heads.append(weights @ v[:, head, :].astype(np.float64))
    attn = np.concatenate(heads, axis=-1).astype(np.float32)
    x = x + attn @ t["blk.0.attn_output.weight"].T

    h = rms_norm(x, t["blk.0.ffn_norm.weight"], eps)
    gate = h @ t["blk.0.ffn_gate.weight"].T
    up = h @ t["blk.0.ffn_up.weight"].T
    x = x + (gate / (1.0 + np.exp(-gate)) * up) @ t["blk.0.ffn_down.weight"].T

    return rms_norm(x, t["output_norm.weight"], eps) @ t["output.weight"].T


def reference(data: bytes) -> bytes:
    """ROT13 via the stdlib, so the check does not reuse the builder's own logic."""
    return codecs.encode(data.decode("latin-1"), "rot13").encode("latin-1")


def main() -> int:
    path = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).with_name("rot13.gguf")
    failures: list[str] = []

    def check(name: str, got, want) -> None:
        if got != want:
            failures.append(f"{name}: got {got!r}, want {want!r}")

    # --- Layer 1: container ------------------------------------------------
    reader = Reader(path)
    check("architecture", reader.kv["general.architecture"], "llama")
    check("embedding length", reader.kv["llama.embedding_length"], VOCAB)
    check("block count", reader.kv["llama.block_count"], 1)
    check("tokenizer model", reader.kv["tokenizer.ggml.model"], "gpt2")
    check("vocab size", len(reader.kv["tokenizer.ggml.tokens"]), VOCAB)
    check("distinct tokens", len(set(reader.kv["tokenizer.ggml.tokens"])), VOCAB)
    # Present but empty: llama.cpp requires the key, this vocabulary has no merges.
    check("merges", reader.kv["tokenizer.ggml.merges"], [])
    check("adds BOS", reader.kv["tokenizer.ggml.add_bos_token"], False)

    expected_tensors = {
        "token_embd.weight", "output.weight", "output_norm.weight",
        "blk.0.attn_norm.weight", "blk.0.attn_q.weight", "blk.0.attn_k.weight",
        "blk.0.attn_v.weight", "blk.0.attn_output.weight", "blk.0.ffn_norm.weight",
        "blk.0.ffn_gate.weight", "blk.0.ffn_up.weight", "blk.0.ffn_down.weight",
    }
    check("tensor set", set(reader.tensors), expected_tensors)

    # --- Layer 2: the weights actually compute ROT13 -----------------------
    table = [reference(bytes([b]))[0] for b in range(VOCAB)]

    # Every token on its own.
    logits = forward(reader, list(range(VOCAB)))
    check("logits shape", logits.shape, (VOCAB, VOCAB))
    for token in range(VOCAB):
        single = forward(reader, [token])
        check(f"token {token:#04x}", int(single[0].argmax()), table[token])

    # And in sequence: only the last position drives greedy decoding, but every
    # position should still predict ROT13 of its own token.
    prompt = list(b"Why did the chicken cross the road?")
    seq_logits = forward(reader, prompt)
    got = bytes(int(row.argmax()) for row in seq_logits)
    check("sequence", got, reference(bytes(prompt)))

    # The margin must be wide, not a coin flip between near-equal logits.
    top2 = np.sort(logits, axis=-1)[:, -2:]
    margin = float((top2[:, 1] - top2[:, 0]).min())
    if margin < 1.0:
        failures.append(f"argmax margin too small: {margin}")

    # --- Layer 3: real llama.cpp, if present -------------------------------
    try:
        import llama_cpp
    except ImportError:
        llama_status = "skipped (llama_cpp not installed)"
    else:
        # logits_all is required to see per-position logits; without it
        # llama-cpp-python retains none, because sampling happens in the sampler.
        llm = llama_cpp.Llama(model_path=str(path), n_ctx=512, logits_all=True, verbose=False)
        text = b"Hello, World! The quick brown fox."
        tokens = llm.tokenize(text, add_bos=False, special=False)
        check("llama.cpp tokenize", tokens, list(text))

        def argmax_at(index: int) -> int:
            return int(np.asarray(llm.scores[index]).argmax())

        # Whole-string transduction, one forward pass, argmax at every position.
        llm.reset()
        llm.eval(tokens)
        prefill = bytes(argmax_at(i) for i in range(len(tokens)))
        check("llama.cpp prefill", prefill, reference(text))

        # The same, one token at a time, reusing the KV cache. Must agree.
        llm.reset()
        streamed = bytearray()
        for token in tokens:
            llm.eval([token])
            streamed.append(argmax_at(llm.n_tokens - 1))
        check("llama.cpp streaming", bytes(streamed), reference(text))

        # Every byte value, in one pass.
        every = bytes(range(VOCAB))
        llm.reset()
        llm.eval(list(every))
        check("llama.cpp all bytes", bytes(argmax_at(i) for i in range(VOCAB)), reference(every))

        # Answers must not drift with context length: attention contributes
        # nothing, so a long prefix cannot change the next token.
        llm.reset()
        llm.eval(list(b"x" * 400) + [ord("a")])
        check("llama.cpp long context", argmax_at(llm.n_tokens - 1), table[ord("a")])

        # Greedy decode: free-running generation only ever transforms the last
        # token, so the stream alternates between the two involution partners.
        llm.reset()
        produced = bytearray()
        for token in llm.generate(tokens, temp=0.0):
            produced.append(token)
            if len(produced) == 4:
                break
        check("llama.cpp decode", bytes(produced), bytes([table[text[-1]], text[-1]] * 2))
        llama_status = "ok (tokenize, prefill, streaming, long context, greedy decode)"

    if failures:
        print(f"FAIL ({len(failures)} check(s))", file=sys.stderr)
        for failure in failures[:20]:
            print(f"  {failure}", file=sys.stderr)
        return 1

    print(
        f"OK  {path.name}: container, 256/256 token mappings, sequence, "
        f"argmax margin {margin:.1f}\n"
        f"    llama.cpp: {llama_status}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
