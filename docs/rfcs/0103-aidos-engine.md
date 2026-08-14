# RFC-0103: Aidos Engine — Shared Local Inference Service

Status: Accepted 2026-08-14

## Abstract

This RFC splits local model inference out of the Aidos Android app into a standalone app, **Aidos
Engine**, so that multiple independent apps on one device — Aidos Agent among them — can share
one loaded set of models instead of each bundling and loading its own copy. Aidos Engine mirrors
Aidos's own core/frontend split: **Aidos Engine Core** is a headless, platform-agnostic module
with no Android or UI dependency, and the **Aidos Engine** Android app hosts it in-process inside
a foreground service, the same shape RFC-0050 already uses for Aidos Agent hosting the runtime. A
third component, **Aidos SDK**, is the one client-side implementation of the handshake and
transport that every consuming app — Aidos Agent included — links against, rather than each app
reimplementing the wire protocol. This RFC defines that three-way boundary, the loopback
transport and its authentication handshake, the version/capability contract, and the memory and
concurrency policy for serving several modalities to several clients at once.

## Motivation

RFC-0050 hosts the runtime, including model loading, **in-process** on MOBILE, on the stated
grounds that "Android has no meaningful multi-process story for this" and "MOBILE has exactly
one frontend by construction" (RFC-0055). That premise no longer holds: a second app, independent
of Aidos Agent, is already in development and needs the same local LLM/embedding/STT inference.
Under the current design each app would bundle its own llama.cpp/whisper.cpp build, download its
own copy of the same multi-gigabyte weights, and load a duplicate model into memory alongside the
other app's copy — wasteful on a memory- and storage-constrained device, and a second place every
model-related fix and license concern has to be kept in sync.

RFC-0021 already makes this tractable: `ModelAdapter` is deliberately symmetric between local and
remote providers, carries no `session_id`, and keeps acquisition (`ModelRuntime`, RFC-0022)
separate from inference. An adapter that calls a sibling app over loopback HTTP is a third
implementation of that same interface, not a redesign of it. RFC-0022's model storage is already
scoped to **user**, not per-project — the right scope for something serving multiple apps.

This RFC does not itself rewrite RFC-0050 or RFC-0022, both of which currently describe model
loading as happening inside the Android app's own process. If this RFC is accepted, those
passages need superseding language in a follow-up commit, per CLAUDE.md's RFC-update process —
recorded here so it isn't lost.

## Goals

1. Define Aidos Engine as a standalone app and its relationship to Aidos Agent and other clients.
2. Keep Aidos Engine Core separate from its Android hosting, mirroring the runtime/app split
   Aidos Agent already has, so the core is not Android-specific by construction.
3. Define the transport: a Binder handshake plus loopback HTTP, OpenAI-compatible wire schema.
4. Define Aidos SDK as the one client-side implementation of that handshake and transport, so no
   consuming app hand-rolls it.
5. Define the v1 trust model (signature-only) and what changes to broaden it later.
6. Define the version and capability-negotiation contract between Engine and its clients.
7. Define concurrency and memory policy across multiple loaded models and multiple callers.
8. Define graceful degradation when Engine is absent or incompatible.
9. Define Engine's ownership of its own UI (model selection, download, licensing).

## Non-goals

This RFC does not define sessions, projects, capabilities, or the execution graph — those stay
in Aidos Agent, unchanged, per RFC-0008/0018/0019/0055.

This RFC does not define vision/multimodal endpoints. LLM, embedding, and STT ship first; vision
is Future Work once model support and the memory budget below are validated.

This RFC does not change DESKTOP or HEADLESS_SERVER behaviour — RFC-0055 already covers those.
This is specifically the MOBILE, app-to-app case RFC-0055 did not have, because it assumed
MOBILE has exactly one frontend.

This RFC does not open Aidos Engine to differently-signed (third-party) clients. v1 is
signature-only; opening it further is Future Work and is not designed here.

This RFC does not define remote or LAN exposure of the Engine. The bound port is loopback-only.

This RFC does not define Aidos SDK's publishing mechanism (Maven coordinates, release cadence,
distribution channel). Only that it exists as the single client-side implementation of the
handshake and transport — packaging is an implementation detail.

## Design

### Two apps, one device

- **Aidos Agent** (`fi.italeino.aidos`) keeps everything RFC-0050 describes except model loading:
  sessions, projects, capabilities, executor, agent loop, tools, knowledge, memory, settings,
  identity, vault. It becomes a *client* of local inference the same way it is already a client
  of a remote provider (RFC-0021/0023).
