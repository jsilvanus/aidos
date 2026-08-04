# RFC-0022: Local Models

Status: Accepted 2026-08-03

## Abstract

Local models are what make offline-first real: without on-device inference, "works on a plane" is
marketing. This RFC defines where weights live, how they are acquired and verified, how the
**cookbook** tells a user which models will actually run on *their* device, why loading is
globally serialized, and why inference happens only in the foreground. The load-bearing
constraint throughout is that a mid-range phone has roughly as much usable memory as one loaded
model wants, and no more.

## Motivation

Remote inference requires connectivity and sends the user's code to somebody else's computer.
Both are unacceptable as a baseline for this product — the primary use case is a Git project on a
phone in airplane mode.

But local inference on a handset is genuinely hard, and the previous version of this document did
not engage with the hard parts. It described download, caching, quantization, and a curated model
list, and then said recommendations should be "accessible: can run on typical developer
machines". That is the desktop framing. On the target device the questions are: does this model
fit in RAM *alongside the context the user wants*, will loading it get the app killed, and can
inference finish inside an execution window.

It also cached models **per project**, contradicting user scope (RFC-0054) — a second project
would re-download three gigabytes — and it fell back to a remote provider automatically when no
local model was available, which contradicts routing being user-owned policy (RFC-0020) at
exactly the point where the promise matters.

## Goals

1. Define where weights live and at what scope.
2. Define the **cookbook**: which models fit *this* device, computed rather than asserted.
3. Define acquisition, verification, and eviction.
4. Define loading — the admission queue and why it is global.
5. State the foreground requirement and what happens without it.
6. State what happens when nothing fits.

## Non-goals

This RFC does not define model selection per request (RFC-0020) or remote providers (RFC-0023).

This RFC does not define the inference engine's internals. GGUF execution is a dependency, not
something Aidos implements.

This RFC does not train, fine-tune, or merge models.

## Design

### Weights live at user scope, once

`~/.aidos/models/` on desktop, the app-private equivalent on Android (RFC-0050). One copy, shared
by every project.

This is not a filing preference. A quantized 7B is 3.5–4.5 GB; per-project caching means a user
with three projects has spent twelve gigabytes on one model. `installed_models` and
`model_catalog` are in `user.db` accordingly (RFC-0054).

Weights are **content-addressed by digest**. The same model acquired twice is stored once, and a
partially-downloaded file can never be mistaken for a complete one.

### Format and engine, per model kind

| Kind | Format | Engine | Status |
|---|---|---|---|
| `LLM` | GGUF | **llama.cpp** | MVP |
| `EMBEDDING` | GGUF | llama.cpp | MVP |
| `STT` | GGUF | whisper.cpp | Phase 4, cut with voice (M33) |
| `TTS` | — | — | **gap**; see Future Work |
| everything else | — | — | not local |

**GGUF, executed by llama.cpp** (D27, D28). GGUF is a single self-describing file with
quantization built in and the widest selection of small models that run on a phone — and it is
llama.cpp's format, so choosing it chose the engine. Saying so explicitly matters: the previous
version named the format and left "a native dependency" unattributed, which is the most
consequential dependency in the product going unstated.

Three things follow, and they are costs rather than details:

- **Per-ABI native builds** — `arm64-v8a` at minimum, `x86_64` for the emulator. `armeabi-v7a` is
  not worth it for a device that cannot run a model anyway.
- **F-Droid reproducible builds are harder with native code**, and RFC-0050 commits to F-Droid.
  Validate this early rather than discovering it at M34.
- **The GGUF loader is the attack surface** named in Security below — concretely, llama.cpp's
  parser reading a file from the internet.

**Why llama.cpp and not ONNX Runtime**, which is better for embeddings and STT and ships official
Kotlin bindings: RFC-0021 requires **constrained decoding** for models without native tool
calling, and that is llama.cpp's GBNF. It is the mechanism by which a local model that cannot do
function calling still participates in the agent loop, and ONNX has no equivalent. A second
native runtime also doubles the ABI matrix, crash surface and F-Droid problem. See D28 for when
to revisit.

