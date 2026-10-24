# Identity and Model Attestation — Architecture Suggestion

**Status:** Suggestion for discussion; not an accepted RFC or implementation requirement  
**Scope:** Aidos identity, cross-platform tool invocation, inference-service trust, and optional model attestation  
**Related proposal:** [Agent Management on Android](AGENT-MANAGEMENT.md)  
**Purpose:** Record the current design direction before implementation. Reconcile it with the accepted identity, capability, Tool Broker, MCP, Android, runtime API, and Engine RFCs before treating it as normative.

## Summary

Aidos should use separate, cryptographically verifiable identities for distinct security principals and keep the identity contract independent of the transport. In particular, a model/inference system should have its own identity, separate from the Aidos installation and from the agent whose work it performs.

Suggested identity domains:

1. **Aidos installation identity** — a particular trusted Aidos runtime installation, on Android, Windows, Linux, or another supported platform.
2. **Agent identity** — a stable identifier for a particular agent hosted or managed by Aidos. It is the identity against which per-agent permissions are evaluated.
3. **Model-system identity** — a particular inference service/deployment. It identifies the service communicating with Aidos, not an agent and not an authority to invoke tools.
4. **Provider identity** — the app or service that owns a tool/API and enforces provider-side permissions (for example, Amp).

These identities describe different principals and must not be interchangeable. A model-system identity must never be accepted as an agent identity or as proof that a tool call is authorised.

## 1. Transport-independent installation and agent identity

Binder is one transport, not the identity system. The same core identity and authorisation concepts should work over Android Binder/AIDL, Windows IPC, local sockets, MCP over HTTP, or a remote authenticated connection.

- Each Aidos installation has a stable installation identifier and a protected signing key. Prefer platform-backed key protection where available, but do not assume every platform has equivalent hardware guarantees.
- The public key is trusted only after an explicit pairing, provisioning, or other defined trust-establishment flow. A caller must not be able to register an arbitrary public key and thereby become trusted.
- Aidos maintains stable agent identifiers within a defined identity namespace. A provider must be able to distinguish one agent from another even when they share the same Aidos process or installation.
- A provider can revoke an installation's trust or an individual agent grant independently.
- Key rotation, recovery, reinstall, backup/restore, and multi-device identity semantics need explicit rules. Do not silently assume that an agent has the same trust on every installation.

Platform transports provide their own peer authentication and isolation:
- Android: Binder caller identity plus package/signature verification and the agreed Aidos assertion.
- Windows local IPC: a suitably ACL-restricted named pipe and OS-level peer checks.
- Remote: authenticated TLS plus explicit pairing/provisioning and key validation.

Transport authentication and Aidos identity assertions complement each other; neither replaces provider-side authorisation.

## 2. Signed invocation assertion

Aidos should define a transport-independent, short-lived signed assertion for agent-initiated provider operations. The exact encoding and cryptographic profile should be selected during implementation rather than invented ad hoc.

Conceptually, the assertion binds:
- issuer and Aidos installation identifier;
- stable agent identifier;
- intended provider/audience;
- operation/tool identifier and relevant scope or request binding;
- unique invocation/request identifier;
- issued-at and expiry times;
- optionally, a model request/correlation identifier for diagnostics.

The provider validates the assertion's signature against a previously trusted installation key, verifies issuer, audience, operation binding, expiry and replay protections, then evaluates its own current per-agent policy. The Aidos Tool Broker must independently authorise the action before issuing the assertion. Both decisions must allow the call.

Do not place secrets or unnecessary private message content in signed claims. Prefer a standard, reviewed token/assertion format and cryptographic profile once the requirements are agreed.

## 3. Separate model-system identity

The inference service should have a distinct identity and, where useful, a protected key. This could identify a local model server, a separately hosted Aidos Engine inference process, or a remote inference deployment.

The model-system identity can establish which endpoint Aidos is communicating with and can support:
- authenticated connections to inference services;
- request/response correlation and audit;
- policy that controls which inference services may receive which data;
- optional evidence that a deployment meets an approved software/model policy.

It does **not** prove that output is correct or safe, and it does not authorise tools. The agent runtime and Tool Broker remain responsible for deciding whether a proposed tool call is allowed. The inference process must not possess the key used to sign agent invocation assertions or otherwise be able to mint claims for arbitrary agents.

Keep model identity separate from model version. A deployment may load a new model while retaining the same service identity under policy, and multiple deployments of the same model should not automatically share one identity. Record model identifier, weights/version digest, runtime, and configuration as separate metadata or measured attributes when relevant.

