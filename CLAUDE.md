# CLAUDE.md

This document describes how to work with Claude on Aidos development and how Claude Code operates in this project.

## Current status

**Read [`PIPELINE.md`](PIPELINE.md) first for the real state of the project.** It is the single
source of truth for what's built, what's next, and the accumulated lessons — this file is process,
PIPELINE.md is status. Don't duplicate status here; if the two ever disagree, PIPELINE.md wins and
this line is the bug.

**RFC status is not implementation status.** An independent codebase review (2026-08-09) found
several RFCs marked Accepted with little or no code behind them (RFC-0043 Plugin Sandbox, RFC-0024
Resource Graph, RFC-0045 Performance Budgets), one credited as done that is an untested stub
(RFC-0012 Intent Graph), and one credited as unbuilt that turned out to have real, tested code
(RFC-0036 Settings). See PIPELINE.md's "Independent codebase review" section for the full list.
**Before implementing against or reporting on an RFC, check the actual code — don't trust a status
line or a milestone checkmark without grepping for the implementation.**

## Philosophy

Aidos is designed to be AI-assisted from day one. These guidelines ensure:

1. **Claude understands context** — RFCs establish architecture; Claude follows them.
2. **Work is traceable** — Commits show what Claude did and why.
3. **Humans stay in control** — Claude suggests; humans decide.
4. **Quality is maintained** — Implementation matches architecture; no shortcuts.

## Development Workflow with Claude

### Before Starting Work

1. **Read the relevant RFC(s)** — Claude will read them. They define what's being built.
2. **Define the task clearly** — "Implement RFC-0032: Git" is clear. "Add Git support" is vague.
3. **Specify constraints** — Timeline, dependencies, scope limits.

### During Development

Claude will:

1. **Read the RFC(s)** — Understand the design and rationale.
2. **Check existing code** — See what's already implemented.
3. **Ask clarifying questions** — If the task is ambiguous.
4. **Implement incrementally** — Small, reviewable changes.
5. **Write clean code** — Minimal, clear, maintainable.
6. **Commit with context** — Commit messages explain *why*, not just *what*.
7. **Test locally** — Run tests, check for regressions.

### Claude's Responsibilities

- **Follow RFCs** — Never contradict established architecture.
- **Maintain style** — Match existing code patterns.
- **Document decisions** — Comments explain non-obvious choices.
- **Write tests** — Code should be testable and tested.
- **Update RFCs if needed** — If implementation reveals design issues, propose RFC updates (not silent changes).
- **Be honest about limitations** — Say "I can't do this" if appropriate.

### Your Responsibilities (As Project Owner)

- **Review PRs** — Even with AI assistance, human review is essential.
- **Define scope** — Tell Claude what matters and what doesn't.
- **Set priorities** — Guide which RFC to implement next.
- **Approve architecture changes** — If an RFC needs updating, you decide.
- **Test on real hardware** — Claude tests locally; real-world testing is yours.

## Contributing with Claude

### If you're a contributor wanting AI assistance:

1. **Use Claude Code CLI or web app** — See https://claude.ai/code.
2. **Clone this repo** — Or attach it to your Claude session.
3. **Read ARCHITECTURE.md** — Understand the big picture.
4. **Read relevant RFC(s)** — Know what you're implementing.
5. **Open an issue or PR** — Describe what you're building.
6. **Work with Claude** — Use these guidelines.
7. **Submit the PR** — With clear commit messages (see below).

### Git Commit Standards

All commits should:

1. **Reference the RFC** — "Implement RFC-0032: Git" or "Fix RFC-0015: Knowledge Engine bug".
2. **Explain the why** — "This allows sessions to manage branches" (not just "Add branch creation").
3. **Include co-author** — If AI-assisted:
   ```
   Implement RFC-0032: Git tool

   Add branch creation, checkout, and status operations.
   Enables sessions to manage project versioning.

   Co-Authored-By: Claude [version] <noreply@anthropic.com>
   Claude-Session: https://claude.ai/code/session_...
   ```

4. **Be atomic** — One logical change per commit.
5. **Test before committing** — "All tests pass" is baseline.

## RFC-Driven Development

Aidos uses RFCs to drive implementation. This means:

1. **RFCs come first** — Design is agreed before coding.
2. **Implementation follows RFC** — Code realizes the RFC design.
3. **RFCs are stable** — Once accepted, they're the source of truth.
4. **Deviations are exceptions** — If implementation reveals problems, update the RFC first, then code.

### If you find an RFC problem:

1. **Document it** — In a PR comment or issue.
2. **Propose an update** — Specific RFC text change.
3. **Get approval** — Project owner reviews.
4. **Update RFC** — In a separate commit from implementation.
5. **Update code** — Now it matches the revised RFC.

## Code Organization

### Directories

