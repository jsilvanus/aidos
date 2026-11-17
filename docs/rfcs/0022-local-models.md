# RFC-0022: Local Models

Status: Implemented 2026-11-15 (Accepted 2026-08-03). Implementation evidence: [STATUS.md](STATUS.md).
Non-MVP Work: **Remaining** (remote-model cookbook section, cross-session auto-tuning, on-device G3/M21 budget validation)
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

### Amendment 2026-08-14 (RFC-0103) — read this whole RFC as DESKTOP/HEADLESS_SERVER design; on
### MOBILE, Aidos Engine is now authoritative for everything below

RFC-0103 splits local model hosting off Aidos Agent onto a separate app, **Aidos Engine**, on
MOBILE only (DESKTOP and HEADLESS_SERVER are explicitly out of RFC-0103's scope). This is not a
one-section correction the way most of this corpus's other RFC-0103 amendments are — this RFC's
entire subject matter (where weights live, the cookbook, acquisition, the admission queue,
foreground-only inference, storage management, model lifecycle) describes work that, on MOBILE, is
now Aidos Engine's to do, not Aidos Agent's. RFC-0103's own Motivation says as much directly: "This
RFC does not itself rewrite RFC-0050 or RFC-0022... If this RFC is accepted, those passages need
superseding language in a follow-up commit... recorded here so it isn't lost." This amendment is
that follow-up, in addendum form rather than a rewrite — a full rewrite is a separate, larger task
this amendment does not attempt.

**What stays true, unconditionally, everywhere:** every *design decision* below — user scope not
project scope, content-addressed digests, the cookbook's device-fit computation (weights + KV
cache + overhead, four verdicts), never-automatic acquisition, the global admission queue with LRU
eviction, foreground-only inference, manual-only storage removal, the embedding-model-pinning rule,
offer-don't-apply updates, `UnavailableOffline` as first-class — all of it is **exactly what
RFC-0103 also specifies for Aidos Engine**, largely verbatim (RFC-0103's own Design section cites
this RFC by name for several of these: "RFC-0022's model storage is already scoped to user,"
"RFC-0022 is explicit that Engine never deletes weights on its own to make room," "matching
RFC-0022's own MVP section," "per RFC-0022"). **What changes is which component does it and where
it's read from:**

| This RFC's claim | On DESKTOP/HEADLESS_SERVER | On MOBILE |
|---|---|---|
| Where weights live | `~/.aidos/models/`, Aidos Agent's `user.db` | Aidos Engine's own app-private storage (RFC-0103, Data Model) |
| Who runs the admission queue | Aidos Agent's `ModelRuntime` | Aidos Engine, extended across every client app on the device, not just Aidos Agent's own projects (RFC-0103, "Concurrency and memory policy") |
| Who serves the cookbook/acquisition UI | Aidos Agent | Aidos Engine's own UI screens (RFC-0103, "Aidos Engine's own UI") — Aidos Agent's model-selection surface becomes a client of Engine's catalogue, not the catalogue's owner |
| Foreground requirement | Aidos Agent's own foreground service, in-process | Aidos Engine's own, separate foreground service (RFC-0103, "Two apps, one device") — see RFC-0006's 2026-08-14 amendment for the still-open question of whether Aidos Agent additionally needs its own |
| Model selection (which model to use) | Aidos Agent's routing layer, unchanged | Unchanged — RFC-0103 is explicit this stays "user-owned policy" in the calling app, never Engine's decision |

Read every section below with that substitution in mind on MOBILE: the *policy* is this RFC's; the
*component* is Aidos Engine's. Where this document says "the runtime does X" or "Aidos does X," on
MOBILE that is now Aidos Engine doing X, reached from Aidos Agent over Aidos SDK — this amendment
does not repeat that qualifier at every single sentence below, since RFC-0103 is the authoritative
document for the corrected component ownership.

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

A model whose digest does not match what the catalogue promised is **quarantined, not kept and
not silently discarded** (amended 2026-11-16, Juha). The file is moved out of the model store, so
a mismatched file never stays where it could be listed or loaded, but it is kept for a short time
so a bad catalogue digest, a bad mirror or a tampered download can be examined.

- The file is moved by atomic rename into a `quarantine/` directory beside the model store, named
  `<original-name>.<first 12 hex of the actual sha256>.quarantined`, with a sidecar
  `<same name>.json` holding the model id, expected digest, actual digest, source URL and time.
- Nothing in the Engine loads, lists or serves from `quarantine/`. The catalogue, `/v1/models`
  and the loader do not see it: the model counts as not installed, and the caller gets the same
  typed digest-mismatch error as before (API v1 is additive-only).
- Disk use is bounded: at most the newest 2 quarantined files are kept, and any older than
  7 days are deleted. This is checked at engine start and after each new quarantine.
- A partial download (interrupted, short) is not a digest mismatch and is handled as before.
- If the rename fails (another filesystem, permissions) the file is deleted instead and the
  failure logged. A mismatched file never stays in the model store.
- There is no automatic "accept anyway". Releasing a file from quarantine is future work and is
  not part of this RFC's MVP.

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

**Changing the model invalidates the index, which is a cache.** Nothing is corrupted and
nothing is lost: the index is derived state, rebuildable from Git and the filesystem at any time
(RFC-0017). What changes is that every existing vector becomes unusable at once, so the index is
marked invalid and search falls back to FTS until it is rebuilt.

**The rebuild is offered, not started.** On a phone with a large repository it is a
foreground-service job measured in hours, so the app states the cost and waits to be told —
the same rule as never auto-downloading a model. Until then search still works, on FTS alone,
marked degraded (`IndexStatus.degraded`). The rebuild is resumable like any other indexing
(RFC-0009): interrupted, it resumes rather than restarting.

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
3. Explicit acquisition, resumable, digest-verified, quarantined on mismatch.
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

## Amendment 2026-11-16: prompt formatting and tool calling

Added after the 2026-11-16 code review found that both llama.cpp adapters wrote prompts as
`User:` / `Assistant:` lines and that tools never reached a local model.

1. **The model's own chat template is the prompt format.** A GGUF that declares
   `tokenizer.chat_template` is prompted by evaluating that template (llama.cpp's Jinja engine)
   over the request's turns, with the generation prompt appended. The runtime never invents a
   role framing for such a model. If the template cannot be evaluated, the adapter falls back to a
   role-labelled prompt and logs that it did.
2. **A model without a template gets its content verbatim.** It has no notion of roles; any
   framing would be text the caller did not send.
3. **System turns are merged into one leading system message**, because several templates accept
   only that.
4. **Tools use one documented text protocol on every local model** (RFC-0021, "constrained
   decoding or a documented text protocol"): the system message lists the tools and asks for
   calls as `{"tool": "<name>", "args": {...}}`, which `ToolCallParser` reads back. Model-family
   tool syntaxes are not used, since the binding returns raw text and they could not be parsed.
   Earlier calls and results are replayed in the same syntax.
5. **`tool_choice` decides constraint.** `required` and a specific tool constrain decoding with a
   GBNF grammar, so the output can only be a call. `auto` leaves the model free to answer in
   prose, so the call is parsed heuristically and `ToolCall.rawText` is kept. `none` offers no tools.
6. **Text returned to the caller excludes the parsed calls.**
7. **The evaluated prompt prefix is cached between requests** (`cache_prompt`), so a conversation
   does not re-evaluate its history on every turn.
8. **Desktop and Android share one implementation** (`LlamaChatPrompt`), so the two hosts cannot
   differ in how a request becomes a prompt.

9. **Per-request sampling.** A request's `temperature` and `top_p`, when the client sends them,
   apply to that request; values the client leaves out fall back to the per-model run settings.
   `ModelRequest` (kernel, frozen at G0) has no fields for them, so the Engine carries them beside
   the request as a coroutine context element (`SamplingOverride`) that the llama adapters read.
   No kernel contract changes.
10. **Unknown roles are refused.** A message whose role is not `system`, `developer`, `user`,
    `assistant` or `tool` is a 400. It is never treated as a system turn: a typo must not gain
    system authority. `developer` is read as `system`.

## Implementation Files

```yaml
files:
  - engine/cookbook/src/commonMain/kotlin/dev/aidos/cookbook/Cookbook.kt
  - engine/modelruntime/src/commonMain/kotlin/dev/aidos/modelruntime/GlobalModelRuntime.kt
  - engine/modelruntime/src/commonMain/kotlin/dev/aidos/modelruntime/ModelRuntimeErrors.kt
  - engine/modelruntime/src/commonMain/kotlin/dev/aidos/modelruntime/SamplingOverride.kt
  - engine/modelruntime/src/commonMain/kotlin/dev/aidos/modelruntime/ModelRunSettings.kt
  - engine/modelruntime/src/jvmMain/kotlin/dev/aidos/modelruntime/LlamaCppAdapter.kt
  - engine/modelruntime/src/jvmMain/kotlin/dev/aidos/modelruntime/LlamaChatPrompt.kt
  - engine/modelruntime/src/jvmTest/kotlin/dev/aidos/modelruntime/LlamaChatPromptTest.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/inference/AndroidLlamaCppAdapter.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/EngineService.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopEngineHost.kt
  - engine/cookbook/src/jvmTest/kotlin/dev/aidos/cookbook/CookbookEngineTest.kt
  - engine/modelruntime/src/jvmTest/kotlin/dev/aidos/modelruntime/GlobalModelRuntimeTest.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopModelDetailModel.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopPreferences.kt
  - engine/engineui/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/ui/ModelDetailContent.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/ui/UiModels.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/SettingsStore.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ModelDetailScreen.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/inference/AndroidLlamaCppInferenceBackend.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/inference/AndroidOnnxVisionBackend.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/inference/InferenceTester.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/loading/ModelLoader.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/suggestions/SuggestionPreferences.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/DeviceProfileProvider.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ModelConfigScreen.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ModelDetailViewModel.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ModelsScreen.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ModelsViewModel.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/TestChatScreen.kt
  - engine/androidapp/src/androidUnitTest/kotlin/fi/italeino/aidos/engine/ui/PackageValidationTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopPreferencesRunSettingsTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/LoadedModelTest.kt
  - engine/downloads/src/androidMain/kotlin/dev/aidos/downloads/LocalDownloadManager.kt
  - engine/downloads/src/commonMain/kotlin/dev/aidos/downloads/DownloadManager.kt
  - engine/downloads/src/jvmMain/kotlin/dev/aidos/downloads/LocalDownloadManager.kt
  - engine/downloads/src/jvmTest/kotlin/dev/aidos/downloads/DownloadManagerTest.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/http/Quantization.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/license/InMemoryLicenseAcceptanceStore.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/license/LicenseAcceptanceStore.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/suggestions/SuggestedModels.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/ui/ModelSelfTest.kt
  - engine/enginehost/src/jvmTest/kotlin/fi/italeino/aidos/engine/http/EngineHttpServerEmbeddingsTest.kt
  - engine/enginehost/src/jvmTest/kotlin/fi/italeino/aidos/engine/http/EngineHttpServerIntegrityTest.kt
  - engine/enginehost/src/jvmTest/kotlin/fi/italeino/aidos/engine/http/EngineHttpServerSystemOneTest.kt
  - engine/enginehost/src/jvmTest/kotlin/fi/italeino/aidos/engine/license/LicenseAcceptanceStoreTest.kt
  - engine/enginehost/src/jvmTest/kotlin/fi/italeino/aidos/engine/suggestions/SuggestedModelsTest.kt
  - engine/enginehost/src/jvmTest/kotlin/fi/italeino/aidos/engine/ui/ModelLoadingStateTest.kt
  - engine/enginehost/src/jvmTest/kotlin/fi/italeino/aidos/engine/ui/ModelSearchFiltersTest.kt
  - engine/enginehost/src/jvmTest/kotlin/fi/italeino/aidos/engine/ui/ModelSelfTestTest.kt
  - engine/engineui/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/ui/ModelsPanes.kt
  - engine/huggingface/src/commonMain/kotlin/dev/aidos/huggingface/HuggingFaceClient.kt
  - engine/huggingface/src/commonMain/kotlin/dev/aidos/huggingface/ModelArtifact.kt
  - engine/huggingface/src/commonMain/kotlin/dev/aidos/huggingface/ModelPackage.kt
  - engine/huggingface/src/jvmTest/kotlin/dev/aidos/huggingface/HuggingFaceClientTest.kt
  - engine/huggingface/src/jvmTest/kotlin/dev/aidos/huggingface/ModelArtifactTest.kt
  - engine/modelruntime/src/jvmMain/kotlin/dev/aidos/modelruntime/LlamaCppInferenceBackend.kt
  - engine/modelruntime/src/jvmMain/kotlin/dev/aidos/modelruntime/ModelFileDigests.kt
  - engine/modelruntime/src/jvmTest/kotlin/dev/aidos/modelruntime/ModelFileDigestsTest.kt
  - engine/downloads/src/jvmAndAndroidMain/kotlin/dev/aidos/downloads/ModelQuarantine.kt
  - engine/downloads/src/jvmTest/kotlin/dev/aidos/downloads/ModelQuarantineTest.kt
  - engine/modelruntime/src/jvmTest/kotlin/dev/aidos/modelruntime/ModelQuarantineBackendTest.kt
  - engine/modelruntime/src/jvmTest/kotlin/dev/aidos/modelruntime/GgufRot13FixtureTest.kt
  - engine/modelruntime/src/jvmTest/kotlin/dev/aidos/modelruntime/ModelRunSettingsTest.kt
  - engine/models/src/commonMain/kotlin/dev/aidos/models/DatabaseModelCatalogManager.kt
  - engine/models/src/commonMain/kotlin/dev/aidos/models/ModelBrowser.kt
  - engine/models/src/commonMain/kotlin/dev/aidos/models/ModelCatalog.kt
  - engine/models/src/commonMain/kotlin/dev/aidos/models/PackageInstall.kt
  - engine/models/src/jvmTest/kotlin/dev/aidos/models/DatabaseModelCatalogManagerTest.kt
  - engine/models/src/jvmTest/kotlin/dev/aidos/models/ModelBrowserTest.kt
  - engine/models/src/jvmTest/kotlin/dev/aidos/models/PackageInstallPlanTest.kt
  - engine/models/src/jvmTest/kotlin/dev/aidos/models/PackageInstallTest.kt
```

## Post-MVP Work

Checklist for work beyond the MVP. Each item has a stable id; set `status` to `partial` or `done` and list the implementing `files` (checked by `scripts/rfc/postmvp.py`). The `Non-MVP Work:` header must say `None` once every item is `done`.

```yaml
post_mvp:
  - id: "0022.1"
    title: "remote-model cookbook section"
    status: todo
  - id: "0022.2"
    title: "cross-session auto-tuning"
    status: todo
  - id: "0022.3"
    title: "on-device G3/M21 budget validation"
    status: todo
  - id: "0022.4"
    title: "RUNS_TIGHT models are offered only behind an explicit opt-in (decided 2026-11-17)"
    status: todo
  - id: "0022.5"
    title: "Model concurrent residency of an LLM and an embedding model during indexing (admission queue serializes them today)"
    status: todo
  - id: "0022.6"
    title: "Present per-device model measurements without aggregating them across devices"
    status: todo
```
