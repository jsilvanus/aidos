# Agent Management on Android

**Status:** Product/architecture proposal  
**Scope:** Aidos Android app, agent discovery, permissions, and cross-app capabilities  
**Relationship to RFCs:** This document develops the user-facing direction. It does not silently amend accepted RFCs; implementation should reconcile any conflicts with RFC-0050 (Android), RFC-0018 (capabilities), RFC-0030 (Tool Broker), RFC-0031 (MCP), RFC-0052 (Runtime API), RFC-0046 (identity), and RFC-0103 (Engine).

## Product direction

The Aidos Android app should have two clear main pages for managing agents:

1. **Agents** — what agents and agent-providing apps are available, and what they are doing.
2. **Agent Permissions** — what each agent is authorised to do, under which scope, and whether the user must approve an action.

The pages have different jobs. Discovery and status belong on **Agents**; authority and consent belong on **Agent Permissions**. An agent's presence, installation, or successful connection must never grant it permission to act.

## 1. Agents page

The Agents page is the inventory and status view for agents known to Aidos.

An entry should show, where applicable:

- Agent name and icon.
- Provider/owning app and its identity.
- Availability and connection state.
- Enabled/disabled state.
- Current activity or lifecycle state, such as available, running, waiting for approval, or unavailable.
- A concise summary of declared operations/capabilities.
- Recent activity and relevant failures, subject to the user's audit/privacy settings.

Selecting an entry opens an agent detail view. It should include provider identity, version/protocol compatibility, declared operations, current status, and a **Manage permissions** action that opens Agent Permissions filtered to that agent.

The list may include Aidos-managed agents and agents exposed by other installed apps. These should be represented consistently, but the UI must distinguish the agent/runtime from the app or service that provides a capability. A provider may be installed but unavailable, or available while a particular operation is denied.

Do not equate an app with an agent: one app may expose multiple agents or operations, and some Aidos agents may be entirely local with no separate provider app.

## 2. Agent Permissions page

Agent Permissions is the central authority and consent interface. It should answer:

- Which agent is allowed to invoke which operation?
- On what resources, projects, accounts, or other scope?
- Is the operation allowed automatically, subject to approval, or blocked?
- Which grants are active, and how can they be reviewed or revoked?
- Are any requests waiting for a decision?

For each operation, expose three understandable policy outcomes:

- **Always allow** — permit it within the explicitly granted scope and applicable policy.
- **Ask every time** — require user approval for each invocation.
- **Not allowed** — deny the operation.

The actual policy model should remain richer than these labels when necessary. For example, a grant may be scoped to one project, one directory, read-only access, a particular provider identity, or a defined period. The UI should not imply a broader grant than the policy engine enforces.

Prefer least privilege and separate read operations from mutations and external side effects. Reading calendar entries must not imply permission to create or delete them. Sending a message must not be covered by a generic permission to read messages. High-impact operations may require explicit confirmation even when an agent has a general grant, according to the Effect Broker and operation policy.

The page should support reviewing, changing, and revoking grants, plus handling pending approval requests. Revocation must take effect in the authoritative policy/broker layer, not merely hide a toggle in the UI.

## 3. Security and trust model

The governing rule is:

> **Discovery describes what a provider offers; Aidos policy decides what an agent may do.**

- A provider's declared operation list is descriptive, not an authorisation grant.
- Installing, discovering, connecting to, or trusting a provider does not automatically authorise its operations.
- Each invocation is checked by Aidos against the current applicable policy and scope.
- MCP metadata, tool descriptions, prompts, and provider responses are untrusted input; they cannot grant themselves authority or override Aidos policy.
- Provider identity and protocol compatibility must be checked before a provider is adopted or used.
- A provider must not be able to impersonate another provider by reusing its display name.
- Audit records should capture the operation, relevant provider/agent identity, decision, and outcome without leaking secrets or unnecessarily collecting private payloads.
- Disconnection, process death, timeout, or provider unavailability must fail safely. Do not automatically retry an ambiguous side-effecting operation unless its semantics explicitly make that safe.
- A user approval is an explicit decision about a specific operation and scope, not permission for the provider to broaden its own grants later.

