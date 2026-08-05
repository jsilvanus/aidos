# RFC-0030: Tool Broker

Status: Accepted 2026-08-03

## Abstract

The Tool Broker is the subsystem through which sessions access external systems and tools. Everything external—Git, the filesystem, shell commands, HTTP requests, notifications, calendars, email, MCP servers—is a Tool. The Tool Broker provides a unified interface for tool invocation, enforces capability-based permissions (RFC-0003), publishes events when tools complete (RFC-0004), and logs all tool usage for auditing. Tools enable sessions to interact with the external world while maintaining security and auditability.

## Motivation

Sessions need to interact with many external systems:

- **Version control**: Commit code, create branches, push changes (Git).
- **File I/O**: Read, write, delete files; create directories.
- **Shell execution**: Run commands, capture output.
- **Network**: Make HTTP requests, fetch data.
- **Notifications**: Send alerts to the user or external systems.
- **Calendar**: Check schedules, add events.
- **Email**: Send mail, through a provider adapter (SMTP, or a hosted API). Post-MVP, and the
  first tool that needs its own provider abstraction the way models do (RFC-0021) — worth
  noting because it is the precedent for any later tool with interchangeable backends. Every
  send is `EffectKind.Egress` and `RecoveryClass.UNSAFE`: it cannot be un-sent and cannot be
  observed after the fact, so it is never retried.
- **Future integrations**: Slack, Discord, GitHub APIs, CI systems, etc.

Without a unified abstraction, the runtime would need to hardcode knowledge of every tool. The Tool Broker solves this by:

1. **Abstracting interfaces**: Every tool has a standard interface.
2. **Enforcing permissions**: Each tool access is checked against capabilities.
3. **Logging execution**: Every tool invocation is logged.
4. **Publishing events**: Tool completion triggers events for interested sessions.
5. **Enabling extensibility**: MCP servers register as tools (RFC-0031 — stdio on desktop,
   streamable HTTP on every profile, both in the MVP per D17). A plugin host is **not** in v1
   (D18), so RFC-0060 describes a surface nothing implements yet.

## Goals

1. **Define tool interface**: What methods must tools implement?

2. **Establish permission model**: How are tool capabilities granted and checked?

3. **Specify event publishing**: How do tools generate completion events?

4. **Define logging and auditing**: How is tool execution logged?

5. **Clarify tool lifecycle**: How are tools registered, initialized, and shutdown?

6. **Explain error handling**: How are tool failures handled?

## Non-goals

This RFC does not specify exact implementations of individual tools (Git, filesystem, etc.). Each is a separate RFC.

This RFC does not define the user-facing API for invoking tools. That is implementation detail.

This RFC does not address distributed tool execution (tools on remote machines). That is future work.

## Design

### Terminology: Operation, not Capability

A tool provides **Operations**. `Capability` means one thing only in Aidos: a security grant
(RFC-0018). The word previously named three different concepts — a security grant, a tool
operation descriptor, and a model class — and they collided in the same modules and in the same
security reasoning. Model classes are `ModelKind` (RFC-0020); tool operations are `Operation`.

### Tool Interface

```kotlin
interface Tool {
    val id: String                       // "git", "fs", "shell"
    val version: String
    fun operations(): List<ToolDescriptor>              // RFC-0008; JSON Schema per operation

    suspend fun execute(
        handle: ResourceHandle,          // carries the capability's scope (RFC-0018)
        operation: String,
        arguments: JsonObject            // already schema-validated by the loop
    ): ToolCallResult                    // RFC-0008

    suspend fun preview(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject
    ): Result<Preview>                   // required for Mutate effects; see below

    suspend fun cancel(operationId: String)
}
```

Three changes from the earlier interface, each fixing a concrete gap:

- **No `session_id` parameter.** A tool does not need to know which session called it, and
  passing it invited tools to make their own authority decisions. Scope arrives in the handle.
- **`cancel` exists.** RFC-0006 specifies that "the Tool Broker is instructed to cancel the
  operation"; there was no such method.
