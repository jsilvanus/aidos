# RFC-0021: Model Providers

Status: Accepted 2026-08-03

## Abstract

A **provider** is a source of models; an **adapter** is the code that speaks to one. This RFC
defines `ModelAdapter` — the single interface every provider implements, local or remote — and
the provider-neutral envelope that keeps the agent loop from knowing which kind it is talking to.
That symmetry is the whole point: it is what makes offline a first-class path rather than a
degraded one.

## Motivation

Aidos must run against a local GGUF model on a phone in airplane mode and against a remote API on
a desktop, with the *same loop above the boundary*. If the agent loop branches on "is this
local", the offline path becomes a second-class citizen that rots — it gets the fixes late, the
tests rarely, and the design attention never.

The previous version of this RFC did not provide that. Its `Provider` interface took a
`session_id` on every call, mixed model *acquisition* (`download_model`) into the inference
interface, exposed `get_usage()` "for billing", and proposed provider plugins plus a marketplace
with star ratings and download counts. It also predates RFC-0008's tool-call envelope, so the
hardest problem in the subsystem — normalising incompatible function-calling formats, including
models that have none — is absent from it entirely.

## Goals

1. Define `ModelAdapter` and what every provider must supply.
2. Define the provider-neutral tool-call envelope, including models without native tool calling.
3. Define where credentials live and how availability is reported.
4. State how a remote call interacts with taint and egress.
5. State what a provider may *not* do.

## Non-goals

This RFC does not define model *selection* — which provider is used for a given call is
RFC-0020's routing, and it is user-owned policy.

This RFC does not define model acquisition, storage, or the cookbook (RFC-0022).

This RFC does not define a plugin SPI for third-party providers, a provider marketplace, or
provider ratings. See "Third-party providers" below for what that means for today's code.

This RFC does not define pricing or billing. Cost units exist for **budget enforcement only**
(RFC-0028); Aidos never issues an invoice, never reconciles against a provider's statement, and
its cost numbers are labelled estimates.

## Design

### One interface

```kotlin
interface ModelAdapter {
    val providerId: String
    val modelId: String
    val modelVersion: String
    val contextWindow: Int
    val isLocal: Boolean

    fun supportsNativeToolCalls(): Boolean

    suspend fun invoke(request: ModelRequest): Result<ModelResponse>
}
```

`runtime/kernel/` is canonical; this is here so the RFC reads on its own.

Three things are deliberately **absent**, and each was present in the previous version:

- **No `session_id`.** An adapter does not need to know which session is calling, and passing it
  invites adapters to make their own policy decisions. Authority is resolved before the call, by
  the broker (RFC-0030) — the same argument as `Tool` not taking one.
- **No `download_model`.** Acquisition is `ModelRuntime` (RFC-0022), at user scope, through an
  admission queue. Conflating "fetch four gigabytes" with "answer this prompt" put a
  progress-callback parameter on an inference interface.
- **No `get_usage()` for billing.** Token counts come back on `ModelResponse` and feed the budget
  ledger. There is no billing.

### The tool-call envelope, and models that have none

Every provider expresses tool calling differently, and **many local GGUF models express it not at
all**. The envelope is what absorbs that difference:

```
provider's native format ──▶ adapter ──▶ ToolCall { callId, toolName, arguments, capabilityId }
                                              │
                                    the loop sees only this
```

An adapter for a model without native tool calling implements the identical interface using
**constrained decoding** — a grammar that permits only well-formed calls — or a documented text
protocol, and reports `supportsNativeToolCalls() = false`. Routing may prefer a native model when
one is available, but nothing above the adapter branches on it.

`ToolCall.rawText` is retained when parsing was heuristic. Parsing ambiguity is
security-relevant: a call the runtime *guessed* at is a call whose arguments may not be what the
model meant, and the audit record should be able to show what was actually emitted.