- **Aidos Engine** (`fi.italeino.aidos.engine`) hosts model loading and serving — llama.cpp for
  LLM and embedding, whisper.cpp for STT (D27/D28) — behind an HTTP server bound to `127.0.0.1`.
  It runs its own foreground service, for the same reasons RFC-0050 gives for Aidos Agent's.
- Any number of client apps, including Aidos Agent, may address one Engine instance through
  **Aidos SDK** (below). This is the requirement driving the split: one loaded model set, several
  apps.

### Core and app, mirrored from Aidos itself

Aidos's own architecture keeps a headless core (`agent/`: kernel and services) separate from
its Android hosting (`agent/androidapp`), precisely so the same core can be hosted differently
per platform (RFC-0050, RFC-0055). Aidos Engine repeats that shape rather than folding model
serving straight into an Android app module:

- **Aidos Engine Core** is a platform-agnostic KMP module — `modelruntime`, `models`,
  `downloads`, `huggingface`, `cookbook`, and the STT/TTS provider interfaces (`SttProvider`,
  `TtsProvider` — the model-serving half of the original `voice` module; see below), moved out of
  `agent/` into their own `engine/` Gradle project rather than merged into an Android module. It
  has no Android or UI dependency,
  and it exposes model loading, inference, and the admission/eviction policy through one
  interface, the same way `RuntimeClient` is the seam RFC-0052 defines for the main runtime.
- **Aidos Engine** (the Android app) hosts Aidos Engine Core in-process inside its foreground
  service — the same relationship RFC-0050 describes between Aidos Agent and the main runtime —
  and additionally runs the loopback HTTP server and the handshake Binder surface *on top of*
  the core, to expose it to other apps. Aidos Engine's own UI (cookbook, downloads, ToS) calls
  the core directly in-process; it does not round-trip through its own HTTP server to talk to
  itself.
- This is a deliberate, narrow exception to RFC-0055's MOBILE assumption that "the runtime is
  in-process... because MOBILE has exactly one frontend by construction." That premise holds for
  Aidos Agent, which still has exactly one frontend. It does not hold for Aidos Engine, whose
  entire purpose is serving several independent frontends on one device — so on MOBILE, Aidos
  Engine legitimately takes the *daemon* shape RFC-0055 otherwise reserves for DESKTOP and
  HEADLESS_SERVER, scoped only to this one component. Keeping Engine Core platform-agnostic is
  what makes that possible without contradicting RFC-0055 for everything else: the exception is
  in how Aidos Engine is hosted, not in what MOBILE means for Aidos Agent.
- Keeping the core separate also means a future non-Android host for Aidos Engine — a desktop
  build, for instance — would expose the same core behind a Unix socket instead of loopback HTTP,
  exactly as RFC-0055 already does for the main runtime's DESKTOP daemon. Nothing here commits to
  building that; it falls out of the split for free if it's ever needed (Future Work).
- Concretely, the repository is one monorepo with the boundary drawn at the directory level:
  `agent/` (Aidos Agent, formerly `runtime/`), `engine/` (Aidos Engine Core plus its own
  `androidapp`), and `sdk/` (Aidos SDK) are three independent Gradle projects side by side.
  `sdk/` is consumed by `agent/` (and any other client) as a library, not a source dependency,
  matching that the two apps only ever talk over the transport this RFC defines.
- `agent/` and `engine/` share exactly one module at the source level: `kernel/`, pulled out as
  its own top-level directory and included by path (`project(":kernel").projectDir =
  file("../kernel")`) from both `agent/settings.gradle.kts` and `engine/settings.gradle.kts`,
  rather than copied into either. This is not a violation of "no shared build graph between the
  two apps" — it is the specific exception the architecture already names: kernel is frozen
  contract types with no implementation, and every service depends on it while it depends on
  none (ARCHITECTURE.md). `modelruntime` needs `ModelAdapter`/`ModelRequest`/`ModelResponse` to
  be the same type Aidos SDK deserializes into on the Agent side; a vendored copy would let the
  two silently drift. `agent/` and `engine/` still share no *service* module, and neither depends
  on the other's build.
- The original `voice` module does not move to `engine/` as one piece, and an earlier draft of
  this RFC was wrong to list it as one of the six that do. Only its model-serving surface —
  `SttProvider`/`TtsProvider` — is Engine's concern. `SpokenSummaryGenerator` (renders a Run
  summary as spoken text, explicitly "no model call") and `VoiceApprovalHandler` (voice-based
  capability approval, RFC-0057) both depend directly on `agent/androidapp`'s execution-graph
  types and on `agent/settings`, which is `agent/`'s own application code and a *service*
  module — not something `engine/` can depend on under this RFC's boundary at all, unlike
  `kernel`. They stay in `agent/voice`; `engine/voice` keeps only the provider interfaces.