- **`arguments` are validated before arrival**, against the operation's JSON Schema (RFC-0008),
  so tools do not each re-implement parameter validation and disagree about it.

### Typed effects

Every operation declares an effect. Untyped tools made preview, undo, retry, approval, and
audit impossible to implement generically, because the broker had no idea what an operation
would do.

```kotlin
sealed interface EffectKind {
    // Observes state. Cacheable, retryable, no approval.
    object Read : EffectKind

    // Changes state. Previewable; approval depends on scope, taint, and reversibility.
    data class Mutate(val scope: MutationScope, val reversible: Boolean = true) : EffectKind

    // Sends data outside the device. Subject to egress policy and taint attenuation.
    data class Egress(val destination: String) : EffectKind

    // Reaches the user. Rate-limited, never silently repeated.
    object Notify : EffectKind
}
```

Behaviour is derived from the effect, uniformly:

| Effect | Approval | Preview | Retry | Taint attenuation (RFC-0027) |
|---|---|---|---|---|
| `Read` | no | n/a | yes | none |
| `Mutate` in project, reversible | no | **required** | if idempotent | preview recorded |
| `Mutate` in project, **irreversible** | yes — readback tier (D26) | required | if idempotent | **denied** |
| `Mutate` outside project | yes | required | if idempotent | **denied** |
| `Egress` | per capability | request shown | `UNSAFE` in practice, so no | **per-call approval** |
| `Notify` | no | n/a | **never** | none |

**`reversible` is a separate axis from `RecoveryClass`.** The first asks whether the *user* can
get their work back; the second asks whether the *executor* may re-run the effect after a crash.
A branch switch discarding uncommitted changes is in-project and perfectly re-runnable, and it
annihilates an hour of typing — which is why conflating them let it into D26's benign class
(RFC-0053).

**Preview** returns the effect a mutation *would* have — a diff for file writes, a patch for Git
operations, a description otherwise — without performing it. It powers dry-run mode, the
approval dialog, and the audit record. Requiring it for `Mutate` is what makes "show me what
the agent is about to do" a runtime feature rather than a per-tool courtesy.

**`Preview.Diff` carries a structured `FileDiff` (RFC-0052), not a unified-diff string.** A
mid-Run approval card and a commit-time hunk card are the same decision at different moments and
are rendered by the same component (RFC-0050, D25). Handing the component a string in one path
and structure in the other would put a diff parser in every frontend, which is the outcome D25
exists to prevent.

### Idempotency and recovery

Every operation additionally declares a `recovery_class` (RFC-0009): `PURE`, `IDEMPOTENT`,
`CHECKABLE`, or `UNSAFE`. This is the contract that lets the executor decide, after a crash,
whether an effect may be re-run. `UNSAFE` operations — `git push`, notifications, outbound HTTP
— are never retried automatically.

Retryable operations carry an `idempotency_key` supplied by the executor.

### Permission Enforcement

Tools are gated by capabilities (RFC-0003):

The order is fixed and is the same for every tool (RFC-0008):

```
1. validate arguments against the operation's JSON Schema
2. resolve the capability the caller NAMED — ToolCall.capabilityId, not a search
3. apply taint attenuation (RFC-0027) and the approval tier (D26)
4. reserve budget (RFC-0028)
5. preview, if the effect is Mutate
6. write the audit row
7. execute
8. taint the Run from the result's trust level
```

Two things about step 2 that the previous version got wrong. Authority is a **named capability
exercised**, not a permission string matched — the caller says which grant it is using, and a
call naming none is denied rather than searched for a grant that would allow it. And the check
happens against a `ResourceHandle` whose scope was fixed at grant time, so "sanitize the
arguments" is not the defence; the handle is (RFC-0018).

A session cannot bypass this. There is one path.

### Event Publishing

Tools publish events when they complete:

```
Git tool completes commit:
  Event: {
    type: "ToolCompleted",
    source: "tool:git",
    payload: {
      tool_id: "git",
      capability: "git:write",
      operation: "commit",
      success: true,
      commit_hash: "abc123...",
      files_changed: 3
    }
  }
  
Other sessions subscribed to "tool:git:*" are woken
```

