# RFC-0042: Networking and Egress

Status: Accepted 2026-08-03

## Abstract

This RFC defines the single path by which data leaves the device. All network access is an
`Egress` effect (RFC-0030) mediated by one runtime HTTP client: no subsystem opens its own
connection. It specifies destination scoping that actually holds (allowlists alone do not),
offline detection and behaviour, and the audit record every outbound call leaves.

## Motivation

Offline-first is the product's first principle. That is only meaningful if leaving the device is
rare, explicit, and observable — which requires knowing every path by which it can happen:

- remote model calls (RFC-0023)
- HTTP-transport MCP servers (RFC-0031)
- Git `fetch` and `push` (RFC-0053)
- model weight downloads (RFC-0022)
- a future HTTP tool

Five subsystems, each of which would otherwise instantiate its own client with its own timeout
policy, its own proxy handling, and its own idea of what a "trusted" destination is. The first
one to get it wrong defines the system's actual egress posture.

There is also a specific, commonly-underestimated problem: **a domain allowlist is not a
destination control.** `allowedDomains = ["api.example.com"]` is defeated by a redirect, by DNS
resolving to an internal address, by an IP literal, or by a rebinding attack between the check
and the connection. A control that a model can be talked into stepping around is not a control,
and the model is the thing proposing the URLs.

## Goals

1. Define one mediated network path and forbid all others.
2. Define destination scoping that survives redirects, DNS, and IP literals.
3. Define offline detection and degradation.
4. Define timeouts, retries, and their interaction with recovery classes.
5. Define the audit record for outbound calls.

## Non-goals

This RFC does not define which providers exist (RFC-0021) or routing policy (RFC-0020).
It does not define egress *eligibility* of content — that is sensitivity labelling (RFC-0024)
and taint (RFC-0027).
It does not define a settings UI.

## Design

### One client, no exceptions

```kotlin
interface EgressClient {
    suspend fun request(
        capabilityId: CapabilityId,     // RFC-0018: named, not searched for
        request: EgressRequest
    ): EgressResponse
}
```

Every outbound byte passes through this. Provider adapters, the MCP HTTP transport, JGit's
transport, and model downloads are all constructed with it rather than with their own client.

This is enforced structurally: the platform HTTP client is not exported from the networking
module, and a test asserts no other module constructs one (RFC-0038). Convention alone does not
survive a contributor in a hurry.

### Destination scoping

`NetworkScope` (RFC-0018) is evaluated in four places, not one:

1. **Before DNS** — the requested host must match the scope. IP literals are rejected outright
   unless the scope explicitly names one; a model proposing `http://169.254.169.254/` is not
   making a hostname request.
2. **After DNS, before connect** — resolved addresses are checked against blocked ranges:
   loopback, link-local, and RFC-1918 private ranges are denied by default. This is what stops
   SSRF into the user's own network, which on a phone means their home LAN.
3. **On the connected socket** — the address actually connected to is re-checked, closing the
   rebinding window between resolution and connection.
4. **On every redirect** — a redirect target is a *new* destination and is validated from step 1.
   Cross-origin redirects are denied by default rather than followed.

```kotlin
data class NetworkScope(
    val allowedHosts: List<HostPattern>,
    val allowedPorts: List<Int>? = null,     // null ⇒ 443 only
    val allowPrivateAddresses: Boolean = false,
    val followRedirects: RedirectPolicy = RedirectPolicy.SAME_HOST_ONLY,
    val maxResponseBytes: Long = 32 * 1024 * 1024
)
```

Defaults are restrictive on purpose: HTTPS only, no private addresses, no cross-host redirects.
Loosening any of them is a capability the user grants explicitly, and the grant says what it
permits in those terms.

### Transport

TLS with the platform trust store. Plaintext HTTP requires an explicitly granted scope and is
never a default — including for `localhost`, where a plaintext MCP server on a shared machine is
readable by any local process.

### Amendment 2026-08-14 (RFC-0103) — Aidos SDK's loopback call to Aidos Engine is not `Egress`

RFC-0103 gives Aidos Agent a new outbound call this RFC's Motivation list (five subsystems) did
not anticipate: Aidos SDK's loopback HTTP client to Aidos Engine's port for
`/v1/chat/completions`, `/v1/embeddings`, and `/v1/audio/transcriptions`. Two things about it
would, read literally, collide with rules above:

- **"No subsystem opens its own connection" / "one runtime HTTP client."** Aidos SDK is,
  structurally, a second HTTP client outside `EgressClient`.
- **`NetworkScope`'s own default denies exactly this traffic**: "resolved addresses are checked
  against blocked ranges: loopback... denied by default" (Destination scoping, above) — Aidos
  Engine's port is `127.0.0.1` by construction (RFC-0103: "bound to `127.0.0.1`").