### Aidos SDK: one client implementation

Every consuming app needs the same handshake, token handling, and HTTP client. Leaving that to
each app to reimplement is exactly the risk this RFC otherwise removes — N slightly different
copies of one wire protocol drifting out of sync is the same failure mode as N bundled copies of
llama.cpp, just moved to the client side. **Aidos SDK** is a small Android library — not a KMP
module; it has no reason to run anywhere Aidos Engine doesn't — that owns:

- The handshake call and the Binder plumbing behind it.
- Token storage and refresh, including re-handshaking after an Engine restart invalidates a token.
- The loopback HTTP client for `/v1/chat/completions`, `/v1/embeddings`, and
  `/v1/audio/transcriptions`.
- Reading `apiVersion` and `capabilities` from the handshake response and exposing them as a
  typed compatibility check, rather than each app parsing the response itself.
- Detecting "Engine not installed" or "handshake failed" and surfacing it as one signal a caller
  reacts to (see Degradation, below), including an optional deep link to install or open Aidos
  Engine.

Aidos SDK exposes this behind `ModelAdapter` implementations for LLM, embedding, and STT
(RFC-0021), so a consuming app's routing layer treats Aidos Engine exactly like any other
provider — the same local/remote symmetry argument Motivation makes for RFC-0021 generally. Aidos
Agent is Aidos SDK's first consumer, not a special case baked into it: any other app on the
device links the same library and gets identical behaviour, which is the actual "multiple apps,
one inference engine" requirement this RFC exists to satisfy.

Aidos SDK is versioned and distributed independently of both Aidos Agent and Aidos Engine, so a
handshake or wire change ships as one SDK release every consuming app picks up, rather than as
protocol code hand-maintained per app.

### Handshake and transport

- Bulk traffic — generate, embed, transcribe — is plain HTTP to the loopback port, using an
  **OpenAI-compatible schema**: `/v1/chat/completions` (streamed via SSE), `/v1/embeddings`,
  `/v1/audio/transcriptions`. This reuses the wire shape RFC-0021/0023 already need for remote
  providers, and avoids Binder's transaction-size limits on long prompts and streaming output.
- A bare TCP socket carries no caller identity — unlike Binder, where Android tells the callee the
  caller's UID and signature for free — so it cannot itself be gated by the OS permission system.
  Aidos Engine therefore exposes exactly one Binder surface, a **handshake**, declared with
  `protectionLevel="signature"`. Given the caller's OS-verified identity, it returns
  `{port, token, apiVersion, capabilities}`. Every HTTP request after that presents the token as
  a bearer credential. This is the direct analogue of RFC-0055's desktop connection token —
  "written to a file readable only by the user" — adapted for Android, where no shared file
  exists between two sandboxed apps to carry that secret.
- The port is ephemeral, chosen at Engine startup, and fetched fresh on each cold connect — never
  hardcoded, never assumed stable across Engine restarts.

### Trust model (v1: signature-only)

The handshake's `signature` protection level is a hard, OS-enforced gate: only apps signed with
the same key as Aidos Engine can complete a handshake at all. Broadening this to differently-signed
clients needs a new authorization step in front of the handshake (consent UI, a capability grant
per caller, plausibly an extension of RFC-0018) — not a transport change. Deferred; see Future
Work.

### Version and capability contract

Two mechanisms, doing different jobs:

- **`apiVersion`** — a strict integer, incremented on any breaking wire-format change, the same
  role RFC-0052 gives `RuntimeClient`'s API version. A client whose required major version does
  not match the Engine's treats it as incompatible and degrades (below) rather than guessing.
- **`capabilities`** — returned in the same handshake response: which endpoints exist today
  (`chat.completions`, `embeddings`, `audio.transcriptions`, later `vision`) and which model
  classes are currently loaded or available. This is what lets a client built after vision ships
  still work against an older Engine that only has LLM/embedding/STT, and lets a client that
  never calls STT ignore whether it's present.

Version alone would break on every new feature; capability negotiation alone would let a client
attempt a wire-incompatible call. Both are needed together.

### Concurrency and memory policy

- Engine loads a model on first request for it; nothing is preloaded at startup.
- Multiple models (LLM, embedding, STT — later vision) may stay resident simultaneously if the
  device's available memory allows it. When it doesn't, Engine evicts the least-recently-used
  resident model to admit the one just requested. This extends RFC-0022's admission queue from
  one project's models to every modality and every client on the device.