Events enable:

- **Coordination**: Sessions react to tool completion.
- **Monitoring**: Watch tool usage in real-time.
- **Automation**: Trigger actions based on tool results.

### Logging and Auditing

Every tool invocation is logged:

Two records, not one, and `schema/project.sql` is canonical for both:

- **`audit_log`** — the permanent security record: actor, capability exercised, subject, outcome.
  Append-only, `PERMANENT` retention, never compacted (RFC-0003, RFC-0056).
- **`tool_calls`** — the operational record joined to the Execution Graph: which Attempt, which
  arguments, what came back. Subject to retention like the rest of the graph.

They are separate because they have different lifetimes and different readers. Collapsing them
into one `ToolLog` — as the previous version did — means either compacting the audit trail or
retaining every tool argument forever.

`session_id` is recorded on both and passed to neither. The tool never learns who called it.

Logs enable:

- **Audit trail**: What did sessions do?
- **Debugging**: Why did a command fail?
- **Security**: Detect unauthorized access attempts.
- **Resource accounting**: Track usage.

### Tool Registration and Discovery

Tools are registered at runtime:

```
Built-in tools (MVP):
  - git
  - filesystem
  - shell
  - http

Tool registration:
  Tool implements Tool interface
  Tool registers with Tool Broker
  Tool becomes available to sessions
  Capabilities advertised
  
External tools (via MCP or plugins):
  MCP servers register as tools
  Plugin-provided tools register
```

### Error Handling

Tool failures are handled gracefully:

```
Tool invocation fails:
  1. Tool catches error
  2. Returns ToolResult { success: false, error_message: "..." }
  3. Tool Broker logs failure
  4. Publishes ToolFailed event
  5. Session handles failure or retries
```

Sessions can decide whether to:

- **Retry**: Try the operation again.
- **Fallback**: Use an alternative approach.
- **Escalate**: Report error to user.
- **Ignore**: Proceed despite failure.

### Telling the model how to read a result

`ToolDescriptor.description` and `inputSchema` say how to **call** an operation. Nothing said how
to **read** what comes back, and for some tools that is the harder half.

A knowledge query returning ranked matches with similarity scores is the clear case. A score of
`0.4` is weak evidence; a model with no guidance reports it as a finding. That is D6 in a new
costume — the model confirming its own success on evidence that does not support it — and it is
not fixed by a better `description`, because the problem appears after the call, not before it.

So `ToolDescriptor` carries **`resultGuidance`**: a short, runtime-authored statement of what the
result shape means, what is significant, useful thresholds, caveats, and what a citation should
look like. It is emitted **with the result**, not with the tool definition, so the MCP-shaped
surface (D23) is unchanged and no server sees a field it does not understand.

**It is runtime-authored and `TRUSTED`, and a tool never supplies its own.** An MCP server is an
`UNTRUSTED` subject (RFC-0027); letting one describe how to weigh its own output would hand an
untrusted party the interpretation of its own evidence. For MCP-sourced tools `resultGuidance` is
either absent or written by Aidos.

**Prior art worth copying, not just referencing.** `jsilvanus/gitsema` keeps exactly this split —
`guideTools.ts` for how to call, `interpretations.ts` for how to read — as one registry feeding
three consumers, with a `docsSync` test that fails when the committed skill file drifts from the
source of truth. That discipline is the same one `schema/check.py` enforces here, and it is worth
carrying over along with the content (RFC-0038).

## Data Model (Conceptual)

```
ToolBroker {
  project_id: UUID
  
  tools: Map<ToolId, Tool>
  tool_registry: ToolRegistry
  
  logs: List<ToolLog>
  active_operations: Map<OpId, ActiveOp>
}

ToolRegistry {
  tools: List<ToolDescriptor>
  capabilities: Map<CapabilityId, Capability>
  
  version: String
  last_updated: Timestamp
}

ActiveOp {
  id: UUID
  tool_id: String
  session_id: UUID
  
  started_at: Timestamp
  timeout: Duration
  
  status: OperationStatus
}
```

