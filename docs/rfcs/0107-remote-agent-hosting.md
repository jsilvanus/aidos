# RFC-0107: Remote Agent Hosting over AIDIN

Status: Draft (proposed with D52, 2026-11-10). Not implemented.

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
2. Android host runs the agent runtime and Binder MCP broker.
3. Peer agent endpoints, scope `agent`, MCP tool relay, tool-host taint and approval.
4. Real two-device run (owner's devices).