- Concurrent requests are served in parallel when the resident working set leaves enough headroom
  for concurrent execution; when it doesn't, they're serialized through a request queue instead of
  rejected. This is internal scheduling policy, invisible on the wire — a client under contention
  just sees a slower response, never an error caused by another client's load.

### Degradation when Engine is unavailable

Aidos SDK surfaces "Engine not installed" and "handshake or version negotiation fails" as one
signal — **local inference unavailable** — which every consuming app, Aidos Agent included,
handles the same way rather than each inventing its own detection. This is exactly how RFC-0021
already treats "no local GGUF model downloaded" today: routing falls back to a configured,
approved remote provider (RFC-0023) if one exists; otherwise the affected capability is reported
unavailable when the project opens (RFC-0049), not mid-Run. Engine's absence removes local
inference, not the app — offline-first's actual guarantee is unaffected, since a project with no
remote provider configured already can't do a model-needing step without a downloaded model.

### Engine's own UI

Aidos Engine ships a full UI, not a headless service: the model cookbook/browser (RFC-0022),
download management and progress, storage/quota, per-model license and terms-of-service
acceptance, and current model status. It can be installed and used on its own, independent of any
particular client, the way Ollama is independent of any one chat frontend. Client apps never
render download or licensing UI themselves; they either use a model the capability set already
reports as available, or deep-link into Aidos Engine's own screens to acquire one. Licensing
acceptance lives in exactly one place.

## Data Model

Handshake response:

```
HandshakeResponse {
  port: Int
  token: String              # bearer credential, short-lived, single handshake
  apiVersion: Int             # strict wire-compat version
  capabilities: {
    endpoints: [String]       # e.g. ["chat.completions", "embeddings", "audio.transcriptions"]
    models: [ModelStatus]     # resident now, and available-but-unloaded
  }
}
```

No new persistent schema beyond RFC-0022's existing model storage, relocated from Agent's storage
to Engine's with the same shape. Admission-queue and eviction state are runtime-only, matching
RFC-0055's lock file being "transient state on disk" rather than a durable row.

## Security

- The signature-level handshake permission is the only OS-enforced boundary in this design. The
  loopback socket itself is unauthenticated transport — any process on the device, not just
  signed siblings, can open a TCP connection to a discovered loopback port — so the bearer-token
  check on every HTTP request is load-bearing and must never be skipped, including for same-device
  convenience during development.
- Tokens are short-lived and scoped to one handshake. A client reconnecting after an Engine
  restart re-handshakes; it does not reuse a stale token.
- Engine's foreground-service notification states that Aidos Engine is running and nothing more —
  it must not name loaded model content or which client app triggered a request, which would leak
  cross-app usage into the user's notification shade.

## MVP

1. Aidos Engine Core as its own KMP module: llama.cpp-backed LLM and embedding inference,
   whisper.cpp-backed STT, the admission queue and LRU eviction policy, and load-on-demand model
   management — no Android dependency.
2. Aidos Engine app: hosts Engine Core in-process in a foreground service; exposes it via
   `/v1/chat/completions`, `/v1/embeddings`, and `/v1/audio/transcriptions` over loopback HTTP.
3. Signature-`protectionLevel` handshake surface returning `{port, token, apiVersion, capabilities}`.
4. Parallel execution across concurrent callers when the resident working set leaves memory
   headroom; a serialized queue otherwise.
5. Aidos SDK: the handshake client, token/session management, the loopback HTTP client, and
   `ModelAdapter` implementations for LLM, embedding, and STT, exposing "Engine unavailable" as
   one signal.
6. Aidos Agent as Aidos SDK's first consumer, falling back to a remote provider or reporting
   unavailability when Aidos SDK reports the handshake failed or `apiVersion` is incompatible.
7. Aidos Engine's own UI: cookbook/model browser, download manager, license/ToS acceptance,
   active-model status — calling Engine Core in-process, not through its own HTTP server.

Not in MVP: vision/multimodal endpoints, third-party (cross-signature) client trust, any remote or
LAN exposure of the Engine port.

## Future Work

- Vision endpoints: multimodal `chat.completions` with image content parts, once model support and
  the memory budget above are validated against real devices.
- Opening the handshake to differently-signed clients: per-caller consent UI, a capability-model
  extension (RFC-0018) for per-app grants, usage/rate limiting per client.
- A possible convergence with RFC-0055's "paired remote runtime" Future Work, if a phone's Aidos
  Engine is ever addressed from a desktop runtime rather than only from apps on the same device.
