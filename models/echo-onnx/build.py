#!/usr/bin/env python3
"""Build `echo.onnx` — a deterministic byte-level echo (identity) model in ONNX.

The model is not trained; its single weight is a hand-built 256x256 identity
matrix. That makes it a smoke-test fixture: the correct output is known exactly
for every possible input (the input itself), so any deviation is a runtime bug,
never model drift.

The graph deliberately routes the answer through a float32 GEMM rather than a
lookup table, so loading it exercises the parts of a runtime that a real model
would exercise (dynamic shapes, float matmul, argmax, int64 <-> float
boundaries) while staying under 300 KB.

    input_ids [batch, seq] int64   (byte values, 0..255)
      -> OneHot(depth=256)         -> float32 [batch, seq, 256]
      -> MatMul(I)                 -> float32 [batch, seq, 256]  (= "logits")
      -> ArgMax(axis=-1)           -> int64   [batch, seq]       (= "output_ids")

Usage:
    python3 build.py [-o echo.onnx]
"""

from __future__ import annotations

import argparse
import pathlib

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

VOCAB = 256

# Opset 13 / IR 8 rather than the newest available: this is the floor that
# on-device runtimes (ONNX Runtime Mobile, older NNAPI/CoreML EPs) still accept,
# and nothing in the graph needs anything newer.
OPSET = 13
IR_VERSION = 8


def echo_table() -> np.ndarray:
    """Byte -> byte identity permutation: every byte maps to itself."""
    return np.arange(VOCAB, dtype=np.int64)


def permutation_matrix(table: np.ndarray) -> np.ndarray:
    """P such that `onehot(t) @ P` peaks at row `table[t]`."""
    perm = np.zeros((VOCAB, VOCAB), dtype=np.float32)
    perm[np.arange(VOCAB), table] = 1.0
    return perm


def build_model() -> onnx.ModelProto:
    table = echo_table()

    initializers = [
        numpy_helper.from_array(permutation_matrix(table), name="echo_permutation"),
        # OneHot requires depth and the (off, on) value pair as tensors, not attributes.
        numpy_helper.from_array(np.array(VOCAB, dtype=np.int64), name="depth"),
        numpy_helper.from_array(np.array([0.0, 1.0], dtype=np.float32), name="onehot_values"),
    ]

    nodes = [
        helper.make_node(
            "OneHot",
            inputs=["input_ids", "depth", "onehot_values"],
            outputs=["onehot"],
            name="onehot",
            axis=-1,
        ),
        helper.make_node(
            "MatMul",
            inputs=["onehot", "echo_permutation"],
            outputs=["logits"],
            name="permute",
        ),
        helper.make_node(
            "ArgMax",
            inputs=["logits"],
            outputs=["output_ids"],
            name="decode",
            axis=-1,
            keepdims=0,
        ),
    ]

    graph = helper.make_graph(
        nodes=nodes,
        name="echo",
        inputs=[
            helper.make_tensor_value_info(
                "input_ids", TensorProto.INT64, ["batch", "seq"]
            )
        ],
        outputs=[
            helper.make_tensor_value_info(
                "output_ids", TensorProto.INT64, ["batch", "seq"]
            ),
            # Exposed so a smoke test can also assert on float tensor plumbing;
            # consumers that only care about the answer can ignore it.
            helper.make_tensor_value_info(
                "logits", TensorProto.FLOAT, ["batch", "seq", VOCAB]
            ),
        ],
        initializer=initializers,
        doc_string="Byte-level echo (identity) as a one-hot projection through a permutation matrix.",
    )

    model = helper.make_model(
        graph,
        producer_name="aidos-echo",
        opset_imports=[helper.make_opsetid("", OPSET)],
        doc_string=__doc__,
    )
    model.ir_version = IR_VERSION
    model.model_version = 1

    for key, value in {
        "task": "smoke-test",
        "contract": "output_ids[i] = input_ids[i]; input_ids must be in 0..255",
        "involution": "the identity function is its own inverse trivially",
        "license": "EUPL-1.2",
    }.items():
        entry = model.metadata_props.add()
        entry.key, entry.value = key, value

    onnx.checker.check_model(model, full_check=True)
    return model


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "-o",
        "--output",
        type=pathlib.Path,
        default=pathlib.Path(__file__).with_name("echo.onnx"),
    )
    args = parser.parse_args()

    model = build_model()
    onnx.save(model, args.output)
    print(f"wrote {args.output} ({args.output.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
