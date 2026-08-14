# RFC-0103: Aidos Engine — Shared Local Inference Service

Status: Draft 2026-08-14

## Abstract

This RFC splits local model inference out of the Aidos Android app into a standalone app, **Aidos
Engine**, so that multiple independent apps on one device — Aidos Agent among them — can share
one loaded set of models instead of each bundling and loading its own copy. It defines the
app boundary, the loopback transport and its authentication handshake, the version/capability
contract, and the memory and concurrency policy for serving several modalities to several
clients at once.

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
2. Define the transport: a Binder handshake plus loopback HTTP, OpenAI-compatible wire schema.
3. Define the v1 trust model (signature-only) and what changes to broaden it later.
4. Define the version and capability-negotiation contract between Engine and its clients.
5. Define concurrency and memory policy across multiple loaded models and multiple callers.
6. Define graceful degradation when Engine is absent or incompatible.
7. Define Engine's ownership of its own UI (model selection, download, licensing).

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

## Design

### Two apps, one device

- **Aidos Agent** (`fi.italeino.aidos`) keeps everything RFC-0050 describes except model loading:
  sessions, projects, capabilities, executor, agent loop, tools, knowledge, memory, settings,
  identity, vault. It becomes a *client* of local inference the same way it is already a client
  of a remote provider (RFC-0021/0023).
- **Aidos Engine** (`fi.italeino.aidos.engine`) hosts `modelruntime`, `models`, `downloads`,
  `huggingface`, `cookbook`, and `voice` (STT) — llama.cpp for LLM and embedding, whisper.cpp for
  STT (D27/D28), behind an HTTP server bound to `127.0.0.1`. It runs its own foreground service,
  for the same reasons RFC-0050 gives for Aidos Agent's.
- Any number of client apps, including Aidos Agent, may address one Engine instance. This is the
  requirement driving the split: one loaded model set, several apps.

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

Aidos Agent (and any other client) treats "Engine not installed" and "handshake or version
negotiation fails" as **local inference unavailable**, not as a hard failure — exactly how RFC-0021
already treats "no local GGUF model downloaded" today. Routing falls back to a configured, approved
remote provider (RFC-0023) if one exists; otherwise the affected capability is reported unavailable
when the project opens (RFC-0049), not mid-Run. Engine's absence removes local inference, not the
app — offline-first's actual guarantee is unaffected, since a project with no remote provider
configured already can't do a model-needing step without a downloaded model.

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

1. Aidos Engine app: llama.cpp-backed `/v1/chat/completions` and `/v1/embeddings`, whisper.cpp-backed
   `/v1/audio/transcriptions`, all over loopback HTTP.
2. Signature-`protectionLevel` handshake surface returning `{port, token, apiVersion, capabilities}`.
3. Load-on-demand with LRU eviction across the three modalities under one shared memory budget;
   parallel execution when the budget allows it, a serialized queue otherwise.
4. Aidos Agent as first client: `ModelAdapter` implementations for LLM, embedding, and STT that
   speak this wire format, falling back to a remote provider or reporting unavailability when the
   handshake fails or `apiVersion` is incompatible.
5. Aidos Engine's own UI: cookbook/model browser, download manager, license/ToS acceptance,
   active-model status.

Not in MVP: vision/multimodal endpoints, third-party (cross-signature) client trust, any remote or
LAN exposure of the Engine port.

## Future Work

- Vision endpoints: multimodal `chat.completions` with image content parts, once model support and
  the memory budget above are validated against real devices.
- Opening the handshake to differently-signed clients: per-caller consent UI, a capability-model
  extension (RFC-0018) for per-app grants, usage/rate limiting per client.
- A possible convergence with RFC-0055's "paired remote runtime" Future Work, if a phone's Aidos
  Engine is ever addressed from a desktop runtime rather than only from apps on the same device.
