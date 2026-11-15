# RFC-0107: Remote Agent Hosting over AIDIN

Status: Draft (proposed with D52, 2026-11-10). Partially implemented (2026-11-15): phases 1 and 3 are built and tested over a real AIDIN peer link between two nodes on loopback; the Android Binder MCP broker (phase 2) and a run on two real devices (phase 4) are not.

Related: RFC-0105 (inference groups), RFC-0104 (locality), RFC-0027 (trust and taint), RFC-0031 (MCP), D48, D49, D51, D52. Study: `plans/cross-device-agent-routing.md` in the project files.

## Abstract

An Engine can run an agent loop on another Aidos Engine it trusts (for example a phone using the desktop's agent and model), while tools keep executing on the device that owns them. The origin Engine exposes its local tools to the peer as an MCP tool relay over the AIDIN peer link.

## Design

### Roles
- **Origin Engine** (front door; typically the phone): receives `/v1/agent` calls from local apps, chooses where the loop runs, hosts the MCP tool relay, is MCP client of the local Binder apps (D51).
- **Agent host** (peer, typically the desktop): runs the loop, calls its own model through its own `InferenceRequestManager`, and is MCP client of the origin's tool relay.
- **Tool host**: the origin. Tools execute only there.

### Routing
A virtual agent id (working name `aidos:agent`) has an ordered candidate list, like `aidos:chat`: `[peer:<nodeId>, local]`. Resolution filters by the effective locality limit first, then takes the first available candidate. A loop on a peer is placement `cluster`. Falling back to a narrower placement is allowed, a wider one never. Hop limit 1: the agent host must not delegate the loop or its model calls onward beyond what the forwarded limit allows.

### Peer endpoints (sketch, not fixed)
`POST /aidin/v1/agent/turns`, `GET /aidin/v1/agent/runs/{id}?since=&waitMs=` (resume by `seq`), `DELETE /aidin/v1/agent/runs/{id}`, and the MCP tool relay stream from the origin to the agent host. Authorised by a new credential scope `agent` (alongside delegate, serve, help, anchor). A run is owned by (peer node, calling app on the origin).

### Tool-host enforcement
1. Per-peer, per-app tool allowlist, approved by the user on the origin.
2. Taint is computed at the origin: any tool result entering a remote run taints it (RFC-0027); effectful tools then need approval. Peer-reported taint is ignored.
3. Both grants hold: the origin's per-app agent grant (D49) and the peer's `agent` scope.
4. Audit records run id, peer, tool names and outcomes, never content.

### Failure
Runs are in memory. If the link drops, tool calls time out (today 120 s); the agent host parks the run and the origin resumes by `seq`. Whether the agent host continues or cancels when the origin disappears is open.

## Not covered
Model-side delegation (RFC-0105, done); LAN sharing for non-Aidos clients (RFC-0051); Android agent runtime hosting (D49).

## Phases
1. Agent adapter through the virtual router (model on a peer, loop local). **Built on the desktop host** (`RoutedModelAdapter`, unit-tested with fakes); a two-Engine run over loopback is not done yet.
2. Android host runs the agent runtime and Binder MCP broker. **Runtime built** (`createEngineAgentRuntime`, shared with desktop; compiles and starts on an emulator, not yet exercised with a model); Binder MCP broker not built.
3. Peer agent endpoints, scope `agent`, MCP tool relay, tool-host taint and approval. **Built** (see Implementation status).
4. Real two-device run (owner's devices). Not done; covered so far by a two-node loopback test.

## Implementation status (2026-11-15)

- **Scope `agent`** (`dev.aidos.aidin.Scope.AGENT`) is never part of the default pairing or group set (`Scope.ALL` excludes it), so pairing alone grants nothing. It is granted per peer with `AidinNode.setPairScopes` (desktop: `DesktopRemoteAgent.setHostingFor`). The node refuses any `/aidin/v1/agent/` request from a peer without it (403 `agent_scope_not_granted`), on top of `delegate`.
- **Peer endpoints** (`AgentPeerEndpoints`): `turns`, `runs/get` (resume by `seq`, long poll), `runs/results`, `runs/cancel`, all over the existing delegated-request frame. A run is owned by (peer node, calling app). The host's model calls are held to `local_only`, so it never delegates onward.
- **Tool relay.** Tools never run on the host. The agent protocol already has the client execute tools (a run stops with pending calls and the caller submits results), so a remote run reuses it: the host's loop stops with a pending call, the origin hands it to the calling app, and the result goes back the same way. No separate relay stream was needed for app tools; the Binder MCP broker for other installed apps is phase 2.
- **Tool-host enforcement** (`OriginToolHost`, at the origin only): a per-peer, per-app allowlist (default none) decides which tools the host is even told about, and a call to anything else is refused; once any tool result has entered a remote run it is tainted locally (peer-reported taint is ignored) and a tool the app did not declare `readOnly` then needs approval, refused by default; the origin's per-app agent grant still gates `/v1/agent` itself; every decision is audited (run, peer, app, tool, outcome, never content).
- **Routing** (`RoutedAgentRuntime`): ordered `[peer..., local]`; a peer is a candidate only when the turn's locality allows the cluster and the peer is connected and trusted; an unreachable peer falls back to the next candidate (never wider); a run stays on the host that started it.
- **Failure.** A dropped link surfaces as 503 on the next call and the app can resume with `GET` by `seq` if the host is still up. Whether a host continues or cancels when the origin disappears is still open (RFC-0108 Phase 5 proposes continue-then-cancel).
- **UI (2026-11-15):** the desktop *Peers* tab (`RemotePeersPane` in aidos-tcr, presenter `DesktopRemotePeersModel`) has the hosting switch, preferred-peer order, per-app tool allowlists and the approval prompt (`ToolApprovalQueue`: answered per call, no answer is a refusal).
- **Not built:** Android screens, the Android host's Binder MCP broker (phase 2), and a two-device run.

## Implementation Files

```yaml
files:
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/RoutedModelAdapter.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/EngineAgentRuntime.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/AgentRoutes.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/AgentRunApi.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/AgentPeerEndpoints.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/RemoteAgentClient.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/RoutedAgentRuntime.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/AgentWire.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/AgentRuntime.kt
  - engine/agenthost/src/test/kotlin/fi/italeino/aidos/engine/agent/RoutedModelAdapterTest.kt
  - engine/agenthost/src/test/kotlin/fi/italeino/aidos/engine/agent/RemoteAgentHostingTest.kt
  - engine/aidin/src/commonMain/kotlin/dev/aidos/aidin/Types.kt
  - engine/aidin/src/jvmAndAndroidMain/kotlin/dev/aidos/aidin/AidinNode.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/http/EngineHttpServer.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopRemoteAgent.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopEngineHost.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopRemoteAgentTest.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopRemotePeersModel.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/ToolApprovalQueue.kt
  - engine/agenthost/src/test/kotlin/fi/italeino/aidos/engine/agent/ToolApprovalQueueTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopRemotePeersModelTest.kt
```