A model in a format no engine here reads is **unavailable, not unsupported** —
`AvailabilityReport` names it and says which conversion would be needed, rather than failing at
inference time (RFC-0049).

### The cookbook: what runs on *this* device

The cookbook answers one question — **"which models will actually work on my phone?"** — and it
answers it by computing, not by publishing a list of sizes and hoping.

A curated set of models ships with the app: known-good, tested, with stated strengths. That is
the *quality* filter, and a human has to do it. The cookbook then **filters and ranks that set
against the device**, which no human can do in advance because they do not know the device.

**Device profile**, sampled at first run and re-sampled when it can change:

```
total RAM · available RAM · storage free · CPU cores and ISA
NPU / GPU delegate present · thermal headroom · battery floor (RFC-0045)
```

**Model requirements**, and this is where naive answers go wrong:

```
resident ≈ weights (file size, after quantization)
         + KV cache
         + runtime overhead
```

**The KV cache is the part that catches people.** A 4 GB quantized 7B does not need 4 GB — it
needs 4 GB *plus* a cache that grows linearly with the context window, and at long contexts that
term can exceed the weights themselves. A model that "fits" at 4k context may be unusable at 32k.
So the cookbook computes fit **against the context length the user actually intends to use**, and
shows the trade:

```
  Qwen2.5 3B · Q4_K_M · 2.0 GB

    4k context     ✓ runs well          2.4 GB resident
   16k context     ✓ runs tight         3.3 GB resident
   32k context     ✗ will not fit       4.6 GB resident, 3.9 GB available
```

**Verdicts**, deliberately four rather than a yes/no:

| Verdict | Meaning |
|---|---|
| `RUNS_WELL` | Fits with headroom; sustained use is unlikely to be reclaimed by the OS |
| `RUNS_TIGHT` | Fits, but the app is a likely victim if another app demands memory |
| `EXCEEDS_CONTEXT` | Weights fit; the requested context does not. Offer a shorter context |
| `WILL_NOT_FIT` | Weights alone exceed what is available |

**What the cookbook cannot know, and says so.** Fit is a prediction, not a guarantee. Android may
reclaim a foreground service under memory pressure; sustained inference throttles as the device
heats, so a model that benchmarks well for ten seconds may halve in speed over two minutes; and
available RAM depends on what else the user is running. `RUNS_TIGHT` exists precisely to name
this band rather than pretend the boundary is sharp.

After a model has actually run, measured cold-start and tokens-per-second replace the estimate
for that device. Predictions are for models you have not run; measurements are better and the
cookbook prefers them.

### Naming and labels

Every model — bundled, downloaded, or a user-registered endpoint (RFC-0021) — carries a
**user-visible label** distinct from its technical identifier, and the label is what surfaces
show. The identifier is for the wire and the audit row; the label is for the person deciding
whether to approve an egress at a bus stop.

The catalogue shows, for every model: label, kind, where it runs (this device / a named
endpoint), size or reachability, and its cookbook verdict. "Where it runs" is not decoration —
it is the difference between a prompt that stays on the phone and one that does not, and it
belongs next to the name rather than two screens away.

### Acquisition

**Never automatic.** A multi-gigabyte download on a phone is a decision about the user's data
plan and storage, and the previous version's "disk space available? network available? YES →
download" is not a decision the runtime gets to make.

```
user chooses a model
    → cookbook verdict shown, with the resident estimate
    → download, resumable, progress in the ongoing notification
    → digest verified before the file is usable
    → recorded in installed_models
```

A model whose digest does not match what the catalogue promised is **deleted, not quarantined**.
There is no scenario in which the right response to "these are not the weights we asked for" is
to keep them.

### Loading is globally serialized

`ModelRuntime.load()` goes through a **single admission queue for the whole runtime**, not per
project or per session.

