# LoRA adapters for Aidos: discovery and study

2026-11-18. Research only, no code changed. Sources are linked inline; vendor numbers are
self-reported unless said otherwise.

**Status: post-MVP** (owner decision, 2026-11-18). Nothing here is scheduled before MVP. The
remaining open questions are listed at the end.

## TL;DR

- What Juha most likely saw: **Hugging Face "harness adapters"**, especially
  `harness-hub/Qwen3.8-27B-pi-adapted` (announced ~2026-11-17). A rank-32 LoRA trained with RL
  *inside an unmodified agent harness* (pi), so the model learns that harness's tools and habits.
  Reported: SWE-bench Pro (hard 51) 39.2% → 64.7%, Terminal-Bench 2.1 41.0% → 56.6%, ~55–62%
  fewer tool calls, ~4.5x fewer tokens than base-with-thinking. Not independently replicated yet.
- **The off-the-shelf adapters don't fit Aidos directly.** They are tied to one base model and
  one harness's tool format (pi/opencode/hermes/claude-code), and the bases are 27B/24B, which
  don't fit an RTX 2060 or OnePlus 8. The *idea* fits Aidos very well, though.
- **Why it fits:** RFC-0129 makes every local model speak one Aidos-specific text protocol
  (`{"tool": ..., "args": {...}}`), which no base model was trained on. That is exactly the gap a
  small harness adapter closes (a community 7B adapter went from 1.7% to 84.7% valid tool calls on
  its own harness format).
- **Runtime is already there.** Our binding `net.ladenthin:llama` 6.0.0 (desktop jar and Android
  AAR) can load GGUF LoRA adapters at model load and change their scales at runtime without
  reloading. No per-request adapter selection.
- **Recommendation:** (1) Add "adapter" as a first-class, digest-pinned artifact bound to one
  base-model digest, load-time only, in RFC-0022's catalogue. (2) Run a small spike: SFT a rank-16
  LoRA on a 1.5–4B cookbook model using traces from our own agent loop, measure tool-call validity
  in `Auto` mode before and after. Only then consider RL or hot-swap. Details below.

## 1. What exists (discovery)

### Harness-tuned adapters