For cross-app Android providers, use an explicit service/interface and verify the calling app identity at the IPC boundary. Capture Binder caller identity synchronously before dispatching work to another coroutine/thread; then apply package/signature identity checks and Aidos's own policy. Android permissions alone—especially normal-level permissions—must not be treated as the complete authorisation system. Keep requests bounded and handle timeout, cancellation, Binder death, and provider process death.

## 4. Is the planned MCP path right for this vision?

**Yes—with one important distinction: MCP is the interoperability protocol, not Aidos's permission system or Android app-management model.**

The existing architecture is directionally right:

- **RFC-0030 Tool Broker / Effect Broker:** remains the authoritative execution and policy path for native and external operations.
- **RFC-0031 MCP:** adapts MCP servers into Aidos operations/tools. MCP is not a parallel tool system and does not bypass Aidos policy, effect classification, previews, approvals, or audit.
- **Official Kotlin MCP SDK:** should own MCP protocol details such as JSON-RPC, protocol negotiation, message types, notifications, and standard transport APIs where applicable. Aidos should retain its own adapter, provider registration/adoption, identity, grants, scope, effect and audit semantics.
- **MCP stdio and Streamable HTTP:** remain useful for the existing desktop/headless and network-connected integrations. Android's offline-first path must not depend on an internet connection or a remote MCP server.
- **Android Binder/AIDL transport:** can provide an additional local IPC transport for cross-app providers. A separate Android-specific adapter around the official Kotlin SDK is a reasonable direction, provided the SDK's public transport contract supports the integration without forking the SDK.

The crucial design is to keep three concerns separate:

1. **Provider contract and discovery:** what an agent/provider exposes, its identity, version, and operations.
2. **Transport:** how a request reaches it—an in-process native call, Android Binder, MCP over HTTP, or MCP over stdio where supported.
3. **Authority and execution:** whether Aidos permits this agent to invoke this operation in this scope, and how approval, effects, audit, cancellation, and recovery are handled.

**For the intended Android ecosystem, MCP servers over Binder are the standard way for an app to expose its own tools to Aidos agents.** This makes Android apps the owners of their domain capabilities while Aidos remains the agent runtime and MCP client. Keep the option of native in-process tools for Aidos-owned functionality or a narrowly justified platform integration, but do not create a competing cross-app tool protocol by default. Avoid inventing a proprietary MCP dialect: Binder should carry standard MCP JSON-RPC semantics if it is presented as an MCP transport.

Likewise, a Binder transport does not itself solve security. Binder establishes an IPC channel; Aidos still needs explicit provider identity, policy evaluation, bounded request handling, lifecycle handling, and safe failure behaviour.

## 4A. First vertical slice: Agentic Messenger (Amp)

The first end-to-end integration should use **Agentic Messenger (Amp)** as the concrete example of an Android app that provides tools to an Aidos-hosted agent.

Responsibilities:

- **Amp owns the messaging domain**: its UI, conversation and message state, and the implementation of messaging operations.
- **Amp provides the user-facing agent entry point initially**. The user starts or interacts with the agent from Amp.
- **Aidos owns the agent runtime**: agent loop, model orchestration, task state, tool selection, policy checks, approvals, execution and recovery.
- **Amp exposes its tools as an MCP server over Binder**. The server invokes Amp's existing domain logic; it must not duplicate or bypass that logic.
- **Aidos acts as the MCP client** and invokes Amp's tools only through the Tool Broker / Effect Broker and the applicable permissions.

This means there are two distinct communication paths between the apps, even if both use Android IPC:

1. **Agent interaction path:** Amp's UI requests agent work from the Aidos agent service through the appropriate Aidos client/runtime API.
2. **Tool invocation path:** Aidos's MCP client calls Amp's MCP server over Binder to perform an Amp-owned operation.

These paths must not be conflated. Amp is not the agent runtime merely because it hosts the agent's entry point, and Aidos should not manipulate Amp's private database or internal classes directly. The MCP server should be a thin adapter over Amp's existing application/domain services.

A minimal first slice should prove one harmless read-only operation, then one explicitly approved mutation. It should cover discovery/registration, caller/provider identity, permission denial, cancellation/timeout, and provider process death. Side-effecting calls must not be blindly retried when the outcome is ambiguous.

### Later: surface the same agent in Aidos

A later step may add an **Agents** view in Aidos itself. This should be another frontend to the same Aidos-hosted agent/session, not a second copy of the agent running independently inside Amp. It can show the agent's status, current work, provider/tool availability and permissions, and may let the user open or continue the same session.