One loaded model can consume most of a phone's available memory. Two concurrent loads on a
mid-range device do not degrade gracefully; they get the process killed, which on Android means
losing the foreground service and parking every Run. The queue admits one load at a time, and
`RUNS_TIGHT` models are given the whole allowance.

Eviction is least-recently-loaded, and loading a second model unloads the first unless both fit.
Weights are `mmap`ed where the engine supports it, so a reload after eviction is page-cache warm
and much cheaper than the first load — which is what keeps the cold-start budget (under 10
seconds to first token, RFC-0045) reachable in practice.

### Inference requires the foreground

**D24.** A Run reaching a local model call runs under a foreground service with a visible ongoing
notification; without one it does its deterministic work, parks with
`SuspendedOperation.ForegroundRequired`, and notifies *"ready to continue"*.

It does **not** route to a remote model instead. That would contradict offline-first at the exact
moment it was promised, and it would send the user's code off the device because of a scheduling
constraint they never saw. Routing across the network boundary is user-owned policy (RFC-0020),
and a background window is not consent.

### Storage management

Models are the largest thing Aidos puts on the device by an order of magnitude, so the accounting
is user-visible and honest:

```
  Models · 6.2 GB of 11.4 GB free

    Qwen2.5 3B Q4      2.0 GB    used 2h ago
    Whisper base       0.3 GB    used yesterday
    nomic-embed        0.5 GB    used 2h ago
    Llama 3.1 8B Q4    3.4 GB    never run · will not fit
```

"Never run · will not fit" is the row that earns this screen: it is the download a user made
before the cookbook existed, or on a different device, and it is pure waste.

Removal is manual. The runtime does not delete weights to make room — an automatic deletion of a
four-gigabyte download over a metered connection is not a kindness. It reports and lets the user
choose. There is no per-project quota, no "increase limit", and no external-storage escape hatch;
on Android app-private storage is what exists (RFC-0050).

### Ongoing management

Acquisition is a moment; a model's life is longer. Five things happen to it afterwards, and each
was previously unspecified.

#### The embedding model is pinned per project

**A project's embedding model is fixed when its index is first built**, recorded as
`knowledge.embedding_model_id` in project settings (RFC-0036). Changing it requires an explicit
re-index that states its cost before it starts.

This is not conservatism. **Vectors from different embedding models are not comparable** — cosine
similarity across two embedding spaces is not merely less accurate, it is meaningless. So the
naive alternative, keeping old vectors and embedding new content with the new model, does not
degrade gracefully: it silently produces rankings that mix two incompatible spaces, and nothing
in the output reveals it. That option is rejected outright rather than deferred.

Storing vectors per `(blob, model)` would be coherent and multiplies vector storage on the device
with the least of it. Also rejected, for a capability almost nobody needs.

**The consequence, stated rather than discovered:** a user who wants a better embedding model
pays a full re-index. On a phone with a large repository that is a foreground-service job
measured in hours. The app says so before starting, and the job is resumable like any other
indexing (RFC-0009) — an interrupted re-index resumes, it does not restart.

This matches the upstream index implementation's locked-model-set behaviour, so the two agree
rather than needing reconciliation.

#### Updates are offered, never applied

A newer quantization or revision of an installed model appears in the catalogue as *"newer
available"* with its own cookbook verdict — a Q4 you can run may have a Q5 you cannot. The
existing copy keeps working and is not replaced until the user says so, for the same reason
acquisition is never automatic: it is their storage and their data plan.

#### A model removed while a Run is parked on it

The Run **fails with a named error identifying the model**, at resume. It does not resume into a
missing model and it does not silently substitute another — substitution would change what
produced the work without saying so, and every attempt already records `model_id` and
`model_version` precisely so that question has an answer.

#### Deprecation

A catalogue entry withdrawn upstream stops being offered and says why. **An installed copy keeps
working.** Aidos does not delete weights the user has because someone else stopped publishing
them.

#### Nothing syncs between devices

D16: no state sync. A model installed on desktop is not installed on the phone; an endpoint
registered on one is unknown to the other. Both must be set up per device.

