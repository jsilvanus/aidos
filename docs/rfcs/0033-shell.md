# RFC-0033: Shell

Status: Draft

## Abstract

The Shell tool allows sessions to execute shell commands. Shell access is gated by explicit permission (RFC-0003). Commands run in sandboxed environments with timeouts, working directory restrictions, and captured output. Shell execution is logged and auditable. The shell tool is powerful but dangerous, so strict controls are necessary.

## Motivation

Some tasks require direct command execution: running tests, building projects, invoking scripts. The Shell tool enables this while maintaining security through permissions and sandboxing.

## Goals

1. **Define shell invocation**: How do sessions run commands?
2. **Specify sandboxing**: How are commands isolated?
3. **Establish timeouts and limits**: Prevent runaway processes.
4. **Define working directory scoping**: Prevent access outside project.
5. **Clarify output capture**: How is stdout/stderr handled?
6. **Explain logging**: Every command execution is logged.

## Design

### Shell Tool Interface

```
Tool: "shell"
Capability: "shell:exec"

invoke("shell:exec", {
  command: "cargo test",
  working_directory: "/project/tests",
  timeout_seconds: 300,
  capture_output: true
})

Result: {
  exit_code: 0,
  stdout: "...",
  stderr: "...",
  duration_ms: 5000
}
```

### Sandboxing

Commands run in restricted environments:

1. **Working directory**: Limited to project or session workspace.
2. **Environment**: Minimal environment (no sensitive vars).
3. **File access**: Only project files visible.
4. **Network**: Blocked (or configurable).
5. **Process isolation**: Separate process, kill on timeout.

### Timeouts and Limits

Each command has limits:

```
Default timeout: 300 seconds
Max output: 10MB
Max processes: 1 per command
```

If timeout expires, process is killed.

### Output Handling

Stdout and stderr are captured:

```
Result: {
  exit_code: 0,
  stdout: [captured output, max 10MB],
  stderr: [captured errors],
  duration_ms: 5000
}
```

### Logging

Every execution is logged:

```
ShellLog {
  timestamp: Timestamp,
  session_id: UUID,
  command: String,           # What was run
  working_directory: String,
  exit_code: Int,
  duration_ms: Int,
  output_size_bytes: Int
}
```

## Security

Shell execution is dangerous. Controls:

1. **Explicit permission**: Session must have `shell:exec`.
2. **Working directory restriction**: Prevent `rm -rf /`.
3. **Timeout enforcement**: Kill runaway processes.
4. **Output redaction**: Redact secrets from logs.
5. **Audit logging**: Every execution logged.
6. **Confirmation prompts** (future): Ask before dangerous commands.

## MVP Scope

MVP includes:

1. **Basic execution**: Run commands, capture output.
2. **Timeouts**: Kill long-running processes.
3. **Working directory restriction**: Limit to project.
4. **Logging**: Log all executions.
5. **Error handling**: Capture exit codes and errors.

Not included:

- Container execution (future).
- Interactive shell (future).
- Shell pipelines (run as single command).
- Signal handling (future).

## Future Work

- **Sandboxing**: Use containers, seccomp, chroot.
- **Interactive shell**: SSH-like interactive sessions.
- **Confirmation prompts**: Ask for dangerous commands (`rm`, `dd`).
- **Resource limits**: CPU, memory quotas.
- **Pipeline support**: Full shell syntax.

## Open Questions

- Should dangerous commands be blocked (blacklist)?
- Should shell output size be limited to prevent memory issues?
- Should interactive commands be supported?
- Should environment variables be accessible (with permission)?
