# RFC-0120: Agent Runtime API (`/v1/agent`)

Status: Implemented 2026-11-15 (MVP: every item in the MVP section is built, wired and tested; post-MVP and device acceptance remain: not served on Android, no streaming or persistence).
Non-MVP Work: **Remaining**

Related: D48 (proposed), D49 (per-app agent grant), D52 (placement and tool relay), RFC-0027 (taint), RFC-0030/0031 (tools, MCP), RFC-0104 (locality), RFC-0107 (remote agent hosting), RFC-0115 (Engine-side tools), RFC-0103/0121 (Engine API and SDK).

## Abstract

An app that wants an agent loop, and has no loop of its own, asks the Engine for one. The Engine serves `/v1/agent/*` beside the model API on its loopback port, behind the same per-app bearer token. A run is a task: the app starts it, observes it, answers tool calls that belong to the app, and may cancel it. This RFC is the normative description of that API and its authorisation rules.

## Motivation

The API exists, is documented in a docs page, and is used by Amp and the desktop agent. Its decisions are spread over D48, D49 and D52, none of which is an RFC, and RFC-0107 builds on it without defining it. A frozen contract (RFC-0121) deliberately leaves it out. It needs a home so changes are deliberate.

## Goals

- A caller supplies its own tools; the runtime asks, the app that owns the domain answers.
- Tasks, not requests: a run survives a slow tool, can be observed by long poll or event stream, and is cancellable.
- Authority is explicit: an approved app cannot run agents until the user also grants agent runs.
- Locality (RFC-0104) is checked on every model call of a turn.

## Non-goals

- Streaming tokens (not yet).
- Durable runs across an Engine restart (runs are in memory today).
- Per-tool authorisation of a caller's own tools (the caller owns them).

## Design

### Routes

`GET /v1/agent/info` (open to any approved app: `apiVersion`, `maxConcurrentRuns`, `maxSteps`, `toolWaitSeconds`); `POST /v1/agent/turns` (start); `POST /v1/agent/runs/{id}/tool-results`; `GET /v1/agent/runs`; `GET /v1/agent/runs/{id}?since=<seq>&waitMs=<n>` (snapshot, long poll up to 30 s); `DELETE /v1/agent/runs/{id}` (cooperative cancel); `GET /v1/agent/events` (`text/event-stream` of advisory `run.updated`).

### A run

`POST /v1/agent/turns` takes `{model, message, systemPrompt?, history?, tools?, maxSteps?, contextWindow?, locality?, useEngineTools?, async?}`. History and the message are untrusted input (RFC-0027). The reply carries `status` (`running`, `tool_calls`, `completed`, `failed`, `cancelled`), `runId`, `seq`, the final `reply`, pending `toolCalls`, `executedToolCalls`, `steps` and `error`. On `tool_calls` the app runs the tool and posts `{results:[{callId, output, isError}]}`; an unanswered call fails after `toolWaitSeconds` with an error the model sees.

### Tasks and notifications

Every run is a task. With `async` the start returns a `running` snapshot at once. `seq` rises on each change so a client ignores stale data. Notifications never carry correctness: they are lossy, have no replay, and the snapshot is authoritative. Cancel makes the status `cancelled` at once; a model call in flight may finish and is discarded. Runs belong to the token subject that started them; another app's run id is a 404. Finished async runs stay readable for ten minutes (at most 64).

### Authorisation

Two conditions must hold: the app is `APPROVED` (RFC-0103) and the user has allowed agent runs for it (`AppApprovalRecord.agentAllowed`, default false, cleared on revoke); otherwise every route except `/info` answers 403 `agent_not_granted`. The Engine's own token and the desktop's own localhost client are the user's and exempt.

### Locality

`locality` is the widest placement the turn's model calls may use; absent means `local_only` (D47). The strictest of the request, the app's limit and the Engine's limit applies to every model call in the turn. Model calls inside the runtime go through the in-process inference manager, not the HTTP model API.

### Engine-side tools

With `useEngineTools: true` the turn is also offered the operations of MCP servers the user adopted in the Engine's Tools pane, named `<server>:<operation>`. The Engine runs them itself; they never go to the caller, each call needs the user's approval, and results are untrusted (RFC-0115, RFC-0031).

### Remote placement

The loop may run on a peer Engine (D52, RFC-0107): the origin remains the front door, the peer holds the loop, and tool execution stays at the tool host with the origin's own taint tracking.

## Security

- The agent grant is default-deny and separate from model access.
- Taint is applied by the Engine, not reported by the caller (RFC-0027).
- Limits: `maxConcurrentRuns` (429 `too_many_runs`), `maxSteps`, retention cap; request size is limited by the Engine's HTTP layer.
- Errors use the Engine envelope `{"error":{"message","type","code"}}`: `invalid_request` 400, `unknown_call` 400, `run_not_found` 404, `run_finished` 409, `too_many_runs` 429, `agent_not_granted` 403.

## MVP

Built on the desktop host with route tests, the run reaper, the tool-approval queue and routed (peer) runs: all routes above, the grant, locality, engine-side tools, peer endpoints. Not built: serving on the Android host, streaming, persistence across restart, per-agent identity.

## Implementation Files

```yaml
files:
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/AgentPeerEndpoints.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/AgentRoutes.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/AgentRunApi.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/AgentRuntime.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/AgentWire.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/AgentsControl.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/EngineAgentRuntime.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/EngineTools.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/ManagedModelAdapter.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/PeerRunReaper.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/RemoteAgentClient.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/RoutedAgentRuntime.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/RoutedModelAdapter.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/ToolApprovalQueue.kt
  - engine/agenthost/src/test/kotlin/fi/italeino/aidos/engine/agent/AgentRoutesTest.kt
  - engine/agenthost/src/test/kotlin/fi/italeino/aidos/engine/agent/AgentContractFreezeTest.kt
  - engine/agenthost/src/test/kotlin/fi/italeino/aidos/engine/agent/AgentRuntimeConfigTest.kt
  - engine/agenthost/src/test/kotlin/fi/italeino/aidos/engine/agent/EngineToolsTest.kt
  - engine/agenthost/src/test/kotlin/fi/italeino/aidos/engine/agent/PeerRunReaperTest.kt
  - engine/agenthost/src/test/kotlin/fi/italeino/aidos/engine/agent/RemoteAgentHostingTest.kt
  - engine/agenthost/src/test/kotlin/fi/italeino/aidos/engine/agent/RoutedModelAdapterTest.kt
  - engine/agenthost/src/test/kotlin/fi/italeino/aidos/engine/agent/ToolApprovalQueueTest.kt
  - docs/agent-runtime-api.md
```

## Post-MVP Work

Checklist for work beyond the MVP. Each item has a stable id; set `status` to `partial` or `done` and list the implementing `files` (checked by `scripts/rfc/postmvp.py`).

```yaml
post_mvp:
  - id: "0120.1"
    title: "Serve /v1/agent on the Android host"
    status: todo
    phase: P1
  - id: "0120.2"
    title: "Freeze the agent API as its own contract with a tripwire test"
    status: done
    phase: P1
    files:
      - engine/agenthost/src/test/kotlin/fi/italeino/aidos/engine/agent/AgentContractFreezeTest.kt
      - sdk/CONTRACT.md
  - id: "0120.3"
    title: "Streaming of agent output"
    status: todo
    phase: P2
  - id: "0120.4"
    title: "Durable runs across an Engine restart"
    status: todo
    phase: P2
  - id: "0120.5"
    title: "Per-agent identity and per-tool authorisation"
    status: todo
    phase: P3
```
