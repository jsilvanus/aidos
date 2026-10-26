# Agent runtime API (desktop host)

Served by the desktop Aidos app on the Engine's loopback port, behind the same bearer token as `/v1/models` (see `~/.aidos/engine.json`, which also carries `"agentApi": "/v1/agent"`). Additive to Engine API v1. Decision: D48.

| Route | Purpose |
|---|---|
| `GET /v1/agent/info` | `apiVersion`, `maxConcurrentRuns`, `maxSteps`, `toolWaitSeconds` |
| `POST /v1/agent/turns` | Start a run |
| `POST /v1/agent/runs/{id}/tool-results` | Answer the pending tool call(s) and continue |
| `GET /v1/agent/runs` | The calling app's runs |
| `GET /v1/agent/runs/{id}?since=<seq>&waitMs=<n>` | Task snapshot; with `since` and `waitMs` (max 30000) it blocks until the run changes past `seq` |
| `DELETE /v1/agent/runs/{id}` | Cancel a run (204; 404 if unknown, or a finished synchronous run) |
| `GET /v1/agent/events` | Server-sent notifications for the calling app's runs |

## Start a run

```json
{
  "model": "<id from /v1/models>",
  "message": "Summarise the unread thread with Anna",
  "systemPrompt": "optional",
  "history": [{"role": "user", "text": "...", "senderId": "optional"}],
  "tools": [{"name": "list_messages", "description": "...", "inputSchema": {"type": "object"}}],
  "maxSteps": 8,
  "contextWindow": 8192
}
```

`model` is required. The user message and history are treated as untrusted input (RFC-0027).

## Response

```json
{ "status": "completed" | "tool_calls" | "failed", "runId": "...",
  "reply": "...", "toolCalls": [{"callId": "call_1", "name": "list_messages", "arguments": {}}],
  "executedToolCalls": [], "steps": 2, "error": null }
```

On `tool_calls`, run the tool in the app and post `{"results": [{"callId": "call_1", "output": "...", "isError": false}]}` to `/v1/agent/runs/{runId}/tool-results`. The reply to that call has the same shape. A tool that is not answered within `toolWaitSeconds` fails with an error the model sees; the run continues.

## Tasks

Every run is a task. Start with `"async": true` to get a `running` snapshot back at once, then observe it with `GET /v1/agent/runs/{id}` (long poll) and answer tool calls with `{"results": [...], "async": true}`. Without `async` each call waits for the next stop point, as above.

`status` is `running`, `tool_calls` (input required), or terminal `completed`, `failed`, `cancelled`. `seq` rises on every change, so a client can ignore snapshots or notifications it has already seen. Terminal states are final. Cancel is cooperative: the status becomes `cancelled` at once, but a model call already in flight may still finish.

A run id is a reference, not authorization: runs belong to the token subject that started them, and another app's run id is a 404. Finished async runs stay readable for 10 minutes (at most 64); synchronous runs are forgotten when delivered. Runs live in memory, so an app restart drops them.

## Notifications

`GET /v1/agent/events` is a `text/event-stream` of `run.updated` events (`{"type","runId","status","seq"}`) for the caller's own runs, with `: ping` comments every 15 s. They are advisory and lossy (no replay): after a reconnect, or when in doubt, reconcile with `GET /v1/agent/runs/{id}`. Progress and log notifications are not offered yet.

## Errors

Same envelope as the rest of the Engine API: `{"error": {"message", "type", "code"}}`. Codes: `invalid_request` (400), `unknown_call` (400), `run_not_found` (404), `run_finished` (409), `too_many_runs` (429).

## Limits and gaps

- At most `maxConcurrentRuns` live runs. State is in memory; a restart of the app drops runs.
- No streaming yet; each call returns at the next point the runtime needs the app, or at the end.
- No per-agent identity or per-tool authorization yet (AGENT-MANAGEMENT.md). Any approved app can run an agent with its own tools.