```
/
├── README.md              # Quick start
├── ARCHITECTURE.md        # Map to RFCs
├── CLAUDE.md              # This file
├── PIPELINE.md            # Working status: what's built, what's next (source of truth)
├── LICENSE                # EUPL-1.2
├── docs/
│   ├── README.md          # Documentation structure
│   ├── rfcs/              # All RFCs (0000-0102)
│   ├── decisions.md       # Settled architecture decisions (D1-D34+)
│   ├── mvp-roadmap.md     # Milestones, RFCs, and done-when conditions
│   └── ...
├── schema/                 # Canonical SQLite DDL (governs; RFC DDL defers to it), check.py
├── kernel/                 # Contract types only, no implementations (frozen at G0).
│                           # A repo-root module, NOT agent/kernel — both agent/ and engine/
│                           # include it by path (`project(":kernel").projectDir`) so the two
│                           # Gradle roots share one definition instead of two drifting copies.
├── agent/                  # Aidos Agent (RFC-0103) — Kotlin Multiplatform Gradle project
│   ├── androidapp/         # Phase 4: Android app (Compose UI, foreground service host)
│   ├── cli/                # CLI frontend
│   ├── mcp-core/           # RFC-0031: JSON-RPC + stdio/HTTP transports. Kernel-free by
│   │                       # decision, so it can be consumed outside this repo.
│   ├── mcp-policy/         # RFC-0031: transport→effect/permission classification, endpoint
│   │                       # validation, trust rules. Also kernel-free.
│   ├── mcp-broker/         # RFC-0031: the kernel binding — ToolDescriptor rendering, Tool impl
│   └── ...                 # capability, broker, executor, storage, git, filesystem, vault,
│                            # prompt, agentloop, routing, worker, retention, knowledge,
│                            # settings, identity, lock, memory, daemon, etc. — one module
│                            # per subsystem, each with its own tests
├── engine/                 # Aidos Engine Core (RFC-0103) — Kotlin Multiplatform Gradle project
│   ├── modelruntime/       # Model loading and inference runtime
│   ├── cookbook/           # Model recipes and configuration
│   ├── huggingface/        # HuggingFace model support
│   ├── downloads/          # Model download management
│   ├── models/             # Model abstraction layer
│   ├── voice/              # STT/TTS voice support
│   └── androidapp/         # Aidos Engine Android app (foreground service host)
├── sdk/                    # Aidos SDK (RFC-0103) — Android client library
│   └── ...                 # Single-module library for Engine client integration
└── ...
```

### Writing Code

**Principles:**
1. **Minimal** — Do what the RFC says, no more.
2. **Clear** — Code should be readable without excessive comments.
3. **Tested** — New code has tests; old code isn't broken.
4. **Safe** — Kotlin (for core, Kotlin Multiplatform); `allWarningsAsErrors` in **18 modules**:
   `kernel` plus `api`, `broker`, `capability`, `cli`, `daemon`, `executor`, `filesystem`, `git`,
   `http`, `identity`, `knowledge`, `lock`, `mcp-broker`, `mcp-core`, `mcp-policy`, `settings`,
   `storage`. A compiler warning in any of these is a build failure, not advice — expect
   "Unnecessary non-null assertion", "No cast needed", and override-parameter-name mismatches to
   fail CI. Check the module's own `build.gradle.kts` before assuming a warning is harmless.
5. **Documented** — Public APIs have doc comments.

**Comments:**
- Explain *why*, not *what*. (The code shows *what*.)
- Link to RFC sections that motivated the code.
- Mark workarounds and technical debt clearly.

Example:
```kotlin
// RFC-0003: Session must check permission before invoking tool.
// This guard prevents privilege escalation.
if (!session.hasCapability(capability)) {
    throw PermissionError(...)
}
```

## Testing Guidelines

### Test Coverage

1. **Unit tests** — Test individual functions/modules.
2. **Integration tests** — Test subsystem interactions.
3. **RFC compliance tests** — Verify implementation matches RFC.

### Running Tests

```bash
# Canonical DDL check — must pass before and after any schema/RFC change
python3 schema/check.py

# Build and run all module tests (Aidos Agent)
cd agent && gradle build

# What CI actually runs for the Agent (.github/workflows/ci.yml, test-agent job).
# Prefer this when checking whether a change is green: --continue enumerates every
# failure instead of stopping at the first, which matters because a module's tests
# cannot compile until its main source does — fixing one break routinely reveals
# the next.
cd agent && gradle jvmTest --continue

# Run a specific module's tests (Aidos Agent).
# NOTE: these are Kotlin Multiplatform modules with a jvm() target, so the task is
# `jvmTest`. Plain `:executor:test` does not exist and fails with
# "task 'test' not found in project ':executor'".
cd agent && gradle :executor:jvmTest

# With output
cd agent && gradle :executor:jvmTest --info

# Aidos Engine and SDK are built similarly from their respective directories:
# cd engine && gradle build
# cd sdk && gradle build
```

### What to Test

- Happy path (normal operation)
- Error cases (permissions denied, invalid input, timeout)
- Edge cases (empty, very large, concurrent)
- RFC compliance (behavior matches RFC-stated semantics)

## Performance Considerations

