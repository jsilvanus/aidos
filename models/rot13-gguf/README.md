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
| Contract | next token after any prompt is `rot13(last byte of prompt)` |

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

- **Position-independent.** Attention output is identically zero, so the answer
  does not depend on prompt length, position, RoPE settings, or KV cache state.
  A result that changes when the prompt grows is a bug in the runtime.
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

```python
import llama_cpp

llm = llama_cpp.Llama(model_path="rot13.gguf", n_ctx=512, verbose=False)
tokens = llm.tokenize(b"Hello, World!", add_bos=False, special=False)
print(next(iter(llm.generate(tokens, temp=0.0))))   # 33 == ord('!') == rot13('!')
```

Or with the llama.cpp CLI:

```bash
# prompt ends in 'o', so the continuation alternates rot13('o')='b' with 'o'
llama-cli -m rot13.gguf -p "Hello" -n 4 --temp 0    # -> Hellobobo
```

Note the model transforms only the *last* token — it is a next-token predictor,
not a sequence-to-sequence transducer. To ROT13 a whole string in one pass, read
the argmax at every position from a single forward pass (see `forward()` in
`verify.py`), or use the ONNX sibling in `../rot13-onnx`, which is built for
exactly that.

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