| Work | Base | Harness | Method | Reported result | Notes |
|---|---|---|---|---|---|
| [harness-hub/Qwen3.8-27B-pi-adapted](https://huggingface.co/harness-hub/Qwen3.8-27B-pi-adapted) ([announcement summary](https://daily.dev/posts/qwen3-8-27b-pi-adapted-a-lora-adapter-tuned-for-the-pi-coding-agent-ntexkkcg7)) | Qwen3.8-27B | pi | RL (AsyncGRPO), LoRA r=32 α=64 on q,k,v,o,gate,up,down; ~640 MB | SWE-bench Pro hard-51 39.2→64.7, Terminal-Bench 2.1 41.0→56.6 (thinking off) | Apache-2.0. GGUF adapter in sibling repo (`…-pi-adapted-GGUF`, F16), run on a **Q8_0** base; card says lower quants drift and the GGUF wasn't re-evaluated. Checkpoint chosen on the same benchmarks. |
| [Harness adapters blog](https://huggingface.co/blog/harness-hub/harness-adapters) (2026-11-06) | LFM2.5-24B-A2B, LFM3-1.7B, TwIL-8B | pi, opencode, hermes (+ claude-code for LFM2.5) | GRPO in TRL, reward = verifier + small bonus for fewer tool calls on correct rollouts; r=32 all-linear | "Smoke-test" only: fewer tool calls, accuracy kept or better | Training data skewed (253/270 "build" tasks). A commenter asks whether adapters survive renamed tools; unanswered. Org page: [harness-hub](https://huggingface.co/harness-hub). |
| [Alogotron/a0-toolcall-lora](https://huggingface.co/Alogotron/a0-toolcall-lora) | Qwen2.5-7B-Instruct | Agent Zero | QLoRA SFT r=16, 887 real records, one RTX 3090 | Valid tool calls 1.7% → 84.7% on 59 held-out | Closest analogue to Aidos: a custom JSON tool format the base never saw. Small, single-household data. |
| [gemma-4-E4B claude-code LoRA](https://huggingface.co/deadbydawn101/gemma-4-E4B-opus-reasoning-claude-code-lora) | Gemma 4 E4B | Claude Code style | SFT on Claude traces | none credible | Provenance unverified; also likely against Anthropic terms for training on outputs. Listed only as "exists". |

Independent check: [OnixResearch/inference-lab#49](https://github.com/OnixResearch/inference-lab/issues/49)
(2026-11-17) proposes comparing the pi adapter against prompt-only and harness-only changes on a
frozen task set. Nothing has been run yet. That comparison is the right skepticism: some of the
gain may be "learn this harness's quirks", which a prompt change could also buy.

### Small models already post-trained for harnesses (no adapter needed)

- [LiquidAI LFM3-1.7B](https://huggingface.co/LiquidAI/LFM3-1.7B) / [GGUF](https://huggingface.co/liquidai/lfm3-1.7b-gguf):
  post-trained for Hermes Agent, pi and OpenClaw (per mirrors of the card; verify on the official
  card), served via llama.cpp. [Liquid's harness guide](https://docs.liquid.ai/examples/agent-harnesses).
  Phone-sized. Worth adding to the cookbook as a base regardless of LoRA.
- On-device function-calling research: [Hammer](https://arxiv.org/abs/2410.04587) (1.5B beats
  xLAM-7B on some benchmarks; uses function masking so names/descriptions don't overfit — relevant
  to "survive renamed tools"), [TinyLLM](https://arxiv.org/abs/2511.22138) (1–3B clearly beats <1B
  on agentic tasks; covers SFT/PEFT/RL).

### Adapters as a runtime choice

- llama.cpp server: adapters loaded at start, global scale via `POST /lora-adapters`, per-request
  `lora: [{id, scale}]` override ([#10994](https://github.com/ggml-org/llama.cpp/pull/10994));
  requests with different adapter sets are not batched together.
- Activated LoRA ([paper](https://arxiv.org/abs/2504.12397), llama.cpp
  [#15327](https://github.com/ggml-org/llama.cpp/pull/15327)): adapter applies only after an
  invocation token sequence, so the base model's KV cache stays valid across swaps. Merge status
  and our binding's support are **unverified**.
- "Adapters as tools" research ([SLAaaT](https://arxiv.org/abs/2608.17034),
  [Adaptive Minds](https://arxiv.org/abs/2510.15416)): the agent picks an adapter mid-task.
  Research prototypes; not for MVP.

## 2. Runtime loading and hot-swap in Aidos

Checked against `net.ladenthin:llama-6.0.0-sources.jar` (Maven Central). Desktop
(`engine/modelruntime`) and Android (`engine/androidapp`, `llama-android` AAR) share this API.

- Load time: `ModelParameters.addLoraAdapter(path)` (repeatable),
  `addLoraScaledAdapter(path, scale)`, `setLoraInitWithoutApply()` (load at scale 0).
- Runtime: `LlamaModel.getLoraAdapters()`, `setLoraAdapters(Map<id, scale>)`,
  `setLoraAdapter(id, scale)`. No reload. Per the upstream contract, adapters missing from the map
  go to scale 0.
- **No per-request selection**: `InferenceParameters` has no LoRA field. The scale is global to the
  loaded model, so a switch affects every request in flight on that model.
- **Windows gotcha:** `addLoraScaledAdapter` rejects any path containing `:` (llama.cpp's
  `FNAME:SCALE` parsing), so `D:\aidos\...` fails. Use `addLoraAdapter(path)` then
  `setLoraAdapter(id, scale)` after load.
- Format: adapters must be GGUF (`convert_lora_to_gguf.py`, or HF's
  [GGUF-my-LoRA](https://huggingface.co/blog/ngxson/gguf-my-lora)). An F16 adapter can sit on a
  quantized base, but quality drifts more on low-bit bases (the pi card says Q8_0); Q4_K_M on
  phones needs its own evaluation.

What hot-swap would cost us:
- **Prefix cache:** RFC-0129 §7's prompt-prefix cache is computed with the active adapter. Any
  scale change must invalidate it (and the KV cache), or outputs silently mix two models. aLoRA is
  the only way around this, and we haven't verified it.
- **Concurrency:** one global scale means swaps must be serialized behind the model's request
  queue. Fine for a single-user engine; wrong for shared/routed engines unless swaps are gated.
- **Memory:** cheap. A rank-16 all-linear adapter on a 3B model is tens of MB (the 27B rank-32
  one is 640 MB). Several can stay resident at scale 0.

Conclusion: **load-time binding** (adapter is part of the model selection) is simple and safe now.
**Hot-swap** is possible but needs cache invalidation and request gating; defer until there are two
adapters worth swapping between.

## 3. Training our own "Aidos harness" adapter

Why it is worth it: RFC-0129 deliberately uses one family-neutral protocol, with a GBNF grammar
for `Required`/`Specific` but only heuristic parsing for `Auto`. Grammar fixes the *shape*, not
*whether to call*, *which tool*, or *good arguments*, and Auto (the common case) has no grammar at
all. A harness adapter targets exactly that, plus the habits the HF work measured (fewer, better
tool calls).

Suggested path, smallest first:

1. **Data from our own loop.** Log agent-loop episodes (system prompt as rendered by RFC-0129,
   tool schemas, calls, results, final answer) from desktop runs with a strong model. A remote
   model may only be used per RFC-0023 privacy approval and the provider's terms (no training on
   Claude/OpenAI outputs where the terms forbid it; an open-weight teacher such as a large Qwen on
   a rented GPU avoids that question). Add synthetic tasks over our real tool catalogue
   (filesystem, git, vault, MCP-bridged tools). Apply Hammer-style masking (random tool names and
   shuffled descriptions) so the adapter learns the *protocol*, not a fixed tool list.
2. **SFT spike.** QLoRA r=16 on a 1.5–4B cookbook model, loss on assistant turns only. This is a
   one-GPU job (a 24 GB card or a rented one; the RTX 2060's 6–8 GB is tight for 3B QLoRA but may
   work for 1.5B). Eval: tool-call validity and correct-tool rate in `Auto`, argument schema pass
   rate at `ToolBroker`, turns per task, refusal-to-call when no tool fits. Baseline = same model
   with no adapter, and an improved-prompt-only arm (the inference-lab#49 control).
3. **RL later, only if SFT plateaus.** GRPO with verifiable tasks as in the HF blog, inside the
   real Aidos agent loop. Heavier infrastructure; not before the SFT numbers.

Reuse from liturgos-auditor: it already does PEFT LoRA (`train --peft lora`), merge-on-export,
dataset lineage/purge, an evaluation gate and a model registry with promotion
(`docs/training-pipeline.md`). Copy that *shape* (versioned dataset → run → eval gate → register),
not the code: it is Whisper/CTranslate2-specific, and LLM tuning wants TRL/Unsloth plus
`convert_lora_to_gguf.py`. Data-protection rules carry over: agent traces contain user files and
messages, so training data needs the same lineage and purge tooling and should be built from
synthetic or consented episodes only.

Merge or ship as an adapter? For phones, **merging** into the base and quantizing once may give
better quality than an F16 adapter over a Q4 base, and is just "another GGUF" to the engine. Ship
unmerged when one base serves several adapters (desktop) or to keep the base download shared.

## 4. Model registry and digest implications

Today (RFC-0022, `engine/models` `ModelCatalog`, `engine/downloads/ModelQuarantine`): one artifact
per model, `expectedDigest` checked on download, mismatch → quarantine (keep 2, 7 days), license
text digest keys acceptance.

An adapter needs:
- **Its own catalogue entry and digest**, verified and quarantined exactly like a model file.
- **A hard binding to the base model's digest** (`baseModelDigest`). An adapter applied to a
  different quantization or revision loads without error and silently degrades, so the loader
  must refuse a mismatch rather than warn.
- **Identity of the effective model** = base digest + ordered (adapter digest, scale) list. This
  is what `/v1/models`, audit logs, tool-call provenance (RFC-0021 `rawText` audit) and any
  routing/trust decisions should name. A request routed to "qwen-3b" on another Engine is not the
  same model if one side has an adapter.
- **Its own license** (adapter and base licenses can differ; both need acceptance).
- **Trust:** an adapter changes model behaviour as much as new weights do. Treat it as a model
  artifact under the threat model (CLAUDE.md names model loaders as a docs/threat-model.md trigger), signed/curated
  in the cookbook, never fetched from an arbitrary URL the agent supplies. "Adapters as tools"
  would need a capability gate (RFC-0003) for this reason.
- **Cookbook device fit:** add adapter size to resident memory; record the quantization the
  adapter was evaluated on.

## 5. Recommendation

1. **Now, cheap:** add LFM3-1.7B (and keep Qwen 1.5–4B) as cookbook candidates and measure them on
   our RFC-0129 protocol as-is. Harness-post-trained small models may close much of the gap with
   no adapter at all.
2. **RFC change (owner decision):** amend RFC-0022's "Fine-tuned adapters" future-work line into
   a short section: adapter = catalogue artifact with own digest, bound to a base digest, applied
   at load time only, effective-model identity as above. Hot-swap and aLoRA stay Post-MVP.
3. **Spike (post-MVP, decided 2026-11-18):** SFT an "Aidos harness" LoRA on one small
   model from our own loop's traces, with the prompt-only control arm, and decide on further work
   from the numbers. Training runs on a rented GPU or a 24 GB card, not the 2060 or the phone.
4. **Don't** pull in the 27B harness-hub adapters: wrong harness format, wrong size for our
   hardware, unreplicated gains.

Decided: the "Aidos harness adapter" spike is post-MVP (owner, 2026-11-18).

Open questions (owner):
- Where would training run (rented GPU vs. a bigger local card)?
- Which teacher is acceptable for trace generation (open-weight only, or a remote model under
  RFC-0023 approval)?
