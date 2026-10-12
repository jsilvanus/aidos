#!/usr/bin/env python3
"""Export a Laya checkpoint (e.g. multilingual) to the 5-input ONNX graph Aidos' Decision backend loads.

Code only: weights and the resulting .onnx stay OUTSIDE the repository (CLAUDE.md "never store model
weights"). RFC-0022 (decision capability). Needs: torch, transformers, onnx, onnxruntime, laya.

    python export_onnx.py CHECKPOINT_DIR --subfolder multilingual --out laya_multilingual.onnx [--int8]

Graph contract (what the Kotlin side feeds):
    inputs : input_ids[n,l] i64, attention_mask[n,l] i64, marker_pos[n,o] i64, marker_mask[n,o] bool, qtype[n] i64
    outputs: logits[n,o] f32, act_logits[n,2] f32
The graph's top-2 branch is traced, so callers must pad `o` to >= 2 (masked slots score -1e4).
"""
import argparse, sys
import numpy as np
import torch


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("checkpoint")
    ap.add_argument("--subfolder", default=None)
    ap.add_argument("--out", required=True)
    ap.add_argument("--int8", action="store_true", help="also write <out>.int8.onnx (dynamic quantization)")
    a = ap.parse_args()

    import laya
    import onnxruntime as ort

    # The fused fast path emits aten::_transformer_encoder_layer_fwd, which the exporter cannot lower.
    torch.backends.mha.set_fastpath_enabled(False)
    agent = laya.Agent(a.checkpoint, subfolder=a.subfolder)
    model = agent.model.float().cpu().eval()
    model.encoder.config.reference_compile = False

    class Wrap(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, input_ids, attention_mask, marker_pos, marker_mask, qtype):
            return self.m(input_ids, attention_mask, marker_pos, marker_mask, qtype)

    w = Wrap(model)
    q, l, o = 3, 24, 4
    ids = torch.randint(5, 1000, (q, l))
    am = torch.ones(q, l, dtype=torch.long)
    mp = torch.tensor([[3, 6, 9, 12]] * q)
    mm = torch.ones(q, o, dtype=torch.bool)
    qt = torch.tensor([0, 1, 2])
    with torch.no_grad():
        torch.onnx.export(
            w, (ids, am, mp, mm, qt), a.out,
            input_names=["input_ids", "attention_mask", "marker_pos", "marker_mask", "qtype"],
            output_names=["logits", "act_logits"],
            dynamic_axes={
                "input_ids": {0: "n", 1: "l"}, "attention_mask": {0: "n", 1: "l"},
                "marker_pos": {0: "n", 1: "o"}, "marker_mask": {0: "n", 1: "o"}, "qtype": {0: "n"},
                "logits": {0: "n", 1: "o"}, "act_logits": {0: "n"},
            },
            opset_version=17, dynamo=False,
        )
        # Parity at a shape other than the traced one, so a baked-in constant shows up here.
        ids2 = torch.randint(5, 1000, (5, 200))
        am2 = torch.ones(5, 200, dtype=torch.long)
        mp2 = torch.tensor([[3, 6, 9]] * 5)
        mm2 = torch.ones(5, 3, dtype=torch.bool)
        qt2 = torch.arange(5) % 3
        ref = w(ids2, am2, mp2, mm2, qt2)
    s = ort.InferenceSession(a.out, providers=["CPUExecutionProvider"])
    out = s.run(None, {"input_ids": ids2.numpy(), "attention_mask": am2.numpy(), "marker_pos": mp2.numpy(),
                       "marker_mask": mm2.numpy(), "qtype": qt2.numpy()})
    d = float(np.abs(out[0] - ref[0].numpy()).max())
    print("max |logit diff| vs torch at an untraced shape:", d)
    if d > 1e-2:
        sys.exit("export parity check failed")
    if a.int8:
        from onnxruntime.quantization import quantize_dynamic, QuantType
        qout = a.out.replace(".onnx", ".int8.onnx")
        quantize_dynamic(a.out, qout, weight_type=QuantType.QInt8)
        print("wrote", qout)


if __name__ == "__main__":
    main()
