# RFC-0116: Agentic Messenger (AMP)

Status: Draft (2026-11-15). Partially implemented: the messenger itself lives in the separate repository `jsilvanus/agentic-messenger` (relay, client SDK, envelope and policy engine, desktop UI, Aidos agent bridge, MCP over Binder); the Aidos side of the bridge is `agent/amp`. MLS encryption, federation, hardware-backed keys and the persistent-memory bridge are not built.

Related: RFC-0003 (security), RFC-0024/0025 (content and prompts), RFC-0026 (memory), RFC-0027 (trust and taint), RFC-0031 (MCP), RFC-0035 (secrets), RFC-0042 (egress), RFC-0046 (identity and actors), RFC-0057 (glanceable), RFC-0106 (generative UI), RFC-0107 (remote agent hosting), RFC-0113 (blinded egress), RFC-0114 (work projects). Source plans: `agentic-messenger/docs/plan.md`, `roadmap.md`, `identity-and-cryptography.md`, `relay-and-storage.md`, `agent-memory-architecture.md`, `federation-architecture.md`, `aidos-joint-app-ux.md`.

## Abstract

The Agentic Messenger Platform (AMP, "Amp") is an end-to-end encrypted messenger and open protocol in which humans and AI agents are first-class, separately keyed participants. A conversation between two people is a four-party mesh: each human and each person's Aidos assistant. Aidos supplies the agent (model, tools, taint model); Amp supplies identity, transport, encryption, the conversation UI and the deterministic rules about what an agent may see and say. This RFC fixes the contract between the two so that neither repository has to guess: what Aidos requires of Amp, what Amp may rely on in Aidos, and what is built.

## Motivation

Messaging apps treat AI as an external bot or a webhook. That breaks three things Aidos already promises: untrusted peer text must not steer the agent (RFC-0027), a private message must not reach a model or the network by accident (RFC-0024, RFC-0042, RFC-0113), and the person must be able to see, per message, who actually said it. A messenger built around Aidos can keep those promises in the protocol rather than in prompts. A second reason is practical: Amp is the first non-Aidos application that uses the Aidos agent through the SDK, the loopback agent API (D48) and the Binder tool path (RFC-0031), so it is the real test of the app-facing contract.

## Goals

- Humans and agents have distinct cryptographic identities; an agent endpoint is a delegated, revocable leaf of its owner.
- All four topologies work: human to human, human to agent, agent to human, agent to agent.
- What an agent may disclose is decided by a deterministic policy evaluator before any model sees the question. A model never decides a privacy boundary.
- A message sealed `HUMAN_ONLY` is unreadable to the agent endpoint by construction (no key), so it can never enter a prompt, a memory, an embedding or an egress.
- The relay is blind: it routes by blinded mailbox, stores ciphertext for a bounded time and deletes it on delivery acknowledgement.
- Amp reuses Aidos instead of copying it: the agent loop, taint model and model adapters stay in Aidos; the conversation UI components are Amp's and are consumed by the Aidos desktop (D46).

## Non-goals

- Amp is not a general chat product with bots bolted on, and does not federate with Matrix, XMPP or Signal.
- The relay never runs models or agent logic.
- Amp does not own document editing or the speech-to-text cursor (Dictator does) and does not own the agent loop (Aidos does).
- This RFC does not specify MLS or the federation wire protocol in full; it fixes the requirements they must meet (see Post-MVP Work).

## Design

### Participants and identity

Each person has a root identity (`did:aidos:<hash of root key>`, Ed25519) that signs a device registry. Every device and every agent runtime has its own keypair; a private key never leaves its device. The agent endpoint is `AGENT_DELEGATE`: a persistent key held in the local Aidos runtime, registered with a signed `delegationScope` (`allowedIntents`, `escrowRequired`). Revoking an agent removes its key from future fan-out without touching the person's devices. This maps to RFC-0046: an Amp device or agent is an `ActorRef`, and a message authored by the agent is recorded with the agent as actor and the person as principal.

### The Universal Agentic Envelope (UAE)

Every message is a signed envelope: routing header (sender endpoint and type: `HUMAN_DIRECT`, `AGENT_DELEGATE`, `SYSTEM_SERVICE`), a provenance record (original human versus agent-synthesised), an `InformationBoundaryPolicy` (`AGENT_ACCESSIBLE` or `HUMAN_ONLY`, plus progressive-disclosure rules naming `allowedTopics` and `escrowedTopics`), and a payload of natural language plus typed annotations (`status`, `cause`, `eta`, `location`, each with confidence). The UI shows provenance on every bubble.

### Deterministic information boundary

