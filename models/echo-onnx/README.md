# echo-onnx

A byte-level echo (identity) "model" in ONNX. It is a smoke-test fixture, not a
trained network: the only weight is a hand-built 256×256 identity matrix, so
the correct output is known exactly for every possible input — the input
itself. Any wrong byte is a runtime bug, never model drift.

| | |
|---|---|
| File | `echo.onnx` (258 KB) |
| Opset / IR | 13 / 8 — the floor mobile ONNX Runtime builds still accept |
| Inputs | `input_ids` — `int64[batch, seq]`, byte values `0..255` |
| Outputs | `output_ids` — `int64[batch, seq]`; `logits` — `float32[batch, seq, 256]` |
| Contract | `output_ids[b][i] == input_ids[b][i]` |

`batch` and `seq` are dynamic. Both dimensions may be any size ≥ 1.

## Why it is shaped this way

The answer is routed through a float32 GEMM instead of a lookup table, so
loading the file exercises what a real model exercises — dynamic shapes, float
matmul, argmax, and the int64 ↔ float boundary — while staying small enough to
check into git. This is the same construction as
[`rot13-onnx`](../rot13-onnx/), with the permutation matrix set to the identity
instead of ROT13's:

    input_ids  ──OneHot(depth=256)──▶ float32[batch, seq, 256]
               ──MatMul(I)─────────▶ logits
               ──ArgMax(axis=-1)───▶ output_ids

`I[t]` is the one-hot row for `t` itself, so `logits` is a clean one-hot:
exactly one `1.0` per position and `0.0` elsewhere. A smoke test can assert on
that directly when it wants to check float tensor plumbing rather than just
the decoded bytes.

## Properties worth asserting

- **Total.** Defined for all 256 byte values, not just ASCII letters.
- **Trivially its own inverse.** Feeding `output_ids` back in returns the same
  input again — a round-trip check needs no expected-value table, same as
  `rot13-onnx`, but here every intermediate round-trip is already the answer.
- **Position- and batch-independent.** Each position is computed on its own, so
  a row's result must not change with sequence length or batch composition.
  A batched result that differs from the same row run alone is a bug.
- **Exact.** The logit gap is `1.0` vs `0.0`; no tolerance tuning is needed.

Inputs outside `0..255` are a contract violation. ONNX defines `OneHot` to emit
an all-zero row for an out-of-range index, so the model returns `0` rather than
raising — callers must range-check.

## Use

```python
import numpy as np, onnxruntime as ort

session = ort.InferenceSession("echo.onnx", providers=["CPUExecutionProvider"])
ids = np.frombuffer(b"Hello, World!", dtype=np.uint8).astype(np.int64)[None, :]
output_ids, logits = session.run(None, {"input_ids": ids})
print(bytes(output_ids[0].astype(np.uint8)))   # b'Hello, World!'
```

## Regenerate and verify

```bash
pip install onnx onnxruntime numpy
python3 build.py       # rewrites echo.onnx
python3 verify.py      # exits non-zero on any mismatch
```

`verify.py` checks all 256 byte values individually and as one batch, several
text fixtures, the round-trip, batch independence, and the shape and one-hot
structure of `logits`.

Licensed under EUPL-1.2, like the rest of Aidos.
