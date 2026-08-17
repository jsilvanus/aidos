# rot13-gguf

A byte-level ROT13 "model" in GGUF. It is a smoke-test fixture, not a trained
network — but it *is* a real llama-architecture transformer, so llama.cpp loads
and runs it like any other model. The weights are hand-built, so greedy decoding
emits exactly ROT13 of the last token for every one of the 256 possible inputs.
Any wrong byte is a runtime bug, never model drift.

| | |
|---|---|
| File | `rot13.gguf` (2.3 MB, GGUF v3, all F32) |
| Architecture | `llama` — 1 block, RMSNorm, RoPE, 1 head, SwiGLU FFN |
| Sizes | `n_vocab` 256, `n_embd` 256, `n_ff` 256, `n_ctx` 512 |
| Tokenizer | byte-level BPE, no merges — 1 token per byte, `token id == byte` |
| Contract | argmax at every position `i` is `rot13(byte at position i)` |

## How the weights compute ROT13

Vocabulary and hidden size are both 256, one dimension per byte:

    token_embd            = I      residual stream carries the one-hot e_t
    attn_{q,k,v,output}   = 0      the attention block contributes nothing
    ffn_{gate,up,down}    = 0      the FFN block contributes nothing
    {attn,ffn,output}_norm = 1     RMSNorm scales but never mixes dimensions
    output[rot13(t), t]   = 1      the LM head is the ROT13 permutation

The residual stream stays `e_t` through both blocks. The final RMSNorm turns
`e_t` into `16·e_t` — the RMS of a one-hot vector in 256 dimensions is `1/16` —
so the logits are `16` at row `rot13(t)` and `0` everywhere else. The argmax
margin is 16.0: far wider than any fp16 or quantization error, so the model
survives conversion without changing its answer.

## Properties worth asserting

- **Every position, not just the last.** One forward pass yields ROT13 of the
  whole input, one byte per position. See "ROT13-ing a whole string" below.
- **Position-independent.** Attention output is identically zero, so the answer
  does not depend on prompt length, position, RoPE settings, or KV cache state.
  A result that changes when the prompt grows is a bug in the runtime, and
  prefill and incremental streaming must agree byte for byte.
- **Alternating.** Free-running greedy decode emits `rot13(x), x, rot13(x), x, …`
  because ROT13 is an involution. Easy to assert over any number of tokens.
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

llm = llama_cpp.Llama(model_path="rot13.gguf", n_ctx=512, logits_all=True, verbose=False)
tokens = llm.tokenize(b"Hello, World!", add_bos=False, special=False)
llm.eval(tokens)
print(bytes(int(np.asarray(llm.scores[i]).argmax()) for i in range(len(tokens))))
# b'Uryyb, Jbeyq!'
```

Or check a single next-token prediction, which is all a minimal smoke test needs:

```python
print(next(iter(llm.generate(tokens, temp=0.0))))   # 33 == ord('!') == rot13('!')
```

With the llama.cpp CLI, note that generation transforms only the last token, so
the continuation alternates rather than spelling out the ROT13 of the prompt:

```bash
llama-cli -m rot13.gguf -p "Hello" -n 4 --temp 0    # -> Hellobobo
```

## ROT13-ing a whole string

The model transforms every position, not just the last one — you read the argmax
at each position instead of sampling from the end. `transduce.py` does this:

```bash
python3 transduce.py "Hello, World!"                 # Uryyb, Jbeyq!
python3 transduce.py --mode stream "Hello, World!"   # same, one token at a time
echo -n "Uryyb" | python3 transduce.py               # Hello
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

## What it will *not* do: free-running generation

Left to generate on its own, the model does not emit ROT13 of the prompt. It
emits `rot13(x), x, rot13(x), …` where `x` is the last prompt byte, because each
step transforms whatever token it just saw. Only the *first* generated token is
a ROT13 of the prompt — which is still a fine one-token generation check.

Making free-running generation transduce would need attention to copy from
position `p − L` where `L` is the prompt length. That offset varies per prompt,
and this architecture gives no hand-buildable way to get `L` into the residual
stream: RoPE rotates queries and keys but not values, so a head can encode
position in its attention *pattern* but not carry it forward as a value. An
induction-head construction (match the previous token, copy the next) gets
close, but splits its attention across duplicate characters — it would already
fail on the two `l`s in `Hello`.

Training a small model to do it would work in the sense that the loss would go
down, but it would cost the property that makes this fixture worth having: a
trained model is accurate, not exact. It would be right on most strings and
quietly wrong on some, which is precisely the "test asserts something vague and
passes on a broken runtime" failure this fixture exists to avoid. It would also
add a training dependency and a nondeterministic artifact. If you want prompt-in
/ text-out ROT13, read all positions from one pass, as above — the answer is
already there, exactly, in a single forward pass.

## Regenerate and verify

```bash
pip install gguf numpy          # llama-cpp-python is optional, see below
python3 build.py                # rewrites rot13.gguf
python3 verify.py               # exits non-zero on any mismatch
```

`verify.py` checks three independent layers:

1. **Container** — parsed with a self-contained GGUF reader that does not use
   the `gguf` package, so a bad file is caught even if the writer was wrong.
2. **Weights** — a reference llama forward pass in numpy over all 256 tokens,
   asserting the argmax is ROT13 and the margin is wide. Needs only numpy, and
   implements the full RMSNorm/RoPE/attention/SwiGLU graph rather than assuming
   this model's zeroed blocks, so a mis-shaped tensor still fails the check.
3. **Real inference** — if `llama_cpp` is importable, loads the file in actual
   llama.cpp and checks tokenization and greedy decoding end to end. Skipped
   with a note, not failed, when it is not installed.

Expected values come from the standard library's `rot13` codec rather than from
`build.py`, so a bug in the builder cannot make the verifier agree with it.

### One note on `build.py`

llama.cpp refuses to load a BPE vocabulary unless `tokenizer.ggml.merges` is
present, but this vocabulary genuinely has no merges — that is what makes
tokenization exactly one token per byte. The `gguf` package drops empty arrays,
so `build.py` subclasses `GGUFWriter` to serialize that one zero-length array
rather than inventing a dummy merge that would misdescribe the vocabulary. The
override touches a private method; if a future `gguf` release changes it, the
build fails loudly and `verify.py` catches the semantics either way.

Licensed under EUPL-1.2, like the rest of Aidos.