**Model output is `UNTRUSTED`** regardless of provider (RFC-0027). Local inference changes where
computation happens, not whether its output may be trusted with authority.

### Credentials

API keys live in `vault.db` (RFC-0035) and are fetched at call time. An adapter never holds a key
beyond the call, never logs one, and never puts one in an event, an audit row, an error message,
or a prompt.

A provider whose credential is missing is **unavailable, not broken**: it does not appear in
`AvailabilityReport`, and the model is never offered it (RFC-0049). The user is told a key is
needed at configuration time, not by a Run failing at step six.

### Availability, and what "offline" does to a provider

```
provider requires network  ∧  ¬networkAvailable   →  unavailable
provider is local          ∧  model not installed →  unavailable
provider is local          ∧  no foreground svc   →  parks (D24)
```

`RoutingDecision.UnavailableOffline(kind)` is a normal outcome on MOBILE, not an error
(RFC-0020). The user is told which model *kind* is missing, because that is what they can act on.

### A remote call is an egress

This is the part the previous version treated as a privacy dialog rather than as an effect.

Every remote invocation is `EffectKind.Egress` and passes through the broker like any other
effect. Consequences that follow automatically rather than needing per-provider handling:

- **Taint attenuates it.** A Run that has admitted `UNTRUSTED` content requires per-call approval
  before it may send anything off the device, naming the tainting source (D7). A remote call is
  precisely the moment that matters.
- **It is recorded.** `egress_records` captures what left, to whom, and under which capability.
- **Redaction applies** before transmission (RFC-0035, RFC-0042), and what was redacted is
  recorded.
- **Policy is user-owned.** Crossing the network boundary is never automatic. There is no
  fallback path in which a local model being unavailable causes the user's code to be uploaded.

Provider policy — which providers are enabled, retention claims, approved task classes — lives in
`aidos.toml` and the `settings` table (RFC-0010, RFC-0036). The previous version put it in a
`project/.aidos/config.json` that does not exist.

### User-registered endpoints

Not every model comes from a vendor Aidos ships an adapter for. A company running its own LLM
behind an OpenAI-compatible API is a common and entirely reasonable case, and it must not require
a code change.

A user may register an endpoint with: a **base URL**, a **model name** the endpoint expects, an
**API key** (into the vault), and a **label** they choose. The adapter is the generic
OpenAI-compatible one; nothing else about the system changes.

**Naming is the user's, and it is always visible.** `modelId` is what goes on the wire;
`displayLabel` is what the user typed and what every surface shows — the model picker, the Run
Summary, the approval card, the audit row. Someone with three endpoints called `gpt-4o` behind
different gateways needs to see *"Work — internal gateway"* rather than three identical rows, and
they need to see it at the moment they approve an egress, not in a settings screen.

**A self-hosted endpoint on your own network is still egress.** It is not the public internet,
and the user may reasonably care about that difference — but the data still leaves the device, so
it is `EffectKind.Egress`, it is recorded, and taint attenuates it. What a LAN endpoint changes
is the user's *policy*, not the mechanism: they may sensibly approve a class of egress to their
own gateway that they would refuse to a vendor. Egress policy is per destination (RFC-0042), so
this is expressible without a special case.

It also requires network, so a self-hosted endpoint is **unavailable offline** like any other
remote provider. Being on the same building's Wi-Fi does not make it work on a train.

### Endpoint health is learned, not polled

RFC-0049 promises that unavailable things are never offered, and above this RFC says a missing
credential is discovered at configuration time rather than by a Run failing at step six. Both
promises are empty unless something actually establishes whether an endpoint answers.

**Verified once, at registration.** A registration that cannot reach its endpoint is reported
immediately, while the user is looking at the form and can fix it.

**Downgraded on failure during use.** A call returning 401, 404, or timing out marks the endpoint
unavailable and tells the user *why* — an expired key reads as *"your key for Work — internal
gateway is no longer accepted"*, not as a failed Run. Credential expiry is therefore the same
mechanism as any other health failure and needs no separate machinery.