A question to an agent about something a sender already shared ("why are you late?") is answered, when possible, from that thread's annotations. The evaluator (`InformationBoundaryEvaluator` in `amp-core`) matches the question's topic against `allowedTopics` and `escrowedTopics` with no model involved: allowed topics are answered, escrowed topics become a one-tap approval card for the sender, anything else is refused or escalated. Only after the evaluator allows it may a model phrase the answer. Evaluator decisions are written as audit records. This is the same pattern as RFC-0113: the model receives a synthetic, already-decided result.

### Taint and trust on the Aidos side

Every inbound message from another party is `UNTRUSTED` (RFC-0027). `AmpAgentRunner` (`agent/amp`) applies that level monotonically to peer turns, so a run that has read a peer message cannot take an UNSAFE, egress or out-of-project effect without per-call approval. A peer can never directly write persistent memory: a message that says "remember that X" does not write the entity graph or intent graph; those writes need deterministic schema extraction or an explicit confirmation in an Aidos UI (RFC-0026). Aidos-side results of the policy evaluator carry no taint; the message text does.

### Human-only compartment

For a `HUMAN_ONLY` message the content key is wrapped only for the person's human devices; the agent leaf receives a sealed placeholder. Consequences that Aidos relies on: the text cannot be embedded, cannot enter the Knowledge Engine (RFC-0015), the prompt (RFC-0025) or memory (RFC-0026), and cannot be sent by a remote model (RFC-0023). If the agent needs to act on a human-only message, the person must say so in their own words.

### Transport: blind relay, queue, blobs

Devices connect over WebSocket with a blinded `mailbox_id = HMAC-SHA256(K_epoch, device_id)`. The relay routes to live sockets, otherwise keeps the ciphertext in a store-and-forward queue (default TTL 14 days) and deletes it on a signed delivery acknowledgement. Voice notes and attachments are encrypted on the device and stored as opaque blobs (filesystem or S3); the key travels inside the E2EE envelope. A public KeyPackage registry holds pre-keys indexed by hashed identity commitments. From Aidos's point of view the relay is a network destination: a message leaving a Life project is an egress and passes RFC-0042 and, for zero-egress projects, the RFC-0113 gate.

### How Aidos hosts the agent

On desktop, the Amp agent service calls the Aidos Engine's loopback agent API (`/v1/agent/`, D48) with the Engine's bearer token and per-app approval; tools belong to the calling app, so a run that needs one returns `tool_calls`, Amp executes it and posts the result back. In process, Amp links `agent/amp` (`AmpAgentRunner`, `AmpToolDefinition`, the OpenAI-compatible adapter) to run the loop against any `ModelAdapter`. On Android, Amp serves a chosen set of its chat tools over the MCP Binder transport (`amp-mcp`, `amp-mcp-android`): deny by default, a caller allow-list at the transport, and a user approval screen per caller. Amp's own privacy policy still applies to every tool, because MCP calls run the same handlers.

### UI

Amp specifies the Conversation Mesh domain: login and identity, contacts and presence, the four-party conversation, the dual view (natural-language stream versus a condensed cue ticker, cf. RFC-0057), progressive-disclosure `ApprovalCard`, provenance and privacy-seal badges, and the `GenerativeUiRegistry` that renders envelopes into components. Where those components are implemented is governed by RFC-0123: the Trusted Component Registry (`aidos-tcr`) is the single implementer and the Aidos desktop consumes them (RFC-0106). Amp's own `amp-ui` module is the interim home until they move; its README still says the owning apps keep the components, which conflicts with RFC-0123 (see its Open Questions).

## Data Model

Aidos stores nothing new for the MVP: Amp keeps its own working memory (decrypted envelopes, local annotation index, outbox) in its SQLite store, scoped to the conversation. The Post-MVP bridge adds Aidos-side rows only through existing mechanisms: contact dossiers and commitments as content nodes and Intent Graph entries (RFC-0024, RFC-0110), written under the rules in "Taint and trust".

## Security

1. The relay and any federated intermediary see only blinded tokens, ciphertext and opaque blobs; a compromised relay learns no handles, text or annotations.
2. Policy is deterministic. No prompt can widen an `allowedTopics` list.
3. Inbound text is untrusted data. It is delimited in the prompt (RFC-0025) and attenuates authority (RFC-0027).
4. Keys: device and agent private keys are vault or keystore material (RFC-0035); hardware-backed storage is Post-MVP.
5. Replay: envelopes carry a timestamp and a unique id; receivers reject frames outside a drift window.
6. Provenance signatures are kept per turn so an audit can distinguish a person's words from an agent's.

## MVP

Built (verify against `agentic-messenger` main; this list was read from its code on 2026-11-15, tests not run here):

