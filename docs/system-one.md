# System One (typed decisions) in the Aidos Engine

Status: implemented and tested on the JVM/desktop host; Android wiring written but never compiled or run (see "Not done"). Design:
RFC-0022 "Decision capability", D45 (settled). Background: `LAYA.md`.

## What it is

A *System One* model answers several typed questions about a text in one non-generative pass and
returns probabilities. Primitives: `choice` (one of N options), `score` (ordered levels, returned as an
expected index), `noul` (yes/no probability). The Engine serves them at `POST /v1/systemone`
(alias `/v1/decisions`), the Jev / OpenJev / laya-serve shape:

```json
{ "model": "laya-multilingual",
  "state": "poista edellinen lause",
  "questions": {
    "cmd":    { "type": "noul",   "instructions": "The speaker is giving an instruction to the editor rather than dictating content." },
    "intent": { "type": "choice", "instructions": "...", "criteria": { "COMMAND": "...", "DICTATION": "..." } } },
  "min_confidence": 0.8 }
```

Response: `{model, answers: {id: {type, choice|noul|score, probabilities, confidence, answer_confidence, abstained?}}, usage}`.
SDK: `AidosEngineClient.decide(DecisionRequest)`.

## Installing Laya multilingual

Weights are never committed. A model is a package directory `<models dir>/<modelId>/`:

```
model.onnx            python scripts/laya/export_onnx.py CHECKPOINT --subfolder multilingual --out model.onnx [--int8]
tokenizer.json        checkpoint's multilingual/tokenizer/tokenizer.json
rl_agent_config.json  checkpoint's multilingual/rl_agent_config.json
```

The export checks parity against PyTorch at an untraced shape. `--int8` quantisation is provided but
**not evaluated** here; compare its output with fp32 before using it. The fp32 graph is 1.29 GB; the
catalog installer now installs a Hugging Face repo that holds the three files as one package directory
(`ModelPackage` / `installPackage`, desktop and Android apps).

## Layout

| Piece | Where |
|---|---|
| Contract, tokenizer, Laya encoder/decoder, wire JSON | `engine/decisions` (commonMain) |
| ONNX Runtime runner, package loader | `engine/decisions` (jvmAndAndroidMain) |
| Backend decorator (llama.cpp ids unchanged) | `OnnxDecisionBackend`, wired in `DesktopEngineHost` |
| HTTP route | `EngineHttpServer.handleDecisions` |

## Verified

`gradle :decisions:jvmTest` with `AIDOS_LAYA_DIR` (checkpoint's `multilingual/` folder) and
`AIDOS_LAYA_ONNX` (the exported graph) set compares against the Python reference (laya 0.4.6): the
Kotlin tokenizer reproduces Hugging Face ids on 20 strings (Finnish, Finnish diacritics, CJK,
emoji, added tokens), the encoder reproduces the reference sequences and markers, and ONNX Runtime
from Kotlin reproduces the reference answers. Without those variables the checkpoint-dependent tests
skip and the decoder test (recorded logits -> published answers) and tokenizer unit tests still run.

## Dictation routing: zero-shot result (why Dictator is not wired in)

`scripts/laya/dictation_eval.py`: 72 synthetic, author-labelled utterances (36 Finnish, 36 English;
24 dictation, 24 editor command, 24 AI request), base `multilingual` checkpoint, CPU, ~110 ms for one
question including tokenization. Feasibility only, not production accuracy.

| Formulation | Result |
|---|---|
| 3-way `choice` (dictation / command / AI request) | 36% accuracy: answers DICTATION for almost everything; command recall 8%, AI-request recall 4% |
| 2-way `choice` (command vs dictation) | 65% |
| `noul` "instruction to the editor rather than content" | 79%; 1 of 24 dictations misread as a command; 9 of 24 commands missed |
| `noul` "asking an assistant to write/change/explain" | ~0 AI requests found |
| `score` for command strength | uninformative (same value for everything) |

So the capability works as built, but the **base checkpoint is not good enough to route Dictator input**
(this matches the model card: base checkpoints are near chance zero-shot and need fine-tuning). Wiring
it into Dictator's execution path now would misroute. Next step is fine-tuning on Dictator-shaped Finnish
and English utterances; until then the only defensible use is advisory (`noul` command signal in shadow
mode, logged and compared with the deterministic activation-word parser), never executing anything.

## Not done

- Android: `AndroidOnnxDecisionBackend` wraps the llama.cpp backend in `EngineService` and shares
  `DecisionPackages` with the desktop backend (that shared part is JVM-tested). The Android glue, the
  `onnxruntime-android` dependency and the proguard rule have not been compiled or run (Android targets do not
  compile in the cloud environment). The 34 MB `tokenizer.json` is parsed as JSON at load; that needs measuring
  on a phone.
- Multi-file packages install from a Hugging Face repo on desktop and Android (`installPackage`, tested with fake
  downloaders and unit tests). No Laya ONNX package is published on the Hub yet. `SuggestedModels` still lists
  the ggmlc GGUF as not runnable.
- Calibration: probabilities are the checkpoint's own (temperature 1.0). Zero-shot base checkpoints are
  over-confident and weak on new tasks; fit temperatures and fine-tune on your own data before relying on them.
- Dictator uses it in shadow mode only (off by default): the model's guess is logged beside the parser's
  decision and never acted on. See `scripts/laya/dictation_eval.py` for why routing is not allowed yet.
- Fine-tuning on Dictator data is deferred.
