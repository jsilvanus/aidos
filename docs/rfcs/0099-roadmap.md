# RFC-0099: Roadmap

Status: Accepted

## Abstract

The Aidos roadmap spans four phases: Architecture (foundation and core concepts), Android MVP (first UI), Desktop & Extensions (power user tools and ecosystem), Platform (distributed AI, cross-device, life management). This RFC outlines the multi-year vision, key milestones per phase, and long-term aspirations for Aidos as an AI operating environment.

## Motivation

A roadmap provides clarity:

1. **Stakeholders**: Understand project direction and timeline.
2. **Contributors**: Know what to work on and how it fits.
3. **Users**: Anticipate features and prioritization.
4. **Investors**: See long-term vision and market opportunity.
5. **Community**: Support is more informed and engaged.

The roadmap balances ambition (long-term vision) with pragmatism (MVP first).

## Goals

1. **Define phases**: What are the major milestones?
2. **Clarify timeline**: Rough estimates for each phase.
3. **Specify deliverables**: What ships in each phase?
4. **Explain rationale**: Why this order?
5. **Show long-term vision**: Where is Aidos headed?
6. **Identify risks and dependencies**: What could delay progress?

## Non-goals

This RFC does not commit to specific dates (too uncertain for 3+ year plan).

This RFC does not specify exact feature lists (those are RFCs themselves).

This RFC does not address pricing or business model (separate document).

## Design

### Phase 1: Architecture Foundation (0-3 months)

**Vision**: Establish core platform principles and infrastructure.

**Key deliverables:**

```
1. RFC documents (this series)
   - 25+ RFCs covering architecture, design, concepts
   - Published as constitutional documents
   - Community review and feedback

2. Headless runtime (Rust)
   - Event-driven architecture
   - Tool Broker framework
   - Basic session management
   - SQLite storage backend
   - Git integration

3. Security model
   - Capability-based access control
   - Permission system
   - Audit logging
   - Initial threat model addressed

4. Local model support
   - Ollama integration
   - Whisper for transcription
   - Embedding models
   - Offline-first confirmed viable

5. Documentation
   - Architecture diagrams
   - Component guides
   - Philosophy and principles
   - Contribution guidelines
```

**Timeline**: 0-3 months (in progress, Q2-Q3 2025)

**Success criteria:**
- RFC series complete and accepted
- Runtime can run projects locally
- Offline-first proven
- Community responds with early interest

**Dependencies**: Core Rust knowledge, clear architectural vision

### Phase 2: Android MVP (3-6 months)

**Vision**: First Aidos UI demonstrating offline-first and headless runtime.

**Key deliverables:**

```
1. Android app (Jetpack Compose)
   - Project browser
   - Session interface
   - Artifact viewer
   - Intent Graph visualization
   - Git integration (read-only)

2. Local runtime execution
   - Embedded runtime in app
   - Background task scheduler
   - Push notifications
   - Progress tracking

3. Voice input
   - Audio capture
   - Local Whisper transcription
   - Voice-to-command interpretation

4. Offline workflows
   - Create/edit projects offline
   - Run sessions offline
   - Browse artifacts offline
   - Sync when online (if configured)

5. Distribution
   - F-Droid (open-source)
   - Google Play Store (if appropriate)
   - GitHub Releases
   - Web-based installer

6. User documentation
   - Getting started guide
   - Workflow examples
   - FAQ
   - Troubleshooting
```

**Timeline**: 3-6 months (Q3-Q4 2025)

**Success criteria:**
- App in F-Droid
- 1000+ downloads
- User feedback positive on offline capability
- Performance acceptable on mid-range devices

**Dependencies:**
- Phase 1 complete
- Rust FFI/mobile bindings stable
- Jetpack Compose patterns established

### Phase 3: Desktop & Extensions (6-12 months)

**Vision**: Power-user tools, developer workflows, extensibility.

**Key deliverables:**

```
1. Desktop app (Compose Multiplatform)
   - Multiple view modes (IDE, Obsidian, Chat, Timeline, Graph)
   - Keyboard-driven interface
   - Git deep integration
   - Code editing (if needed)
   - Remote runtime support

2. Plugin SDK
   - Tool plugins
   - Knowledge plugins
   - Model plugins
   - Instruction plugins
   - Importer/exporter plugins

3. Plugin examples
   - Slack integration
   - GitHub integration
   - SQL/database knowledge source
   - Custom model wrapper
   - Markdown importer

4. Storage enhancements
   - Encryption at-rest (SQLite)
   - Incremental backup
   - Schema migration tools
   - Performance optimization

5. Cloud integration (optional)
   - Project sync (git push/pull model)
   - Backup integration (S3, GCS, Azure)
   - Cloud runtime option (managed Aidos)

6. Developer relations
   - Plugin development tutorial
   - API documentation
   - Example plugins
   - Developer community forum
```

