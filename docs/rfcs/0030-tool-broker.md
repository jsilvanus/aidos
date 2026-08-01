# RFC-0030: Tool Broker

Status: Accepted

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
- **Email**: Send emails to collaborators.
- **Future integrations**: Slack, Discord, GitHub APIs, CI systems, etc.

Without a unified abstraction, the runtime would need to hardcode knowledge of every tool. The Tool Broker solves this by:

1. **Abstracting interfaces**: Every tool has a standard interface.
2. **Enforcing permissions**: Each tool access is checked against capabilities.
3. **Logging execution**: Every tool invocation is logged.
4. **Publishing events**: Tool completion triggers events for interested sessions.
5. **Enabling extensibility**: New tools can be added via the plugin system (RFC-0060).

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

### Tool Interface

All tools implement a standard interface:

```
Tool {
  id: String                        # "git", "filesystem", "shell"
  name: String                      # Human-readable name
  version: String                   # Tool version
  
  /// List capabilities this tool provides
  list_capabilities() -> List<Capability>
  
  /// Check if a capability exists
  has_capability(capability_id: String) -> Boolean
  
  /// Invoke a tool operation
  invoke(
    capability_id: String,
    request: ToolRequest,
    session_id: UUID
  ) -> ToolResult
  
  /// Stream results for long-running operations
  invoke_streaming(
    capability_id: String,
    request: ToolRequest,
    session_id: UUID,
    callback: StreamCallback
  ) -> void
  
  /// Subscribe to events from this tool
  subscribe(topic: String) -> Subscription
}

ToolRequest {
  capability_id: String             # What capability is being used
  parameters: Map<String, Any>      # Request parameters
  context: Map<String, Any>?        # Optional context
}

ToolResult {
  success: Boolean
  output: Any                       # Result of operation
  error_message: String?
  metadata: Map<String, Any> {
    execution_time_ms: Int
    resource_used: String?
  }
}

Capability {
  id: String                        # "git:write", "filesystem:delete"
  name: String
  description: String?
  
  requires_permission: String?      # Capability name from RFC-0003
  
  parameters: List<Parameter>
}
```

### Permission Enforcement

Tools are gated by capabilities (RFC-0003):

```
Session requests: "Run command: rm -rf /"

Tool Broker:
  1. Check session capabilities: Does session have "shell:exec"?
  2. If no: Deny with PermissionError
  3. If yes:
     a. Check parameters: Command sanitization
     b. Invoke tool
     c. Log execution
     d. Publish event
```

Permission checks happen before tool invocation. A session cannot bypass permissions.

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

```
ToolLog {
  timestamp: Timestamp
  session_id: UUID
  
  tool_id: String
  capability: String
  
  request: ToolRequest              # Redacted if sensitive
  result: ToolResult
  
  success: Boolean
  error: String?
  
  duration_ms: Int
  resources_used: Map<String, Any>
}
```

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
3. **Isolation**: Tools run in sandboxed contexts (where possible).
4. **Audit logging**: All operations logged.
5. **Timeout enforcement**: Long-running tools are interrupted.

## MVP Scope

MVP includes:

1. **Git tool**: Version control operations.
2. **Filesystem tool**: File I/O operations.
3. **Shell tool**: Command execution (sandboxed).
4. **Basic permission checks**: Enforce capabilities.
5. **Logging**: Log all tool invocations.
6. **Event publishing**: Generate completion events.

Not included:

- HTTP tool (future).
- Notification tool (future).
- MCP tool adapter (future, RFC-0031 covers MCP).
- Advanced sandboxing (future).

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

## Open Questions

- Should tools support transactions (rollback if something fails)?
- Should there be tool versioning (different versions of Git tool)?
- Should tool timeouts be configurable per tool or per request?
- Should tools support batching (multiple operations in one call)?
- Should there be a "dry-run" mode for destructive tools?
- Should tools publish partial results while still executing?
