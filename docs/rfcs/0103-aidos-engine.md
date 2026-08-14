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
9. Define Aidos Engine's own UI: its screens, and what it persists to support them.
10. Define where Aidos Engine's credentials (starting with a Hugging Face token) live, and name
    the direction for whether remote-provider execution eventually moves into Aidos Engine too.

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

This RFC names the direction for remote-provider execution moving into Aidos Engine but does not
design it: no wire format per provider, no egress-policy enforcement point, no multi-app
credential-sharing semantics. That is Future Work, deliberately scoped out of MVP.

This RFC does not amend RFC-0050 to add Aidos Agent's model-selection screen, which the direction
above requires exist somewhere. That amendment is separate follow-up work, the same category of
debt Motivation already records for RFC-0050/0022's in-process-hosting language.

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

### Aidos Engine's own UI

Aidos Engine ships a full UI, not a headless service. It can be installed and used on its own,
independent of any particular client, the way Ollama is independent of any one chat frontend.
Client apps never render download or licensing UI themselves; they either use a model the
capability set already reports as available, or deep-link into Aidos Engine's own screens to
acquire one — so `model/{modelId}` must be a real, deep-linkable destination, not just reachable
from Home. Screens are derived from the actual questions someone opening Engine has, the same
method RFC-0050 uses for Aidos Agent, and the gesture grammar is inherited rather than reinvented:
horizontal swipe between peers, vertical scroll through a list, tap goes deeper.

**1 · Home — status and cookbook, side by side.** Two panes, swiped between, status first —
mirroring Agent's Inbox-before-Projects: *"what's happening"* beats *"what's possible"*.

*Status pane* — what Engine is doing right now:

```
  Resident now
    ● Qwen2.5 3B Q4        loaded 12m ago · Aidos Agent
    ● nomic-embed          loaded 12m ago · Aidos Agent

  Memory      3.2 GB / 4.0 GB budget
  Connected   Aidos Agent

  ⋯ 1 download in progress
```

*Cookbook pane* — the RFC-0022 cookbook rendered as that RFC already designed it: label, kind,
size, verdict, nothing invented here:

```
  Qwen2.5 3B · Q4_K_M · 2.0 GB          RUNS_WELL
  Llama 3.1 8B · Q4                     WILL_NOT_FIT
  Whisper base                          RUNS_WELL
```

**2 · Model detail / acquire.** Tap a catalogue entry: the per-context-length fit table RFC-0022
already specifies (4k/16k/32k, verdict per row), and the model's license/terms-of-service **shown
at the point of deciding to download**, not as a blanket EULA at first launch — consistent with
RFC-0022's "never automatic." Download is disabled until this specific model's license is
accepted; acceptance is recorded once per model+version and re-shown only if the license text
changes. Progress is resumable, per RFC-0022.

If the selected model needs Hugging Face authentication (gated repositories), the acquire flow
prompts for an HF token inline, right here, rather than gating the whole app behind a
sign-in-first screen — the same "ask when it's actually needed" rule as the license itself.

**3 · Storage.** RFC-0022's accounting table, verbatim — that RFC already designed the content:

```
  Models · 6.2 GB of 11.4 GB free

    Qwen2.5 3B Q4      2.0 GB    used 2h ago
    Whisper base       0.3 GB    used yesterday
    Llama 3.1 8B Q4    3.4 GB    never run · will not fit
```

Tap a row → remove. Manual only — RFC-0022 is explicit that Engine never deletes weights on its
own to make room.

**4 · Connected apps.** Every request into Engine already carries the bearer token minted for
that caller at handshake time — so per-app attribution costs nothing new to add, only something
to tally instead of discard. The display name is resolved via `PackageManager` from the calling
package the signature-permission handshake already verified, not self-reported by the client (a
self-reported name could claim to be anything; the verified package identity cannot).

```
  Aidos Agent                    connected

    Requests        142 (this session)
      chat.completions   118
      embeddings          24
    Last active     2m ago
```

Session-scoped counts (since Engine last started) are close to free — a counter keyed by client
token, incremented in the dispatch path. Persisted history across restarts is not: Engine owns no
storage that survives a restart in this RFC's MVP (see Storage, below), so "usage over the last
week" is Future Work, not something to bundle in now.