**Timeline**: 6-12 months (Q4 2025 - Q2 2026)

**Success criteria:**
- Desktop app stable on macOS, Windows, Linux
- 5+ first-party plugins shipped
- 10+ community plugins created
- Plugin API stable (won't break in next year)

**Dependencies:**
- Phase 2 Android stable
- Remote runtime operational
- Storage backend performant

### Phase 4: Platform Expansion (12+ months)

**Vision**: Aidos as distributed, voice-centric, life-management AI OS.

**Key deliverables:**

```
1. Distributed execution
   - Worker nodes (agents) on various devices
   - Task distribution across workers
   - Consensus/coordination model
   - Fault tolerance
   - Multi-user collaboration

2. Advanced voice interface
   - Continuous listening (wake word + voice)
   - Multi-turn conversation
   - Voice-based Intent Graph editing
   - Voice note creation and indexing
   - Dialect and accent support

3. Vision capabilities
   - Camera integration
   - Object recognition
   - Document scanning (OCR)
   - Diagram/chart understanding
   - Code screenshot analysis

4. Life management
   - Calendar integration
   - Task management
   - Note-taking (wiki-like)
   - Habit tracking
   - Goal planning

5. Cross-device synchronization
   - Phone, tablet, laptop, desktop
   - Real-time sync
   - Selective sync (privacy)
   - Conflict resolution
   - Version history

6. Marketplace
   - Curated plugins
   - Paid plugins (revenue share)
   - Project templates
   - Model marketplace
   - Integration registry

7. Collaboration
   - Real-time editing
   - Comments and reviews
   - Permission model for shared projects
   - Audit trail for collaborative work

8. Web interface (if useful)
   - Remote project access
   - Collaboration hub
   - Mobile web (complementary to apps)
```

**Timeline**: 12+ months (2026+)

**Success criteria:**
- 10,000+ active users
- Marketplace with 100+ plugins
- Distributed worker network operational
- Aidos used for real-world projects (not just demos)

**Dependencies:**
- Phase 3 complete and stable
- Community buy-in
- Funding/resources for larger team

### Long-Term Vision (3+ years)

**Beyond platform expansion:**

```
1. Industry adoption
   - Embedded in developer workflows
   - Used by teams of 10-100+ people
   - Replaces specific tools (IDE, knowledge base, etc.)

2. AI advancement
   - Better models from research community
   - Aidos adapts automatically
   - Specialized models for domains
   - Self-improving loops

3. Infrastructure simplification
   - Single system replacing Git + IDE + Slack + Jira + ...
   - Unified project representation
   - Natural interfaces (voice, gesture, visual)

4. Offline-first everywhere
   - Works on phones, watches, VR headsets
   - Seamless sync across all devices
   - Privacy guaranteed

5. AI-assisted everything
   - Code generation, testing, debugging
   - Documentation auto-generation
   - Architecture review and suggestions
   - Dependency management automation

6. Next-generation interaction
   - Neural interfaces (if technology matures)
   - Holographic displays
   - Ambient awareness of projects
   - Predictive suggestions

7. Open ecosystem
   - No vendor lock-in
   - Export to other formats
   - Import from other systems
   - Interoperability with other AI systems

8. Economic model
   - Sustainable open-source (grants, sponsorships)
   - Optional paid cloud (for sync, compute)
   - Plugin marketplace (developer revenue share)
   - Consulting and support services
```

## Milestones and Checkpoints

**Q2-Q3 2025 (Phase 1 - Architecture)**
- [ ] RFC series complete (25+ RFCs)
- [ ] Runtime core operational
- [ ] First user testing
- [ ] Community repository active

**Q3-Q4 2025 (Phase 2 - Android MVP)**
- [ ] Android app in F-Droid
- [ ] 1000+ downloads
- [ ] User feedback positive
- [ ] Offline workflows validated

**Q4 2025 - Q2 2026 (Phase 3 - Desktop & Extensions)**
- [ ] Desktop app stable (macOS, Windows, Linux)
- [ ] Plugin SDK released
- [ ] 5+ first-party plugins
- [ ] 10+ community plugins
- [ ] Developer community active

**Q2 2026+ (Phase 4 - Platform Expansion)**
- [ ] Distributed execution prototype
- [ ] Voice interface improvements
- [ ] Vision capabilities
- [ ] 10,000+ active users
- [ ] Marketplace launched

## Technical Dependencies

**Critical path:**

```
Phase 1 (complete)
  └─> Phase 2 (Android)
       ├─> Mobile experiences
       └─> Phase 3 (Desktop)
           ├─> Power users
           ├─> Plugin ecosystem
           └─> Phase 4 (Platform)
               ├─> Distributed work
               ├─> Advanced AI
               └─> Long-term vision
```

**Parallel workstreams:**

```
1. Core platform (all phases)
   - Runtime improvements
   - Storage optimization
   - Security hardening

2. AI/models (all phases)
   - Model improvements
   - New capabilities (vision, audio)
   - Provider integrations

3. Developer relations (Phase 2+)
   - Documentation
   - Examples
   - Community engagement

4. Product (Phase 2+)
   - User feedback
   - UX/design
   - Performance optimization
```

## Risk Mitigation

**Key risks and mitigation:**

```
Risk: Market doesn't adopt offline-first AI
Mitigation:
  - Start with power users (developers)
  - Show clear privacy/control benefits
  - Demonstrate offline capability early

Risk: AI models not good enough locally
Mitigation:
  - Cloud fallback option
  - Integrate best local models
  - Hybrid pipelines (local + cloud)

Risk: Complexity too high for users
Mitigation:
  - Start simple (MVP)
  - Gradually add features
  - Focus on workflows, not features
  - Strong documentation and tutorials

Risk: Competition from commercial AI IDEs
Mitigation:
  - Open-source (can't be killed)
  - Community-driven (vs. corporate)
  - Modularity (adapt to new capabilities)
  - Privacy focus (compliance)

Risk: Insufficient resources/funding
Mitigation:
  - Open-source model (volunteers)
  - Modular (features can be added gradually)
  - Phase-based (stop at any point)
  - Sustainability focus (not expensive)
```

## Success Metrics

**Overall project health:**

```
Community:
  - Contributors: 100+ by Phase 3
  - GitHub stars: 10,000+ by Phase 4
  - Community plugins: 50+ by Phase 3, 300+ by Phase 4
  - Active forum users: 1000+ by Phase 4

Adoption:
  - Downloads: 10,000+ by Phase 2, 100,000+ by Phase 3
  - Active users: 1000+ by Phase 3, 10,000+ by Phase 4
  - Recurring users: 80%+ retention by Phase 3

Technical:
  - Runtime uptime: 99.9%
  - Offline reliability: 99%
  - Performance: < 200ms UI response time
  - Storage: < 1GB per active project

Ecosystem:
  - Plugin diversity: 10+ categories by Phase 3
  - Integration completeness: Core tools (Git, FS, Shell) stable
  - API stability: Zero breaking changes in Phase 3+
```

## Open Questions

- Should Aidos target specific domains first (e.g., Python developers)?
- How aggressive should the roadmap be (1 year vs. 3 year for full vision)?
- Should paid/commercial features be considered in Phase 3 or later?
- How should community feedback influence the roadmap?
- Should we fork/differentiate for enterprise vs. community versions?
- What's the maximum market Aidos can address (10M developers? 100M?)?
- Should Aidos eventually be ported to every platform (iOS, wearables, VR)?
- What's the success condition for declaring Aidos "complete"?

## Appendix: Phase 1 Architecture RFC Index

This roadmap references the Phase 1 architecture RFCs:

**Core**
- RFC-0000: Vision
- RFC-0001: Principles
- RFC-0002: Runtime Architecture
- RFC-0003: Security (Capability-Based Access Control)
- RFC-0004: Event Bus
- RFC-0005: Scheduler

**Concepts**
- RFC-0010: Projects
- RFC-0011: Sessions
- RFC-0012: Intent Graph
- RFC-0013: Resources
- RFC-0014: Artifacts
- RFC-0015: Knowledge Engine
- RFC-0016: Instruction Engine

**AI/Models**
- RFC-0020: AI Engine
- RFC-0021: Model Providers
- RFC-0022: Local Models
- RFC-0023: Remote Models

**Tools & Integration**
- RFC-0030: Tool Broker
- RFC-0031: MCP (Model Context Protocol)
- RFC-0032: Git
- RFC-0033: Shell
- RFC-0034: Filesystem

**Infrastructure**
- RFC-0040: Storage
- RFC-0041: Export/Import

**UIs & Extensions**
- RFC-0050: Android
- RFC-0051: Desktop
- RFC-0060: Plugin SDK

**Roadmap and Reviews**
- RFC-0099: Roadmap (this document)
- RFC-0100: Comprehensive Architecture Review