1. **Measure before optimizing** — Don't guess.
2. **Target latency** — Sessions should respond in < 200ms for typical operations.
3. **Memory** — Local models require efficient memory use.
4. **Offline** — Assume network is unavailable; cache aggressively.

## Security Considerations

1. **Capability-based access control** — RFC-0003 is law. Every tool invocation checks permissions.
2. **Input validation** — Never trust session input. Validate at tool boundaries.
3. **Secrets** — Never hardcode API keys. Use secure storage.
4. **Audit logging** — Every operation should be loggable (for forensics).
5. **Sandboxing** — Tools run in isolated contexts (where possible).

See [RFC-0003: Security](docs/rfcs/0003-security.md) for details.

## Working with AI Models

### Local Models (RFC-0022)

- Prefer offline-first.
- Use GGUF format when possible (quantizable, portable).
- Test on mid-range hardware (not just high-end GPUs).
- Document model sources and licenses.

### Remote Models (RFC-0023)

- Privacy approval required before sending data to cloud.
- Implement redaction/summarization to minimize data sent.
- Log all remote queries for auditing.
- Fallback to local model if cloud unavailable.

## Debugging with Claude

### When things go wrong:

1. **Provide context** — Error message, code snippet, RFC reference.
2. **Describe the behavior** — What did you expect? What happened?
3. **Show recent commits** — What changed?
4. **Mention environment** — OS, JDK/Kotlin version, hardware (phone model if Android-specific).

Claude will:
- Read the error and code
- Check RFCs for design intent
- Trace the issue
- Suggest fixes
- Verify the fix works

### Common issues:

**"My code doesn't match the RFC"**
→ Check RFC's Design section. Re-read the RFC if confused.

**"Tests are failing"**
→ Run locally first. Check test error message carefully. Reproduce without Claude.

**"Performance is poor"**
→ Measure (don't guess). Profile. Identify bottleneck. Then optimize that specific part.

**"I don't understand why X is designed this way"**
→ Read the Motivation section of the relevant RFC. That explains the *why*.

## Contributing Documentation

### RFCs

RFCs are frozen after acceptance (by design). To propose changes:

1. **Read `docs/rfcs/README.md` and RFC-0099 (roadmap)** — understand the process and the current
   milestone plan before proposing changes.
2. **Propose RFC change** — File issue with specific text changes.
3. **Get approval** — Project owner reviews.
4. **Update RFC** — In a commit.

### Code Comments

1. **Link to RFCs** — "See RFC-0030: Tool Broker" in comments.
2. **Explain trade-offs** — "We chose Vec over LinkedList because..."
3. **Mark technical debt** — "TODO: Optimize per RFC-0015 Future Work section".

### Examples and Tutorials

- Keep them up-to-date with RFCs.
- Link to relevant RFCs in examples.
- Use real-world scenarios.

## Reporting Issues

### Good Issue Report

```
Title: RFC-0032: Git worktree creation fails with unicode paths

Description:
When I try to create a worktree with a unicode character in the path:
  worktree.create("project/тест", branch="main")
Result: Error: "invalid path"

Expected: Worktree created successfully.

Environment: macOS 14, JDK 17, Aidos commit abc123

Relevant RFC: RFC-0032: Git (Design section, Worktree Support)
```

### Bad Issue Report

```
Title: Git is broken

Description: Nothing works. Pls fix.
```

## Support and Questions

### RFC Questions
→ Read [ARCHITECTURE.md](ARCHITECTURE.md) and the relevant RFC.
→ Comment in RFC pull request if design needs clarification.

### Implementation Questions
→ Open an issue with code snippet and expected behavior.
→ Link to relevant RFC.

### Design Proposals
→ File an issue with "RFC Proposal" tag.
→ Explain motivation, goals, and trade-offs.
→ Reference existing RFCs.

### Working with Claude on Issues
→ Share the issue link or paste the text.
→ Provide commit hash or branch name.
→ Ask specific questions ("Why is X designed this way?" vs. "Help me debug").

## Code Review Checklist

Before reviewing any PR:

1. **Does it match the RFC?** — Compare code against RFC's Design section.
2. **Are tests included?** — New code should have tests.
3. **Is it minimal?** — No extra features beyond what the RFC says.
4. **Are commits clear?** — Commit messages explain *why*.
5. **Is error handling present?** — What happens when things go wrong?
6. **Are permissions checked?** — RFC-0003 compliance.
7. **Is it documented?** — Code comments link to RFCs where relevant.

## License

All contributions to Aidos are licensed under the [EUPL-1.2 License](LICENSE). By contributing, you agree to this licensing.

The EUPL is a strong copyleft license compatible with the GPL. It ensures Aidos and all derivative works remain open source.

## Summary

**The core principle:** RFC → Code → Test → Commit → Review → Merge.

Claude is a force multiplier for this process. It can read RFCs, implement designs, write tests, and suggest improvements. But humans decide what gets built and review the results.

Together, with this workflow, Aidos can scale from one person to a community without losing coherence or quality.

---

**Ready to start?** Pick an RFC from [ARCHITECTURE.md](ARCHITECTURE.md), describe what you want to build, and get started.