**Resolution: this traffic is not `Egress`, and does not go through `EgressClient`, by design —
not an oversight this RFC needs to police.** The reasoning:

1. **It never leaves the device.** This RFC's own Abstract scopes itself to "the single path by
   which data leaves the device" — Aidos Engine is a sibling app on the *same* device, and RFC-0103
   forbids any LAN/remote exposure of its port outright. Nothing here is the kind of boundary this
   RFC exists to control.
2. **The loopback-is-unsafe reasoning that motivates the default-deny above does not apply the same
   way here.** RFC-0031/D17 reject a loopback exemption for MCP specifically because a bare TCP
   port on Android carries no caller identity — "any app holding `INTERNET` can bind or connect to
   it." Aidos SDK's channel is different: the *port and bearer token themselves* are only ever
   handed out through a **Binder handshake**, and Binder — unlike a bare socket — gives the callee
   the caller's UID/package name for free, which is what makes a per-caller approval decision
   possible in the first place. This is *not* a signature-match trust claim: RFC-0103's own Trust
   model section is explicit that raw signature comparison (`protectionLevel="signature"`) is only
   "an OS-enforced initial gate for technical [identity] safety," not the actual authority — F-Droid
   rebuilds and re-signs submitted apps, so two genuine Aidos apps installed from F-Droid will *not*
   share a signature, and RFC-0103 designs around that explicitly ("Why this design, not
   signature-only"). The real trust anchor is the **persisted, explicit user-approval decision**
   (Engine's `AppApprovalStore`, surfaced via ConnectedAppsScreen) that the Binder-verified caller
   identity is checked against on every handshake — closer in shape to RFC-0031's own deferred
   Future Work ("Android's own IPC... where the caller's package name is verifiable," *plus* an
   explicit approval step) than to a bare signature check. This is the same shape as RFC-0055's own
   precedent: the DESKTOP runtime socket (UI↔runtime, not device↔network) is not run through
   `EgressClient` either, for the analogous reason that it's an intra-product IPC boundary with its
   own authentication, not a network egress path.
3. **It should not write `egress_records`.** That table is "the evidence for the answer to 'what has
   left this device, and where did it go'" — a call that never leaves the device has no answer to
   record there, and adding a same-device entry would make the privacy audit noisier without adding
   privacy-relevant information. If per-call observability into Agent↔Engine traffic is ever wanted,
   it belongs in RFC-0103's own domain (its "Connected apps" screen already tallies per-app request
   counts on Engine's side) rather than this table.
4. **This does not need a new capability-approval prompt of its own.** RFC-0103's Security section
   already states Engine executes "with authority already established by Aidos Agent's own approval
   flow, not re-derived by Engine" — the relevant authority question (may this Run call a model at
   all) is RFC-0018's `model:query` capability, already checked before dispatch, same as it is for a
   remote provider call today.

**What this amendment does not resolve, flagged rather than guessed:** whether Aidos SDK's client
should be constructed at the same composition-root layer as `EgressClient` (for consistency of
timeout/retry policy, Timeouts/retries above) even though it is not routed *through* it — this is
an implementation-shape question for whoever wires Aidos SDK into Aidos Agent's dependency graph
(RFC-0048), not a networking-policy question this RFC needs to settle. If reviewing this amendment,
the load-bearing claim to check is #2: that Binder-verified caller identity plus a persisted,
explicit user-approval decision — not a raw signature match — is a sufficient substitute for the
peer-identity check a bare loopback socket cannot provide.

**Certificate pinning is not used.** It breaks corporate proxies, breaks when providers rotate,
and produces failures users cannot diagnose or fix. The threat it addresses — a compromised
platform trust store — is out of scope (RFC-0003). Proxy configuration is honoured from the
platform, because a user behind a proxy who cannot use Aidos will simply not use Aidos.

### Offline

Offline is the expected state, not an error condition.

`NetworkAvailability` is observed from the platform and exposed on the Runtime API (RFC-0052).
When unavailable:

- `NETWORKED`-tier tools are filtered out of the model's tool list (RFC-0049) — the model is not
  told they exist, so it does not propose them.
- Routing resolves to `LOCAL` or `UNAVAILABLE_OFFLINE` (RFC-0020), never to a hung request.
- `git fetch` / `push` queue as pending intents surfaced in the UI, and execute on reconnection
  **with explicit confirmation** — never automatically, since the user may have moved networks
  deliberately.

Connectivity is treated as advisory and always verified by the attempt: a phone reporting a
connection while behind a captive portal is routine. A failed call under "available" degrades
identically to a call under "unavailable".

### Timeouts, retries, and recovery

| Class | Connect | Total | Retry |
|---|---|---|---|
| Model call | 10s | per routing policy, default 120s | `TRANSIENT`/`RATE_LIMITED` only (RFC-0029) |
| MCP HTTP | 10s | 60s | same |
| Git fetch/push | 30s | unbounded, cancellable | **never automatic** |
| Model download | 30s | unbounded, resumable | resumable by range |

Two rules that matter more than the numbers:

**A request whose response was not fully received is `INDETERMINATE`, not failed.** A `POST` that
timed out may have been processed. Recovery class `UNSAFE` applies (RFC-0009): it is surfaced to
the user, never silently retried. This is why `git push` never auto-retries.

**Rate limiting honours the provider's `Retry-After`.** Aidos does not invent its own backoff
against a service that has told it exactly what to do.

### Audit

Every call writes one record, and the record deliberately contains no payload:

```kotlin
data class EgressRecord(
    val id: UUID,
    val attemptId: UUID?,
    val capabilityId: CapabilityId,
    val destinationHost: String,
    val resolvedAddressClass: AddressClass,   // PUBLIC | PRIVATE | LOOPBACK
    val method: String,
    val requestBytes: Long,
    val responseBytes: Long,
    val payloadHash: String,                  // hash, never content
    val contentSensitivity: SensitivityLevel, // highest label sent (RFC-0024)
    val runTaint: TrustLevel,                 // RFC-0027
    val durationMs: Long,
    val outcome: EgressOutcome,
    val costUnits: Long?,                     // RFC-0028
    val providerRetention: ProviderRetention? // RFC-0026
)
```

`payloadHash` rather than payload: the audit trail must answer "what left, when, to whom, under
what authority" without becoming a second copy of everything that ever left. The prompt itself
is already recoverable from the Attempt's prompt package (RFC-0025) subject to retention
(RFC-0056).

The user-facing view is a single question answered honestly: *what has left this device, and
where did it go?*

## Data Model

```sql
CREATE TABLE egress_records (
    id TEXT PRIMARY KEY,
    project_id TEXT,                     -- NULL for user-scope (model downloads)
    attempt_id TEXT,
    capability_id TEXT NOT NULL,
    destination_host TEXT NOT NULL,
    resolved_address_class TEXT NOT NULL,
    method TEXT NOT NULL,
    request_bytes INTEGER NOT NULL,
    response_bytes INTEGER NOT NULL,
    payload_hash TEXT NOT NULL,
    content_sensitivity TEXT NOT NULL,
    run_taint TEXT NOT NULL,
    duration_ms INTEGER NOT NULL,
    outcome TEXT NOT NULL,
    cost_units INTEGER,
    occurred_at TEXT NOT NULL,
    FOREIGN KEY (attempt_id) REFERENCES attempts(id)
);

CREATE INDEX idx_egress_host ON egress_records(destination_host, occurred_at);
CREATE INDEX idx_egress_project ON egress_records(project_id, occurred_at);
```

`egress_records` are `PERMANENT` (RFC-0056). They are small, and they are the evidence for the
central privacy claim.

## Security

1. **One mediated path**, enforced by a test, not a convention.
2. **Four-point destination checking** — pre-DNS, post-DNS, post-connect, per-redirect. Private
   and loopback ranges denied by default. This is the SSRF control, and on a mobile device the
   protected network is the user's home LAN.
3. **Taint gates egress.** A Run that has read untrusted content requires per-call approval for
   every `Egress` effect (RFC-0027). This is the control that prevents "read a hostile file, then
   POST the user's source somewhere."
4. **Sensitivity gates content.** `SECRET` and `SENSITIVE` nodes never leave; `REQUIRES_APPROVAL`
   resolves against routing policy (RFC-0024, RFC-0020).
5. **No payloads in audit**, so the audit trail is not itself a disclosure risk.
6. **Plaintext requires an explicit grant**, including on loopback.

## MVP

1. `EgressClient` as the sole network path, with the no-other-client test.
2. `NetworkScope` with pre-DNS, post-DNS, and post-connect checks; private ranges denied;
   same-host redirects only.
3. TLS via the platform trust store; platform proxy honoured.
4. Offline detection, tool filtering, and `UNAVAILABLE_OFFLINE` routing.
5. Timeouts per the table; `INDETERMINATE` on incomplete responses; `Retry-After` honoured.
6. `egress_records` and a "what has left this device" view.

Not in MVP: queued offline network intents, resumable model downloads, per-destination budgets.

## Future Work

Per-destination egress budgets (RFC-0028), so a runaway integration is bounded by bytes as well
as by cost.

A pre-flight egress preview: showing what would be sent, before it is, for high-sensitivity
content.

Tor or proxy-chain support for users who need it, as an explicitly configured transport.