**Re-verified when asked, or when the network returns** after a period offline.

**Never polled in the background.** A periodic reachability check is battery spent on a question
nobody asked, and on a device that is offline by default it would be answering "no" almost every
time. Health is a fact learned from use, not a metric maintained.

### Versioning

`modelVersion` is recorded on **every attempt** (`attempts.model_version`). Providers change
model behaviour behind a stable name; without the recorded version, "why did this Run behave
differently in March" has no answer. This is also why deterministic replay is not a goal (D1) —
the version is captured for *explanation*, not reproduction.

An adapter whose provider reports no version records the date-stamped identifier it was given, and
says it is imprecise rather than inventing precision.

### Third-party providers

There is no provider SPI, no plugin loading, and no marketplace. D18 puts the plugin host beyond
v1; RFC-0099 excludes a marketplace explicitly.

**What that means for code written now** — because "far future" is not the same as "no
consequences today":

`ModelAdapter` is an **internal interface**. It may change shape in any release without
ceremony, because everything implementing it ships in this repository. If a third-party provider
mechanism ever arrives, it should be a *published SPI that wraps* `ModelAdapter`, not
`ModelAdapter` itself promoted to public API — an interface designed for internal use and then
frozen for external consumers is how a codebase acquires an abstraction it cannot fix.

Concretely: do not add versioning, stability annotations, or defensive extension points to
`ModelAdapter` in anticipation. Keep it small enough to wrap later.

## Data Model

`model_catalog` and `installed_models` in `schema/user.sql`; per-attempt provider, model, version,
token counts, and cost units on `attempts` in `schema/project.sql`. Provider credentials are in
`vault.db`. No provider table exists or is needed — a provider is code plus configuration, not a
row.

## Security

1. **Credentials never leave the vault** except into the request that needs them.
2. **Every remote call is an egress** and is subject to taint attenuation, redaction, approval,
   and recording. There is no adapter-level bypass.
3. **Model output is `UNTRUSTED`**, and a provider claiming otherwise does not change that.
4. **Adapters do not resolve authority.** No `session_id`, no capability checks inside an
   adapter, no policy decisions. An adapter that could decide what it is allowed to do would be a
   second enforcement point, and the second one is always the weaker.
5. **A provider's own retention policy is a claim, not a control.** Aidos records what the
   provider states so the user can weigh it, and defends by redacting and by requiring approval —
   not by trusting the statement.

## MVP

1. `ModelAdapter` with one remote provider adapter, end to end (M14).
2. The envelope, with normalisation from that provider's native tool format.
3. Credentials from the vault; availability reported; missing key means unavailable.
4. Remote calls as `Egress`, with taint attenuation and `egress_records`.
5. `modelVersion` on every attempt.
6. Cost units to the budget ledger — never billing.

A local GGUF adapter with constrained decoding is Phase 3 (M21), and it is the one that proves
the envelope was worth having.

## Future Work

- **Additional remote adapters.** Each is a class, not an architecture change — which is the test
  of whether this RFC did its job.
- **Streaming.** `ModelResponse` is currently whole-response; `AiResponseDelta` already exists on
  the event stream (RFC-0052), so the frontend contract is ready and the adapter side is not.
- **Batch and cached-prefix APIs**, where a provider offers them and the saving is real.
- **A published provider SPI**, if and only if the plugin host lands (D18) and someone actually
  wants to write one. It wraps `ModelAdapter`; it does not become it.

## Open Questions

- Should a provider be able to declare "my tool-calling is native but unreliable", so routing can
  prefer constrained decoding even where a native path exists? Some models advertise the
  capability and use it badly, and the failure is silent.
- Where should redaction sit — in the broker before the adapter, or in the adapter? Broker is
  cleaner and provider-agnostic; adapter is the only place that knows the wire format well enough
  to redact structured fields rather than text.
