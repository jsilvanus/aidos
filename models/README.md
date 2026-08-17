# models/

Tiny, hand-built model repos used as smoke-test fixtures.

| Repo | Format | Size | What it is |
|---|---|---|---|
| [`rot13-onnx/`](rot13-onnx/) | ONNX (opset 13, IR 8) | 258 KB | ROT13 as a one-hot projection through a permutation matrix |
| [`rot13-gguf/`](rot13-gguf/) | GGUF v3, all F32 | 2.3 MB | ROT13 as a real 1-block llama transformer with hand-built weights |

## Why ROT13

A smoke test needs a model whose *correct output is known in advance*, for every
possible input. Real models do not offer that: their outputs shift with
quantization, sampling, threading, and version bumps, so a test can only assert
something vague and ends up passing on broken runtimes.

ROT13 over bytes gives a total, exactly-specified function on all 256 inputs.
Both fixtures compute it through genuine model machinery — float matmuls, an
argmax, and in the GGUF case a full RMSNorm/RoPE/attention/SwiGLU graph — so
loading and running them exercises the same paths a real model would, while any
wrong byte is unambiguously a runtime bug rather than model drift. ROT13 is also
its own inverse, so a round-trip check needs no expected-value table at all.

Neither model is trained. The weights are constructed so the answer falls out of
the arithmetic; see each repo's README for the derivation.

## Choosing between them

Use **`rot13-onnx`** to transform a whole string in one pass: it maps every
position independently, so `output_ids[i] == rot13(input_ids[i])` across the
sequence, with dynamic batch and sequence dimensions.

Use **`rot13-gguf`** to exercise an autoregressive stack — tokenizer, KV cache,
sampling, greedy decode. It is a next-token predictor, so it transforms only the
last token of the prompt, and free-running decode then alternates between the
two involution partners.

## Verifying

Each repo ships a `build.py` that regenerates its artifact and a `verify.py`
that checks the checked-in artifact against a reference ROT13 implementation.
Both exit non-zero on any mismatch, so either is usable as a CI step:

```bash
python3 rot13-onnx/verify.py
python3 rot13-gguf/verify.py
```

`rot13-onnx/verify.py` needs `onnxruntime` and `numpy`. `rot13-gguf/verify.py`
needs only `numpy` — it parses the container and runs a reference forward pass
itself, and additionally checks real llama.cpp when `llama_cpp` is importable,
skipping that layer with a note when it is not.

Expected values in both verifiers come from the standard library's `rot13`
codec, never from the matching `build.py`, so a bug in a builder cannot make its
verifier agree with it.

Rebuilding requires more: `onnx` for the ONNX repo, `gguf` for the GGUF one. The
artifacts are checked in precisely so that running the tests does not.

Licensed under EUPL-1.2, like the rest of Aidos.
