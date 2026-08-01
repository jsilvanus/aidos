# CLAUDE.md

This document describes how to work with Claude on Aidos development and how Claude Code operates in this project.

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
├── CLAUDE.md             # This file
├── LICENSE               # EUPL-1.2
├── docs/
│   ├── README.md         # Documentation structure
│   ├── rfcs/             # All RFCs
│   ├── vision.md         # Vision statement
│   ├── principles.md     # Core principles
│   ├── roadmap.md        # High-level roadmap
│   └── ...
├── src/                  # Implementation (Rust, if core)
├── tests/                # Test suite
└── ...
```

### Writing Code

**Principles:**
1. **Minimal** — Do what the RFC says, no more.
2. **Clear** — Code should be readable without excessive comments.
3. **Tested** — New code has tests; old code isn't broken.
4. **Safe** — Rust (for core); memory safety matters.
5. **Documented** — Public APIs have doc comments.

**Comments:**
- Explain *why*, not *what*. (The code shows *what*.)
- Link to RFC sections that motivated the code.
- Mark workarounds and technical debt clearly.

Example:
```rust
// RFC-0003: Session must check permission before invoking tool.
// This guard prevents privilege escalation.
if !session.has_capability(&capability) {
    return Err(PermissionError { ... });
}
```

## Testing Guidelines

### Test Coverage

1. **Unit tests** — Test individual functions/modules.
2. **Integration tests** — Test subsystem interactions.
3. **RFC compliance tests** — Verify implementation matches RFC.

### Running Tests

```bash
# Run all tests
cargo test

# Run specific test
cargo test session::tests::test_create_session

# With output
cargo test -- --nocapture
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
4. **Mention environment** — OS, Rust version, hardware.

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

1. **Read RFC-0000 through RFC-0099** — Understand the process.
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

Environment: macOS 14, Rust 1.75, Aidos commit abc123

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
