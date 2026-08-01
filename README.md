# Aidos

An offline-first, open-source AI Operating Environment.

**Aidos is a headless runtime that brings AI agents and tools under your complete control.** Run AI models locally, manage projects and goals, execute tools (Git, shell, custom plugins), and organize output—all on your device, with privacy by default.

## Quick Start

### What is Aidos?

Aidos is a smarter, AI-aware replacement for your terminal, IDE, and knowledge base combined. It:

- **Runs offline** — Full capability without internet; cloud is optional.
- **Respects privacy** — Your data stays on your device by default.
- **Gives you control** — Fine-grained permissions; you decide what each agent can do.
- **Integrates everything** — Git, shell, filesystem, HTTP, custom tools via plugins.
- **Reasons and plans** — AI engines for language, embeddings, voice, and vision.
- **Organizes your work** — Track goals in an editable Intent Graph; sessions run to accomplish them.

### First Time Here?

1. **Understand the architecture:** Read [ARCHITECTURE.md](ARCHITECTURE.md) (2–4 pages). It's a map to the RFCs and explains how the engines fit together.
2. **Dive into RFCs:** Read individual RFCs based on your interests (see ARCHITECTURE.md for recommendations).
3. **Contribute or use:** See [CLAUDE.md](CLAUDE.md) for development practices and ways to help.

### For Different Audiences

**I want to use Aidos:**
→ See [RFC-0050: Android](docs/rfcs/0050-android.md) or [RFC-0051: Desktop](docs/rfcs/0051-desktop.md) for UI plans.
→ [RFC-0099: Roadmap](docs/rfcs/0099-roadmap.md) shows what's coming.

**I want to write plugins/extensions:**
→ [RFC-0060: Plugin SDK](docs/rfcs/0060-plugin-sdk.md).

**I want to contribute to core:**
→ [RFC-0002: Runtime Architecture](docs/rfcs/0002-runtime.md).
→ [CLAUDE.md](CLAUDE.md) for development practices.

**I want to understand the philosophy:**
→ [RFC-0000: Vision](docs/rfcs/0000-vision.md).
→ [RFC-0001: Principles](docs/rfcs/0001-principles.md).

## Key Concepts

**Projects** — Git repositories with an Intent Graph (goals), Resources (docs), and Artifacts (outputs).

**Sessions** — Long-lived agents that run in a project. Each has permissions and can accomplish goals.

**Intent Graph** — Editable, persistent representation of what you want to achieve (not implementation details).

**Tools** — Git, shell, filesystem, HTTP, custom plugins. Unified interface through Tool Broker.

**AI Engine** — Supports LLMs, embeddings, voice, vision. Runs locally by default; cloud is optional.

**Knowledge Engine** — Synthesizes understanding from your project files, docs, and history.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full picture.

## Documentation

**Project overview:**
- [ARCHITECTURE.md](ARCHITECTURE.md) — Map to the RFCs; start here.
- [docs/README.md](docs/README.md) — Documentation structure.
- [docs/principles.md](docs/principles.md) — Ten core principles.
- [docs/vision.md](docs/vision.md) — Mission statement.
- [docs/roadmap.md](docs/roadmap.md) — Four-phase evolution.

**RFCs (25+):**
- All architectural decisions documented as RFCs in [docs/rfcs/](docs/rfcs/).
- See [docs/rfcs/README.md](docs/rfcs/README.md) for RFC process and index.

## Development

**Repository:**
- `docs/` — Documentation, RFCs, architecture.
- `ARCHITECTURE.md` — This document's bigger sibling; navigation guide.
- `CLAUDE.md` — Development practices, contributor guidelines, AI assistance info.

**License:**
Aidos is licensed under the [European Union Public Licence (EUPL-1.2)](LICENSE). This is a strong copyleft license compatible with GPL. You're free to use, modify, and distribute Aidos, with the requirement that modifications remain open source.

See [LICENSE](LICENSE) for full details.

## Contributing

We welcome contributions: code, RFCs, documentation, plugins, ideas.

1. Read [CLAUDE.md](CLAUDE.md) — Development practices and contribution guidelines.
2. Read relevant RFCs (see [ARCHITECTURE.md](ARCHITECTURE.md) for navigation).
3. Open an issue or PR on GitHub.

For AI-assisted development, see [CLAUDE.md](CLAUDE.md).

## Status

**Current phase:** Architecture foundation (Phase 1).

All foundational RFCs (25+) are complete and accepted. Core runtime and first UI implementations are in progress.

See [RFC-0099: Roadmap](docs/rfcs/0099-roadmap.md) for the full project timeline and long-term vision.

## Questions?

- **Architecture questions:** See [ARCHITECTURE.md](ARCHITECTURE.md).
- **RFC questions:** Read the relevant RFC(s) in [docs/rfcs/](docs/rfcs/).
- **Development questions:** See [CLAUDE.md](CLAUDE.md).
- **Open issues:** GitHub issues (coming soon).

---

**Next:** Read [ARCHITECTURE.md](ARCHITECTURE.md) to understand how everything fits together.