**5 · Settings.** Credential management — HF token entry/status/clear now; the natural home for
provider credentials generally if Aidos Engine later executes remote-provider calls (see "Remote
providers through Aidos Engine," below). Nothing else lives here in v1: no account, no sync, no
per-app trust configuration (the trust model is signature-only and not user-configurable — Trust
model, above).

**Deliberately absent**, mirroring RFC-0050's own table:

| Not built | Why |
|---|---|
| Chat / prompt surface | Engine serves, it doesn't converse — every client app's job, not Engine's |
| Account, login | No account exists anywhere in this product (RFC-0046) |
| Sync, cross-device usage history | D16 — nothing syncs, same as Aidos Agent |
| Arbitrary model import (raw GGUF upload) | Bypasses the cookbook's verdict system entirely; Future Work, not a v1 screen |
| Persisted per-app usage history | Requires storage Engine doesn't have in v1 (Storage, below); session-scoped only for now |

**Notifications.** The same three kinds RFC-0050 settles for Aidos Agent (Ongoing / Needs you /
Terminal), but Engine's Ongoing notification is bound by the Security section below: states that
Engine is running, never which model or which app. A download in progress is a second
legitimate Ongoing-class notification — its content is the user's own decision to watch, not
cross-app leakage.

### Vault: Aidos Engine's own credential store

Engine needs to hold at least one secret — a Hugging Face access token, required for gated-model
downloads — and cannot reach `agent/vault`, which stays in `agent/` and is Aidos Agent's own
application storage, unreachable across the app boundary by construction (the same reasoning that
keeps `agent/settings` and `agent/androidapp` out of `engine/`'s dependency graph). So Engine needs
a small vault of its own, not a shared one.

**Scoped as a credential store, not a general secrets service**: Android Keystore-backed encrypted
storage (`EncryptedSharedPreferences` or equivalent), holding named credentials with no
interpretation of what they're for beyond a label and the provider they authenticate. This shape —
generic credential slots, not an HF-specific field — is deliberate: if Aidos Engine later executes
remote-provider calls (Future Work, below), the same store holds an Anthropic or OpenAI key
without redesign. What ships in v1 is one credential type wired up (HF token); the store itself is
not v1-shaped.

Nothing here changes the trust model in Security, above — this is a secret Engine holds on the
user's behalf for Engine's own acquisition flow, not a capability grant to a calling app.

### Storage: what Aidos Engine persists

Beyond the model weights themselves (RFC-0022's existing storage, relocated per Two apps, above),
Engine's UI needs a small amount of its own persistent state that has nowhere else to live now
that it is a separate app:

- **License/ToS acceptance records** — per model, per version (Aidos Engine's own UI, above) — so
  a re-download or an app restart doesn't re-prompt for something already agreed to.
- **The vault**, above.

**Deliberately not persisted in v1**: per-app usage history (session-scoped counters only, reset
on restart — Connected apps, above); cached cookbook verdicts (cheap enough to recompute against
the live device profile each time, per RFC-0022, so a cache would be an optimization with nothing
yet to optimize).

This is genuinely new scope this RFC did not previously name: Engine was designed around model
storage (RFC-0022) and in-memory admission/eviction state (Data Model, above), neither of which is
"Engine has its own small database." It does now — minimal, but real, and worth stating rather
than discovering during implementation.

### Remote providers through Aidos Engine

RFC-0021 already treats local and remote providers as symmetric behind one `ModelAdapter`
interface. This RFC, as originally written, only let Aidos Engine execute the local half —
Aidos Agent kept its own direct HTTP clients to Anthropic, OpenAI, and other remote providers
(RFC-0021/0023), unchanged. That leaves the same duplication problem this RFC exists to solve,
just moved to the remote case: every other app on the device that wants remote-model access has to
reimplement its own provider HTTP clients, its own API-key storage, its own egress logging —
exactly the N-copies failure mode Motivation names for local weights, recurring for remote calls
instead.

**The direction: Aidos Engine executes both local and remote model calls; Aidos Agent decides.**
RFC-0023's privacy approval — "this will send project data to OpenAI's servers" — is tied to
session, Run, and project context that lives entirely in Aidos Agent's capability and trust
machinery (RFC-0018, RFC-0027), which this RFC's Non-goals correctly keep out of Engine. That does
not change: the approval happens in Aidos Agent, before Aidos Engine is ever asked to make the
call. What moves to Engine is execution and credential custody — the vault above becomes the one
place a remote provider's API key lives, and the one place the outbound HTTPS call is actually
made, for whichever client app asked (with authority already established by Aidos Agent's own
approval flow, not re-derived by Engine).

**Aidos Agent's direct-remote path does not go away.** Degradation, above, already requires it: a
project can fall back to a configured remote provider when Aidos Engine is unavailable, which
means Aidos Agent needs *some* direct remote capability regardless of what Aidos Engine can do.
Aidos Agent's model-selection surface (a screen this RFC does not itself design — RFC-0050 needs a
follow-up amendment to add one, the same debt already recorded in Motivation for RFC-0050/0022)
should offer Aidos Engine as the default, first option, with directly-configured remote providers
as a secondary, explicit alternative — not a forced single path.

This is Future Work, not MVP: the vault is shaped to hold provider credentials generically (Vault,
above) precisely so this is additive later rather than a redesign, but actually wiring a
remote-provider `ModelAdapter` through Aidos Engine — request/response translation per provider,
egress policy enforcement at the point of execution, credential-sharing semantics across multiple
client apps using the same stored key — is real, separately-scoped work. Naming the direction now
is what keeps the vault and the transport from needing to change shape when that work starts.

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

Aidos Engine's own small persistent state (Storage, above):

```
VaultEntry {
  label: String              # e.g. "Hugging Face"
  provider: String           # opaque to Engine beyond routing which acquire/execute flow uses it
  secret: ByteArray          # Keystore-encrypted at rest
  createdAt: Instant
}

LicenseAcceptance {
  modelId: String
  modelVersion: String
  acceptedAt: Instant
  licenseTextDigest: String  # re-prompt only if this changes
}
```

Per-app usage counters (Connected apps, above) are in-memory only in v1 — keyed by client token,
not persisted, reset on Engine restart. No schema for them here because there is deliberately
nothing to persist yet.

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
- The vault (above) is Android-Keystore-backed encrypted storage, app-private to Aidos Engine like
  everything else in this design — no client app can read it, including over the loopback
  transport, since nothing in the wire protocol exposes stored credentials, only their effects
  (an acquire flow that succeeds, a call that gets made). A compromised client app can ask Engine
  to *use* a credential it's authorized to trigger; it cannot read the credential itself.
- Connected Apps and per-app usage (above) are shown only in Aidos Engine's own UI, which only the
  device owner can open — this is not the same surface the foreground-notification restriction
  governs, and showing full per-app detail there does not reintroduce the cross-app leakage that
  restriction exists to prevent.

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
7. Aidos Engine's own UI, calling Engine Core in-process, not through its own HTTP server: Home
   (status + cookbook panes), Model detail/acquire (license acceptance, HF token prompt when
   needed), Storage, Connected apps (session-scoped usage), Settings (credential management).
8. The vault: Keystore-backed credential storage, generic enough to hold any provider's
   credential, with the Hugging Face token as the one credential type actually wired to a flow
   (gated-model acquisition).
9. License-acceptance records, persisted per model+version, so acquisition doesn't re-prompt.

Not in MVP: vision/multimodal endpoints, third-party (cross-signature) client trust, any remote or
LAN exposure of the Engine port, remote-provider execution through Aidos Engine (direction named,
not built), persisted per-app usage history.

## Future Work

- Vision endpoints: multimodal `chat.completions` with image content parts, once model support and
  the memory budget above are validated against real devices.
- Opening the handshake to differently-signed clients: per-caller consent UI, a capability-model
  extension (RFC-0018) for per-app grants, usage/rate limiting per client.
- A possible convergence with RFC-0055's "paired remote runtime" Future Work, if a phone's Aidos
  Engine is ever addressed from a desktop runtime rather than only from apps on the same device.
- Remote-provider execution through Aidos Engine (Remote providers through Aidos Engine, above):
  per-provider request/response translation, egress-policy enforcement at the point of execution,
  and credential-sharing semantics when multiple client apps use the same stored key.
- Persisted, cross-restart per-app usage history — requires Aidos Engine to own more storage than
  the license-acceptance and vault records this RFC's MVP gives it.
- Aidos Agent's model-selection screen (RFC-0050 amendment): Aidos Engine as default, directly-
  configured remote providers as an explicit secondary path.