## 4. Model attestation: optional stronger evidence

**Model attestation** is an optional mechanism for checking evidence about the inference environment and, where the platform supports it, the model/runtime actually loaded. It is stronger than trusting a service key alone, but its guarantees vary significantly by implementation.

A possible attestation flow:

1. Aidos (or a trusted verifier) issues a fresh challenge/nonce.
2. The inference environment measures relevant components, potentially including boot state, runtime binaries, model weights, and configuration.
3. A protected hardware or confidential-computing mechanism produces signed evidence bound to those measurements and the challenge.
4. The verifier validates the evidence chain, freshness, measurements, and policy.
5. Aidos maps the result to a trust level and applies a policy, such as whether sensitive data may be sent to that inference endpoint.

Important limitations:
- Hashing a model file alone is not strong attestation: a compromised host may falsify the report or load different weights into memory.
- A hardware-backed key proves key possession, not automatically that the intended model is running.
- TPM measured boot, confidential-computing attestation, and application/model measurement provide different guarantees; they should not be described as equivalent.
- Attestation does not prove that model output is safe, correct, unbiased, or free from prompt injection.
- Attestation is evidence for a policy decision, not an authorisation grant by itself.
- Hardware-backed attestation may be unavailable or disproportionate on ordinary local deployments. The system should work without it.

## 5. Suggested trust levels

Keep the model-system identity useful on ordinary platforms, and make stronger assurances explicit rather than mandatory.

- **Level 0 — Untrusted/unknown:** endpoint is not authenticated or has not been approved; no sensitive data should be sent by default.
- **Level 1 — Authenticated service:** endpoint identity/key is trusted through pairing or provisioning, but the loaded model/runtime is not attested.
- **Level 2 — Measured deployment:** evidence about selected software/model components is checked against an approved policy, with clearly documented limitations.
- **Level 3 — Hardware-backed attestation:** a supported hardware/confidential-computing root of trust produces fresh evidence for the relevant environment and measurements.

These are proposed labels only. They should not be adopted until each level has precise, testable guarantees and a defined verifier/policy. Do not claim Level 2 or 3 if the evidence only hashes files in ordinary application code.

## 6. Separation of responsibilities

For a sample Amp message-search operation:

1. The model system produces a proposed tool call.
2. The Aidos agent runtime associates it with the actual agent/session and validates the proposal.
3. The Aidos Tool Broker checks Aidos-side grants, scope, approval requirements, and effect policy.
4. Aidos authenticates to Amp and presents a signed assertion binding the installation, agent, provider, operation, and invocation.
5. Amp validates the caller/assertion and checks Amp's own grant for that specific agent and operation.
6. Only if both Aidos and Amp allow it does Amp execute the operation.

The model system may be recorded as the inference endpoint/request associated with the decision, but it must not be allowed to choose or forge the agent identity in the assertion. If the inference service is compromised, the service should not thereby gain the ability to impersonate agents or bypass either policy engine.

## 7. Recommended implementation order

1. Reconcile the existing identity RFC/draft and define the identity namespaces and lifecycle.
2. Define installation identity, public-key trust establishment, revocation, and rotation.
3. Define agent identity and how the trusted runtime binds a real agent/session to an invocation.
4. Define the signed invocation assertion and replay/expiry protections.
5. Specify Android Binder and Windows/local/remote transports as implementations of the same contract.
6. Add model-system identity and authenticated inference-endpoint registration, keeping it separate from agent authority.
7. Add audit correlation between agent invocation, inference request, provider decision, and outcome while minimising sensitive payload retention.
8. Consider model attestation only where there is a concrete threat model and platform support; specify measurable claims and fail/allow policy before implementation.

## Open questions

- What precisely constitutes an installation after reinstall, device migration, or restore?
- Are agent IDs installation-local, account-scoped, or globally namespaced? What trust is preserved when the same logical agent runs on multiple devices?
- Which standard assertion format and signature profile should be used?
- How should keys be paired, rotated, recovered, and revoked across Android, Windows, and remote deployments?
- Which inference deployments warrant attestation, and which data-handling decisions depend on it?
- What model/runtime measurements can each supported platform actually verify, and what residual risks remain?

## Security principle

**Identity establishes which principal is making a claim; attestation provides evidence about an environment; authorisation decides what an operation may do. Keep all three separate.**

This document is a proposal, not an accepted RFC. It must not be treated as implemented functionality.