The agent should have a stable identity/session reference across both entry points. UI state can differ between Amp and Aidos, but execution state and authorisation remain owned by Aidos. The initial release does not need the full Aidos agent-management UI if Amp is the intended starting point; design the contracts so that adding the second view does not require moving the runtime.

### Important scope distinction: MCP client versus provider server

RFC-0031 currently specifies Aidos primarily as an **MCP client** that consumes external MCP servers; it explicitly makes Aidos exposing its own runtime as an MCP server a v1 non-goal. That is not the same as an independently installed app exposing its own operations to Aidos. For the cross-app vision, we must explicitly design and test the provider-side direction too: Aidos connects as the MCP client over Binder, while the other app hosts an MCP server over the Binder transport. The planned Kotlin Binder transport project can support that direction only if its transport contract and SDK integration work for both client and server roles. Alternatively, a small native Android provider contract can be used for integrations that do not need MCP interoperability. Do not assume the current inbound MCP client implementation automatically delivers cross-app agent discovery or provider hosting.

## 5. Recommended request path

All agent-initiated operations—whether local, cross-app, or remote—should converge on the same internal policy and execution path:

```
Agent
  -> operation/tool adapter
  -> Aidos Tool Broker / Effect Broker
  -> identity + scope + grant/policy check
  -> approval/preview when required
  -> provider transport (local / Binder / MCP HTTP / MCP stdio)
  -> result validation and audit
  -> agent
```

Transport-specific code must not decide that an operation is authorised. Provider code must not be able to skip the broker by calling an alternate path. Any unavoidable platform-specific path should preserve the same policy semantics and be covered by contract tests.

## 6. Relationship to Engine and Agent hosting

The Android UI may present one coherent Aidos app experience while Engine Core and Agent Runtime remain separate modules with explicit contracts. Sharing an Android host or foreground-service lifecycle does not mean merging their responsibilities or authorisation domains.

- Engine authority (for example, permission to request inference) is not permission to run an agent or invoke tools.
- Agent authority does not automatically grant access to every model, provider, project, or external service.
- Cross-app clients should receive only the API surface and scopes explicitly granted to them.
- A separate provider app remains independently unavailable if it is force-stopped, disabled, uninstalled, or its service cannot be bound. The UI should communicate that state rather than implying guaranteed availability.

## 7. Suggested implementation order

1. **Define the internal provider/operation model** and how it maps to existing Tool Broker, Effect Broker, identity, capability, and audit contracts.
2. **Define the Agents inventory model**: provider identity, agent identity, version, declared operations, availability, and status.
3. **Define permission presentation and policy mapping**: operation-level grants, scope, approval mode, revocation, and pending requests.
4. **Prove a cross-app Android vertical slice** with two separately installed apps and a harmless read-only operation; test provider death, denial, and identity mismatch.
5. **Integrate MCP through the official Kotlin SDK** and its public transport abstraction; add Binder as a separate Android-specific transport if it remains the best fit after validating the current SDK API.
6. **Run every transport through the same policy path** and add contract tests proving that denied operations remain denied regardless of transport.
7. **Reconcile the user-facing page scope with RFC-0050** and update relevant RFCs/roadmap before treating this proposal as accepted architecture.

## 8. Non-goals

- Giving agents unrestricted access merely because they are installed.
- Treating MCP tool discovery as consent.
- Requiring every native Android capability to be implemented as an MCP server.
- Letting providers grant or expand their own authority.
- Guaranteeing a bound provider app remains alive in the background.
- Building a second permission engine inside the UI or inside each transport adapter.

## Related architecture

- [RFC-0018: Capability Manager](docs/rfcs/0018-capability-model.md)
- [RFC-0030: Tool Broker](docs/rfcs/0030-tool-broker.md)
- [RFC-0031: MCP](docs/rfcs/0031-mcp.md)
- [RFC-0050: Android](docs/rfcs/0050-android.md)
- [RFC-0052: Runtime API](docs/rfcs/0052-runtime-api.md)
- RFC-0046: Identity (Draft; locate/reconcile its current source and requirements before implementation)
- [RFC-0103: Aidos Engine](docs/rfcs/0103-aidos-engine.md)
- [RFC-0103 change rationale](RFC-0103-CHANGE-RATIONALE.md)
