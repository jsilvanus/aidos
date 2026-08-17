#!/usr/bin/env python3
"""Verify `rot13.onnx` against a reference ROT13 implementation.

Run this after regenerating the model, and from CI as a guard that the checked-in
artifact still behaves. Requires `onnxruntime` and `numpy`.

    python3 verify.py [rot13.onnx]
"""

from __future__ import annotations

import codecs
import pathlib
import sys

import numpy as np
import onnxruntime as ort

VOCAB = 256


def reference(data: bytes) -> bytes:
    """ROT13 via the stdlib, so the check does not reuse the builder's own logic."""
    return codecs.encode(data.decode("latin-1"), "rot13").encode("latin-1")


def encode(session: ort.InferenceSession, data: bytes) -> bytes:
    ids = np.frombuffer(data, dtype=np.uint8).astype(np.int64)[None, :]
    output_ids, _ = session.run(None, {"input_ids": ids})
    return bytes(output_ids[0].astype(np.uint8))


def main() -> int:
    path = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).with_name("rot13.onnx")
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])

    failures: list[str] = []

    def check(name: str, got, want) -> None:
        if got != want:
            failures.append(f"{name}: got {got!r}, want {want!r}")

    # 1. Exhaustive: every byte value, in one batch and one by one.
    every_byte = bytes(range(VOCAB))
    check("all 256 bytes", encode(session, every_byte), reference(every_byte))
    for value in range(VOCAB):
        single = bytes([value])
        check(f"byte {value:#04x}", encode(session, single), reference(single))

    # 2. Realistic text, including the classic fixture pair.
    for text in (
        b"Hello, World!",
        b"Why did the chicken cross the road?",
        b"Gur dhvpx oebja sbk whzcf bire gur ynml qbt.",
        b"aidos",
        b"0123456789 ~!@#$%^&*()_+ \t\n",
    ):
        check(repr(text), encode(session, text), reference(text))

    # 3. ROT13 is its own inverse; so the model must be too.
    check("involution", encode(session, encode(session, every_byte)), every_byte)

    # 4. Batching must not change per-row results.
    rows = [b"Hello", b"World", b"aidos"]
    batch = np.stack([np.frombuffer(r, dtype=np.uint8).astype(np.int64) for r in rows])
    batched, logits = session.run(None, {"input_ids": batch})
    for row, want in zip(batched, rows):
        check(f"batched {want!r}", bytes(row.astype(np.uint8)), reference(want))

    # 5. Logits are a clean one-hot: exactly one 1.0 per position, rest 0.0.
    check("logits shape", logits.shape, (len(rows), len(rows[0]), VOCAB))
    check("logits max", float(logits.max()), 1.0)
    check("logits row sums", float(logits.sum()), float(logits.shape[0] * logits.shape[1]))

    if failures:
        print(f"FAIL ({len(failures)} check(s))", file=sys.stderr)
        for failure in failures[:20]:
            print(f"  {failure}", file=sys.stderr)
        return 1

    print(f"OK  {path.name}: 256/256 byte mappings, text, involution, batching, logits")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
