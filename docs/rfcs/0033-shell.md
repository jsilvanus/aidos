# RFC-0033: Shell

Status: Draft

## Abstract

The Shell tool allows sessions to execute shell commands on platform profiles that support it.
Shell access is gated by explicit capability (RFC-0018), runs with a working directory fixed by
the capability scope, and is logged and auditable. **Shell is a `PLATFORM`-tier tool: it exists
on DESKTOP and HEADLESS_SERVER, and does not exist on MOBILE** (RFC-0049).

## Motivation

Some tasks require direct command execution: running tests, building projects, invoking
scripts. The Shell tool enables this where the platform allows it.

### Shell is not available on the first platform, and that is by design

Android provides no general shell and forbids executing arbitrary binaries. Rather than
pretending otherwise or treating this as a defect, the architecture treats shell as a declared,
tiered capability:

- The primary Aidos use case — making progress on Git projects offline from a phone — is
  reading, understanding, planning, editing, reviewing, and committing. None of it requires a
  shell.
- On MOBILE, the model is never *told* the shell tool exists (RFC-0008 filters descriptors by
  availability), so it never proposes it, and the user never sees a denial for something that
  could not have worked.
- A project that declares `tools = ["shell"]` reports shell as degraded or unsatisfied when
  opened on a phone, **before** any session spends tokens (RFC-0049).
- Work started on a phone remains completable: a Run that could not run tests still produces a
  commit, and the Execution Graph records what was skipped so a later Run on a capable device
  can pick it up.

Where a narrow native capability is genuinely valuable on mobile — fast content search, for
example — it ships as a `BUNDLED` tool with a typed effect and its own capability, fixed at
build time. `BUNDLED` is deliberately not a shell with extra steps: no interpreter is ever
bundled, and nothing executable arrives from a project.

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