## Security

The Tool Broker enforces security via:

1. **Capability checks**: Every operation verified against permissions.
2. **Input validation**: Tool inputs are validated before execution.
3. **Isolation**: built-in tools run in-process and are trusted code; isolation comes from the
   handle they are given, not from a sandbox. There is no plugin sandbox in v1 (D18), and
   claiming "sandboxed where possible" overstated what exists.
4. **Audit logging**: All operations logged.
5. **Timeout enforcement**: Long-running tools are interrupted.

## MVP Scope

MVP includes:

1. **Filesystem tool**: read, write, list, via `DirHandle` (RFC-0018).
2. **Git tool**: status, diff, log, read-at-ref, stage, commit, branch (RFC-0032, RFC-0053).
3. **Shell tool**: DESKTOP profile only (RFC-0049); absent on MOBILE.
4. Typed effects with `preview()` for every `Mutate`.
5. JSON Schema per operation; validation before capability resolution.
6. `recovery_class` per operation; `cancel()`.
7. Capability validation by named capability ID; handles for hierarchical scopes.
8. Audit record per invocation; `SIGNAL` progress events.

Not included:

- HTTP tool, notification tool.
- MCP adapter (RFC-0031 — stdio on desktop, HTTP on every profile).
- `CHECKABLE` recovery probes.
- Read caching across steps.

## Future Work

### Tool Middleware

Intercept and modify tool calls:

```
Middleware 1: Logging (log all calls)
Middleware 2: Caching (cache results)
Middleware 3: Retry (retry on failure)
Middleware 4: Audit (audit sensitive calls)

Tool call flow:
  Session → Middleware chain → Tool → Result
```

### Tool Composition

Combine tools in pipelines:

```
Pipeline: "update and commit"
  1. filesystem: write file
  2. git: stage
  3. git: commit
  4. git: push

Or: "test and report"
  1. shell: run tests
  2. shell: capture output
  3. http: POST results to CI
```

### Smart Retries

Automatically retry failed operations:

```
Tool fails: "connection timeout"
Broker retries: 3 times, exponential backoff
Log: Number of retries, success on attempt N
```

### Tool Profiling

Monitor tool performance:

```
Frequently slow tool: "shell" 
Reason: Docker container startup overhead
Suggestion: Keep container running between calls
```

### Rate Limiting

Control tool usage:

```
Shell tool: Max 100 commands/minute
Git tool: Max 10 commits/minute
HTTP tool: Max 50 requests/minute
```

## Resolved questions

Several of these were open questions; they are now decided, because each affects the tool
interface and could not be deferred past the first tool.

- **Dry-run for destructive tools** — yes, and mandatory: `preview()` is required for every
  `Mutate` effect. This was previously listed as an open question while being the mechanism the
  approval UI depends on.
- **Tool timeouts** — per request, bounded by the capability's `maxDurationSeconds`. Per-tool
  defaults are a configuration detail.
- **Batching** — no. The agent loop executes tool calls sequentially (RFC-0008) so that the
  audit order is the execution order.
- **Partial results while executing** — as `SIGNAL` events (RFC-0004), which are not persisted.
  The Task result is always the complete result.
- **Transactions and rollback** — no general mechanism. Compensation is modelled in the
  Execution Graph (RFC-0019) where it is meaningful, and `preview()` plus Git history covers
  the practical need for the common case.

- **Tool versioning is resolved silently by the broker** (2026-08-03), not exposed as a
  different `ToolDescriptor` per version. The version is recorded on the attempt for provenance.
  A model that can see tool versions will condition on them, and then behaviour depends on a
  number nobody meant to be an interface.
- **`Read` results are not cached across steps in the MVP** (2026-08-03). A cached read can serve
  content the user changed underneath it, which is the whole reason RFC-0053's reconciliation
  exists. Measure first; if reads prove expensive, key any cache on the **blob hash** rather than
  the path, so it invalidates for free.

## Open Questions

None.
