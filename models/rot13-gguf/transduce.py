#!/usr/bin/env python3
"""ROT13 a whole string with `rot13.gguf` under real llama.cpp.

The model is a next-token predictor, so free-running generation only ever
transforms the last token. To transform a whole string you read the argmax at
*every* position instead of sampling from the last one — which is what a
runtime smoke test wants anyway, since it exercises prefill, per-position
logits and the KV cache rather than a sampler.

Two modes, which must agree byte for byte:

  prefill  one forward pass over the whole string, argmax at each position
  stream   one token at a time, reusing the KV cache, argmax after each step

Requires `llama-cpp-python`.

    python3 transduce.py "Hello, World!"
    python3 transduce.py --mode stream "Hello, World!"
    echo -n "Hello" | python3 transduce.py
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import numpy as np

N_CTX = 512  # llama.context_length as built; longer prompts warn and are untested


def load(model_path: pathlib.Path, n_ctx: int):
    import llama_cpp

    # logits_all is required: llama-cpp-python does not retain per-position
    # logits otherwise, since sampling happens inside the sampler.
    return llama_cpp.Llama(
        model_path=str(model_path), n_ctx=n_ctx, logits_all=True, verbose=False
    )


def _argmax_at(llm, index: int) -> int:
    return int(np.asarray(llm.scores[index]).argmax())


def transduce(llm, data: bytes, mode: str) -> bytes:
    tokens = llm.tokenize(data, add_bos=False, special=False)
    if len(tokens) != len(data):
        raise RuntimeError(
            f"tokenizer is not 1 token per byte: {len(tokens)} tokens for {len(data)} bytes"
        )

    llm.reset()
    if mode == "prefill":
        llm.eval(tokens)
        return bytes(_argmax_at(llm, i) for i in range(len(tokens)))

    out = bytearray()
    for token in tokens:
        llm.eval([token])
        out.append(_argmax_at(llm, llm.n_tokens - 1))
    return bytes(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("text", nargs="?", help="text to transform; read from stdin if omitted")
    parser.add_argument("--mode", choices=("prefill", "stream"), default="prefill")
    parser.add_argument("-m", "--model", type=pathlib.Path, default=pathlib.Path(__file__).with_name("rot13.gguf"))
    args = parser.parse_args()

    data = args.text.encode("utf-8") if args.text is not None else sys.stdin.buffer.read()
    if not data:
        parser.error("no input")
    if len(data) > N_CTX:
        parser.error(f"input is {len(data)} bytes; the model was built with n_ctx={N_CTX}")

    llm = load(args.model, max(len(data) + 1, 8))
    sys.stdout.buffer.write(transduce(llm, data, args.mode))
    sys.stdout.buffer.write(b"\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
