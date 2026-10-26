# Agent runtime API (desktop host)

Served by the desktop Aidos app on the Engine's loopback port, behind the same bearer token as `/v1/models` (see `~/.aidos/engine.json`, which also carries `"agentApi": "/v1/agent"`). Additive to Engine API v1. Decision: D48.

| Route | Purpose |
|---|---|
| `GET /v1/agent/info` | `apiVersion`, `maxConcurrentRuns`, `maxSteps`, `toolWaitSeconds` |
| `POST /v1/agent/turns` | Start a run |
| `POST /v1/agent/runs/{id}/tool-results` | Answer the pending tool call(s) and continue |
| `DELETE /v1/agent/runs/{id}` | Cancel a run (204, or 404 if unknown/finished) |

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

## Errors

Same envelope as the rest of the Engine API: `{"error": {"message", "type", "code"}}`. Codes: `invalid_request` (400), `unknown_call` (400), `run_not_found` (404), `too_many_runs` (429).

## Limits and gaps

- At most `maxConcurrentRuns` live runs. State is in memory; a restart of the app drops runs.
- No streaming yet; each call returns at the next point the runtime needs the app, or at the end.
- No per-agent identity or per-tool authorization yet (AGENT-MANAGEMENT.md). Any approved app can run an agent with its own tools.