1. UAE data model, JSON/CBOR serialisation, provenance, and the deterministic boundary evaluator with audit records (`amp-core`).
2. Ed25519, X25519 and AES-256-GCM, with dual-compartment encryption in which the agent leaf gets a sealed tombstone (`amp-crypto`).
3. Blind relay with blinded mailbox routing, TTL store-and-forward with ACK purge, client-encrypted blob storage, KeyPackage registry (`amp-server`), and a client SDK with reconnect, outbox and SQLite working memory (`amp-client-sdk`).
4. Compose UI and a dual-window desktop launcher: login, contacts, conversation, dual view, provenance badges, approval card, generative-UI registry (`amp-ui`).
5. Aidos agent bridge: `AmpAgentRunner` with UNTRUSTED taint on peer turns (`agent/amp`), the desktop agent service over the Engine's loopback API (`amp-agent`).
6. Chat tools served over MCP Binder with deny-by-default exposure and user approval (`amp-mcp`, `amp-mcp-android`).

Not in MVP: see Post-MVP Work.

## Future Work

See the checklist below. Order of value: persistent memory bridge (needs RFC-0026 rules decided), MLS group encryption and device pairing, in-process embedding in the Aidos build, then federation and the web client.

## Open Questions

- Should the agent endpoint's `delegationScope` be an RFC-0018 capability the Engine issues, or a separate Amp record that the Engine only checks? Leaning to a capability so revocation is one mechanism.
- Which Amp tools are safe to expose over MCP to a non-Aidos agent? The roadmap defers the external MCP disclosure policy (`answer_from_context`, `request_progressive_disclosure`) for review.
- Does a message the agent sends on the person's behalf count as an egress that needs the RFC-0042 preview, or as a tool effect (RFC-0030)?

## Implementation Files

```yaml
files:
  - agent/amp/build.gradle.kts
  - agent/amp/src/commonMain/kotlin/dev/aidos/amp/AmpAgentRunner.kt
  - agent/amp/src/commonMain/kotlin/dev/aidos/amp/AmpModels.kt
  - agent/amp/src/commonMain/kotlin/dev/aidos/amp/HttpTransport.kt
  - agent/amp/src/commonMain/kotlin/dev/aidos/amp/OpenAiHttpModelAdapter.kt
  - agent/amp/src/jvmTest/kotlin/dev/aidos/amp/AmpAgentRunnerTest.kt
```

Outside this repository (`jsilvanus/agentic-messenger`): `amp-core`, `amp-crypto`, `amp-client-sdk`, `amp-server`, `amp-agent`, `amp-mcp`, `amp-mcp-android`, `amp-ui`.

## Post-MVP Work

Checklist for work beyond the MVP. Each item has a stable id; set `status` to `partial` or `done` and list the implementing `files` (checked by `scripts/rfc/postmvp.py`). Items implemented in the Amp repository are tracked in its `docs/roadmap.md` and mirrored here by status only.

```yaml
post_mvp:
  - id: "0116.1"
    title: "MLS (RFC 9420) group encryption with a distinct leaf per human device and agent, epoch updates"
    status: todo
    phase: P1
  - id: "0116.2"
    title: "Device pairing by QR cross-signing, revocation certificates and epoch ratchet on removal"
    status: todo
    phase: P1
    depends_on:
      - "0116.1"
  - id: "0116.3"
    title: "Hardware-backed keys: Android StrongBox/KeyStore and desktop OS keychain for device and agent keys"
    status: todo
    phase: P1
  - id: "0116.4"
    title: "Persistent memory bridge: contact dossiers and commitments into the Knowledge and Intent Graph, with confirmation for peer-sourced writes"
    status: todo
    phase: P2
  - id: "0116.5"
    title: "Agent endpoint delegation scope issued as an RFC-0018 capability, revocable from the Engine"
    status: todo
    phase: P2
  - id: "0116.6"
    title: "Message-send egress preview and RFC-0113 gate for zero-egress Life projects"
    status: todo
    phase: P2
  - id: "0116.7"
    title: "External MCP disclosure policy: answer_from_context, request_progressive_disclosure, approve_disclosure, get_audit_log"
    status: todo
    phase: P2
  - id: "0116.8"
    title: "In-process embedding: amp-core, client SDK and amp-ui as Gradle dependencies of the Aidos build"
    status: todo
    phase: P3
  - id: "0116.9"
    title: "Conversation presence nodes on the Aidos spatial canvas"
    status: todo
    phase: P3
  - id: "0116.10"
    title: "Dictator Red Pill audio bridge for voice messages"
    status: todo
    phase: P3
  - id: "0116.11"
    title: "Federated blind relays: DID addressing, mTLS server-to-server envelopes and acks, anti-abuse"
    status: todo
    phase: P4
    depends_on:
      - "0116.1"
  - id: "0116.12"
    title: "Web client for invite links (Kotlin/Wasm) and promotion to a full device"
    status: todo
    phase: P4
```