This is the honest consequence rather than an oversight, and the API key makes it unavoidable in
any case — the vault does not travel (RFC-0035, RFC-0041). What *would* be worth carrying later
is the non-secret part of an endpoint registration, and that is Future Work rather than a gap.

### When nothing fits

`RoutingDecision.UnavailableOffline(kind)` — **not an error**. The user is told which model kind
is missing and what the options are: a smaller model, a shorter context, or a remote provider if
they have configured and permitted one.

A device that cannot run any local LLM is a supported configuration. It degrades to a project
browser with Git, knowledge search over the index, and remote inference if permitted — which is
still useful, and is a far better outcome than an app that will not open.

## Data Model

`schema/user.sql` is canonical: `model_catalog`, `installed_models`, and `resource_budgets`
(`memory_mb`, `battery_floor_pct`). Device profile and cookbook verdicts are **computed, not
stored** — the device changes, and a cached verdict about free memory is wrong within minutes.
Measured cold-start and throughput per `(model_id, device)` are retained as metric samples
(RFC-0037), because those are stable.

## Security

1. **A model file is untrusted input to a parser.** GGUF loading happens in the inference engine
   over data from the internet; a malformed file is a memory-safety question in a native
   dependency. Digest verification before first use is the mitigation available, and it is not a
   complete one — this is the largest native attack surface in the product and it should be
   stated rather than implied.
2. **No code is executed from a model repository.** Weights only. Nothing in the acquisition path
   runs a script, and formats that carry executable payloads — notably pickle-based checkpoints —
   are not supported, which is a second reason for GGUF beyond convenience.
3. **Model output is `UNTRUSTED`** (RFC-0027), local or not. Running on-device changes where
   inference happens, not whether its output can be trusted with authority.
4. **Downloads are the only network activity** this subsystem performs, and they are subject to
   egress policy like anything else (RFC-0042). A local model that phones home is a contradiction
   in terms.

## MVP

1. GGUF, user scope, content-addressed by digest.
2. Cookbook: device profile × model requirements including KV cache, four verdicts, measured
   values replacing estimates once a model has run.
3. Explicit acquisition, resumable, digest-verified, deleted on mismatch.
4. Global admission queue, LRU eviction, `mmap` where available.
5. Foreground-only inference, parking otherwise (D24).
6. Storage screen with per-model attribution and manual removal.
7. `UnavailableOffline` as a first-class outcome.

One LLM and one embedding model must meet RFC-0045's budgets on a real mid-range phone. That is
M21, and it gates G3.

## Future Work

- **NPU and GPU delegates** where the platform exposes them. Substantial speedup, substantial
  fragmentation; worth doing after the CPU path is honest.
- **ONNX Runtime for embeddings and STT**, revisited after G3 with measurement (D28). Better NPU
  access and official Kotlin bindings, against a doubled native surface — a numbers question.
- **Local TTS**, which is the one kind the ggml family does not serve well today. RFC-0057's
  spoken summaries depend on it, and without it eyes-free operation is unavailable rather than
  degraded.
- **Speculative decoding** with a small draft model, if two models can be resident at once on the
  target hardware — which today they usually cannot.
- **Community cookbook entries**, contributed and signed, so the curated set is not limited to
  what one maintainer has tested. The device-fit computation is unchanged; only the input list
  grows.
- **Per-model context presets**, so "this model at 8k" is a first-class catalogue entry rather
  than something the user configures.
- **Fine-tuned adapters** (LoRA) applied at load time, once there is a reason more specific than
  that it is possible.

## Open Questions

- Should `RUNS_TIGHT` be offered at all by default, or only behind an explicit "show models that
  may be unstable"? It is the band where the app gets killed, and a user who does not understand
  the verdict will blame Aidos rather than the model.
- Does the cookbook need to model *concurrent* load — an LLM and an embedding model resident
  together during indexing — or is serializing them through the admission queue sufficient?
- How should a model that runs well on one device and badly on another be presented, once
  measurements exist across devices? Aggregating them would be misleading; showing only the
  local measurement discards useful information.
