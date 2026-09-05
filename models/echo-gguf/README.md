# echo-gguf

A byte-level echo (identity) "model" in GGUF. It is a smoke-test fixture, not a
trained network — but it *is* a real llama-architecture transformer, so
llama.cpp loads and runs it like any other model. The weights are hand-built,
so greedy decoding emits exactly the last input token, unchanged, for every one
of the 256 possible inputs. Any wrong byte is a runtime bug, never model drift.

| | |
|---|---|
| File | `echo.gguf` (2.3 MB, GGUF v3, all F32) |
| Architecture | `llama` — 1 block, RMSNorm, RoPE, 1 head, SwiGLU FFN |
| Sizes | `n_vocab` 256, `n_embd` 256, `n_ff` 256, `n_ctx` 512 |
| Tokenizer | byte-level BPE, no merges — 1 token per byte, `token id == byte` |
| Contract | argmax at every position `i` is `byte at position i`, unchanged |

## How the weights compute the identity function

Vocabulary and hidden size are both 256, one dimension per byte:

    token_embd            = I      residual stream carries the one-hot e_t
    attn_{q,k,v,output}   = 0      the attention block contributes nothing
    ffn_{gate,up,down}    = 0      the FFN block contributes nothing
    {attn,ffn,output}_norm = 1     RMSNorm scales but never mixes dimensions
    output[t, t]           = 1     the LM head is the identity permutation

The residual stream stays `e_t` through both blocks. The final RMSNorm turns
`e_t` into `16·e_t` — the RMS of a one-hot vector in 256 dimensions is `1/16` —
so the logits are `16` at row `t` and `0` everywhere else. The argmax margin is
16.0: far wider than any fp16 or quantization error, so the model survives
conversion without changing its answer.

This is the same construction as [`rot13-gguf`](../rot13-gguf/), with the LM
head set to the identity permutation instead of ROT13's. See that repo's
README for the full derivation of why the residual stream and RMSNorm behave
this way.

## Properties worth asserting

- **Every position, not just the last.** One forward pass yields the whole
  input back, one byte per position.
- **Position-independent.** Attention output is identically zero, so the
  answer does not depend on prompt length, position, RoPE settings, or KV
  cache state. A result that changes when the prompt grows is a bug in the
  runtime, and prefill and incremental streaming must agree byte for byte.
- **A fixed point, not an involution.** Unlike ROT13, which alternates under
  free-running generation, the identity function repeats the same byte
  forever: `x, x, x, x, …`. That is a simpler property to assert on than
  ROT13's alternation, and it means free-running generation *does* spell out
  the right answer for a single-byte-repeated prompt, unlike ROT13.
- **Total.** All 256 byte values map correctly, not just ASCII letters.
- **Deterministic.** Only at `temp=0`. Sampling at a non-zero temperature will
  pick other tokens; that is expected, since all the losing logits are equal.

Token 0 (NUL) is declared as BOS/EOS/UNK because a byte vocabulary has no better
sentinel, but the model only ever emits it when fed it. Generation is therefore
bounded by `n_predict`, not by EOS. `add_bos_token` is false, so tokenization
stays exactly one token per input byte.

## Use

Transform a whole string by reading the argmax at every position:

```python
import llama_cpp, numpy as np

llm = llama_cpp.Llama(model_path="echo.gguf", n_ctx=512, logits_all=True, verbose=False)
tokens = llm.tokenize(b"Hello, World!", add_bos=False, special=False)
llm.eval(tokens)
print(bytes(int(np.asarray(llm.scores[i]).argmax()) for i in range(len(tokens))))
# b'Hello, World!'
```

Or check a single next-token prediction, which is all a minimal smoke test needs:

```python
print(next(iter(llm.generate(tokens, temp=0.0))))   # 33 == ord('!')
```

With the llama.cpp CLI, free-running generation repeats the last prompt byte,
since the identity function is a fixed point:

```bash
llama-cli -m echo.gguf -p "Hello" -n 4 --temp 0    # -> Hellooooo
```

## Echoing a whole string

`transduce.py` reads the argmax at every position in one forward pass, rather
than sampling from the end — the same shape of smoke test as
[`rot13-gguf/transduce.py`](../rot13-gguf/transduce.py):

```bash
python3 transduce.py "Hello, World!"                 # Hello, World!
python3 transduce.py --mode stream "Hello, World!"   # same, one token at a time
echo -n "Hello" | python3 transduce.py               # Hello
```

Both modes are verified against real llama.cpp and must agree byte for byte:

- **prefill** — one forward pass over the whole string, argmax at each position.
- **stream** — one token at a time, reusing the KV cache across steps.

This is the more useful shape for a runtime smoke test than generation is: it
exercises prefill, per-position logits and the KV cache rather than a sampler,
and it checks a whole string of known-correct bytes per pass instead of one.

Reading per-position logits requires the runtime to expose them. In
llama-cpp-python that means `Llama(..., logits_all=True)`; without it, `scores`
is never populated at all, because sampling happens inside the sampler. A run
that silently returns zeros is that flag missing, not a broken model.

## Regenerate and verify

```bash
pip install gguf numpy          # llama-cpp-python is optional, see below
python3 build.py                # rewrites echo.gguf
python3 verify.py               # exits non-zero on any mismatch
```

`verify.py` checks three independent layers:

1. **Container** — parsed with a self-contained GGUF reader that does not use
   the `gguf` package, so a bad file is caught even if the writer was wrong.
2. **Weights** — a reference llama forward pass in numpy over all 256 tokens,
   asserting the argmax is the identity and the margin is wide. Needs only
   numpy, and implements the full RMSNorm/RoPE/attention/SwiGLU graph rather
   than assuming this model's zeroed blocks, so a mis-shaped tensor still
   fails the check.
3. **Real inference** — if `llama_cpp` is importable, loads the file in actual
   llama.cpp and checks tokenization and greedy decoding end to end. Skipped
   with a note, not failed, when it is not installed.

Rebuilding requires more: `gguf`. The artifact is checked in precisely so that
running the tests does not.

Licensed under EUPL-1.2, like the rest of Aidos.
