# RFC-0104: Aidos Desktop — Spatial Canvas & Generative UI Architecture

Status: Draft  
Date: 2026-10-22  
Supersedes: RFC-0051 Core View Modes (Amended)  
Related RFCs: [RFC-0012 Intent Graph](0012-intent-graph.md), [RFC-0051 Desktop](0051-desktop.md), [RFC-0103 Aidos Engine](0103-aidos-engine.md)  
Related Documents: [LAYA: Typed Decisions](../../LAYA.md), [Dictator SDK Integration Plan](../dictator-sdk-integration-plan.md)

---

## Abstract

This RFC defines the user interface and interaction architecture for **Aidos Desktop**. 

Departing from the rigid, multi-pane tiling layouts common to traditional IDEs (and originally proposed in RFC-0051's six static view modes), Aidos Desktop establishes an **agent-composed, generative user interface**. The architecture is structured around two unified spatial layers:
1. **The Macro Surface (The Infinite Intent Canvas):** A 2D zoomable spatial graph where project goals, architectural constraints, milestones, and research nodes live as a live Directed Acyclic Graph (RFC-0012).
2. **The Micro Surface (The Unified Living Document):** Zooming into any canvas node reveals a continuous, voice-docked document powered by Dictator's rich text and code editing engine, entirely free of window splits.

The interface is driven by a **Multi-Agent Presence System**: a **Traveling STT Caret Pill (Red Pill)** that glides with the human cursor during voice dictation and routes commands, and **Autonomous Worker Pills (Blue/Green Pills)** that project active work on the canvas via directional wave signaling and reserve code/prose ranges in documents via animated line locks. 

To eliminate prompt context bloat and prevent cross-domain data leakage, the interface is powered by a **Domain-Scoped Trusted Component Registry (TCR)** and a **Multi-Orchestrator Compartmentalization Model** enforcing mathematically rigorous Information Flow Control (IFC).

---

## Motivation

### 1. The Failure of Legacy Panes ("Panel-Hell")
Traditional developer environments (VS Code, JetBrains, Blender) divide display real estate into arbitrary, rigid rectangles: file trees, editors, terminals, debuggers, and AI chat sidebars. When modern AI tools attempt "Generative UI," they frequently fall into automated window management—generating yet another split pane. This leads to cramped views, high cognitive fatigue, and lost context.

### 2. The Limits of Rolling Chat History
Conventional AI assistants store memory as a rolling, chronological conversation transcript. Over long work sessions, this causes severe context window bloat (15,000+ tokens per turn), slow local inference, attention dilution, and hallucinated calculations. In Aidos, the **Intent Graph (RFC-0012) is the canonical memory**. Decisions and state live in persistent graph nodes, not buried in ephemeral chat logs.

### 3. The Need for Seamless Multimodal Fluidity
Human thought fluidly traverses prose, code, systems architecture, physical environment controls, and personal life. A user drafting an architectural specification needs voice dictation (Dictator), live code editing, interactive diffs, and dependency tracking in a single, distraction-free environment.

### 4. Decoupling from the Engine Daemon
RFC-0103 established Aidos Engine as an independent, local inference daemon (`127.0.0.1:18080` discovered via `~/.aidos/engine.json`). Aidos Desktop must be a lightweight client that connects over loopback HTTP/WebSocket, allowing the UI to restart, update, or re-render without interrupting running inference processes.

---

## Goals

1. **Replace Static View Modes with a Generative Canvas-Document Hierarchy:** Formally amend RFC-0051 to replace its six static modes (IDE, Obsidian, Chat, Timeline, Intent Graph, Project Explorer) with a unified **2D Intent Canvas (Macro)** and **Living Document (Micro)**.
2. **Establish the Multi-Agent Presence System:** Define visual and spatial protocols for the Traveling STT Caret Pill and Autonomous Worker Pills (wave signaling, line-range reservations, and soft concurrency locks).
3. **Specify the Domain-Scoped Trusted Component Registry (TCR):** Define the atomic periodic table (Atoms, Molecules, Combinators) and domain partitioning (`@tcr/core`, `@tcr/code`, `@tcr/finance`, `@tcr/home`, etc.) to prevent prompt bloat.
4. **Implement Multi-Orchestrator Compartmentalization:** Enforce Information Flow Control (IFC) across isolated workspace compartments (`Family`, `Job`, `Finance`) with typed, blinded inter-compartment querying.
5. **Guarantee Local-First GDPR Data Minimization:** Establish the in-flight **Privacy Vault** for automated pseudonymization and deterministic Article 17 cascading erasure across the Intent Graph.

---

## Non-goals

* This RFC does not specify low-level GPU rendering shaders for the canvas (those belong to the client implementation engine, e.g., Skia / Pixi / WebGL).
* This RFC does not mandate a specific cloud sync protocol; all contracts are local-first.
* This RFC does not redesign Aidos Engine's inference backends (governed by RFC-0022 and RFC-0103).

---

## Design & Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                      AIDOS DESKTOP ARCHITECTURE                        │
├────────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  [MACRO STARTING POINT]  ──►  THE INFINITE INTENT CANVAS               │
│                               • 2D Spatial surface (RFC-0012 DAG)      │
│                               • Goals, constraints, architecture nodes │
│                               • Semantic zoom navigation               │
│                                                                        │
│  [MICRO SURFACE]         ──►  UNIFIED LIVING DOCUMENT                  │
│                               • Dictator voice-docked prose & code     │
│                               • Continuous, distraction-free flow      │
│                               • Zero panel splits                      │
│                                                                        │
│  [AGENT PRESENCE]        ──►  MULTI-AGENT PILLS                        │
│                               • 🔴 Red Pill: Speech & Orchestrator     │
│                               • 🔵 Blue Pill: Code & Refactor Worker   │
│                               • 🟢 Green Pill: Verifier & Test Worker  │
│                               • Canvas wave signaling ("Working here") │
│                               • Document line reservations & locks     │
│                                                                        │
│  [GENERATIVE EXECUTION]  ──►  ATOMIC INLINE TOOLS                      │
│                               • Synthesized at the point of thought    │
│                               • Assembled from TCR atomic primitives   │
│                               • Infinite parametric variations         │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

---

### 1. Spatial Hierarchy: Macro Canvas to Micro Document

#### 1.1 The Infinite Intent Canvas (Macro)
The user begins on an infinite 2D canvas representing the project's **RFC-0012 Intent Graph**:
* **Nodes as Interactive Surfaces:** Nodes represent Goals, Constraints, Tasks, Financial Models, or Research Topics.
* **Edges as Causal Dependencies:** Directed edges indicate prerequisites, data flow, or constraints.
* **Semantic Zooming (ZUI):**
  * *Scale 0.1 – 0.3 (Galaxy View):* Macro architecture, milestone health, high-level dependencies.
  * *Scale 0.4 – 0.7 (Cluster View):* Active sub-goals, assigned agent pills, and progress gauges.
  * *Scale 0.8 – 1.0 (Focus View):* Smoothly expands the target node into the **Unified Living Document**.

#### 1.2 The Unified Living Document (Micro)
Zooming into a node enters the Living Document:
* Powered by Dictator's TipTap/ProseMirror or Compose editing engine.
* Technical prose, architectural rationale, and runnable code blocks live in a single vertical stream.
* Tools (diffs, test runners, charts, web previews) are injected **inline as bespoke blocks** directly at the point of thought—never in separate side panels.

---

### 2. Multi-Agent Presence & Spatial Interaction

Agents are represented by lightweight, floating **Pill Avatars**:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        MULTI-AGENT PILL ROLES                          │
├───────────────────────────────┬────────────────────────────────────────┤
│ 🔴 Red Pill (Orchestrator)    │ Caret companion; speech & STT listener │
│ 🔵 Blue Pill (Worker: Code)   │ Code restructuring, refactoring, patch │
│ 🟢 Green Pill (Worker: Test)  │ Verifier, test runner, constraints     │
│ 🟣 Violet Pill (Worker: R&D)  │ Web research, documentation, media     │
└────────────────────────────────────────────────────────────────────────┘
```

#### 2.1 The Traveling STT Caret Pill (Red Pill)
* **Caret Tracking:** Follows the cursor’s dynamic screen coordinates `(x, y)` in real time.
* **Audio Waveform & Ghost Text:** Displays real-time audio visualization (`▅▆▇▃`) and streams interim Whisper tokens before committing text.
* **Intent Trigger Morphing:** Spoken commands (e.g. *"Hey Aidos, optimize this function"*) are evaluated by **Laya**, morphing the pill into an action trigger and delegating work to worker pills.
* **Timeline HUD:** Accompanied by a slim, collapsible timeline scroller offering two reading modes:
  1. *Full Transcript Mode:* Timestamped turns, audio replays, and agent decisions.
  2. *Cue-Words Ticker:* Compact horizontal stream of semantic chips that act as spatial camera bookmarks on the canvas.

#### 2.2 Directional Wave Signaling on the Canvas
When an agent is assigned a canvas node:
* The worker pill glides across the canvas and anchors near the node border.
* It emits radiating, animated concentric ripples toward the node.
* The node pulses with the agent's color theme, providing instant visual feedback: *"Agent is working here."*

#### 2.3 Document Line-Range Reservations & Live Locks
When modifying code or text in a document:
* The worker pill navigates to the target line range.
* The lines receive an advisory translucent background wash (e.g., `rgba(59, 130, 246, 0.12)`) and an animated gutter shimmer.
* This establishes a soft concurrency lock, preventing human-AI edit collisions.
* Upon completion, the reservation smoothly unfurls into an **Atomic Inline Diff Block** with one-click accept/reject controls.

---

### 3. The Trusted Component Registry (TCR)

To prevent the agent from hallucinating fragile HTML/CSS while enabling infinite UI flexibility, the TCR uses a **Tri-Level Atomic Architecture**:

```
Level 0: Design Tokens (Colors, Densities, Glass, Radii)
   │
   ▼
Level 1: Atomic Primitives (TextSpan, Button, Waveform, DiffChunk, Slider, Badge)
   │
   ▼
Level 2: Molecular Combinators (InlineCard, SplitComparison, MetricCluster, ActionBar)
   │
   ▼
Runtime Output: Declarative A2UI JSON Manifest
```

#### 3.1 Domain-Scoped Packaging (Zero Context Bloat)
The TCR is partitioned into modular packages to ensure lean prompt contexts:
* `@tcr/core`: Universal atoms (always loaded; ~300 tokens).
* `@tcr/code-engineering`: Monaco, Diff, AST, Terminal, TestRunner, OpenAPI.
* `@tcr/finance-modeling`: CausalDriver, Amortization, Currency, Sankey, TaxOptimizer.
* `@tcr/home-iot`: LightToggle, Thermostat, ColorHue, ScheduleTimer, SecurityCamera.
* `@tcr/family-household`: ScheduleRibbon, RideLogistics, SmartGrocery, ChoreLeaderboard, MedLog.
* `@tcr/research-geo`: MapCanvas, RoutePolyline, PriceMatrix, RecipeCard.
* `@tcr/analytics-olap`: InterpretedChart, DuckDBSandbox, DataProfile.
* `@tcr/messaging-agentic`: ConversationThread, MessageEnvelope, ApprovalModal.

**Dynamic In-Prompt Hydration:** Laya classifies the active intent domain in $< 5\text{ms}$ and injects *only* the required domain schema into the agent's tool context, reducing prompt overhead from $12,000+$ tokens to under $800$ tokens.

---

### 4. Stateless Invocations & Multi-Orchestrator Compartmentalization

#### 4.1 Stateless Canvas Prompts
Rolling linear conversation history is dropped. The agent's prompt context is strictly bounded to:
1. `Active Node State` (current code, prose, or attributes).
2. `Neighborhood Context` (immediate parent/child nodes in the Intent Graph).
3. `Scoped Domain TCR Schema`.
4. `Immediate User Instruction`.

#### 4.2 Multi-Orchestrator Compartments & Information Flow Control (IFC)
Workspaces are partitioned into isolated cryptographic and memory compartments:
* **`Family Life` (`outflow: DENY_ALL`):** Air-gapped. Memories and data cannot leave the local SQLite database.
* **`Enterprise Job` (`outflow: ALLOW_WORK_EGRESS`):** Permitted to communicate with company git remotes and APIs, but possesses **zero read access** to Family, Health, or Personal Finance compartments.

#### 4.3 The Inter-Compartment Bridge
Orchestrators communicate strictly via typed, blinded queries:
* When `Orchestrator_Job` queries scheduling availability, `Orchestrator_Family` returns only `{ available: false, busy: true }`. Zero personal details or family names are disclosed.
* Escalated queries trigger an explicit on-screen **Human Consent Gate Modal**.

---

### 5. Local-First GDPR Data Minimization

1. **The In-Flight Privacy Vault:**
   * Raw user inputs are intercepted locally before dispatch.
   * Direct PII (names, emails, phones, IBANs, national identity numbers) is replaced with semantic placeholder tokens (`[PERSON_1]`, `[IBAN_1]`).
   * The model reasons over placeholders; the Vault re-hydrates tokens locally on screen. External or cloud models never see or retain personal data.
2. **Article 17 "Right to Erasure" Cascades:**
   * Inference weights (`llama.cpp`) are frozen and memory-free.
   * When an erasure request is executed, Aidos traverses the Intent Graph DAG, scrubbing personal identifiers across nodes, transcripts, and vector embeddings while retaining required aggregate accounting totals.
   * Generates a verifiable, cryptographically signed Article 17 Deletion Certificate.

---

### 6. Big Data Mining & On-The-Fly API Synthesis

1. **In-Process DuckDB OLAP Engine:**
   * Big data (gigabyte-scale CSV, Parquet, JSON logs) is never shoved into prompt context windows.
   * The agent generates vectorized SQL executed locally by embedded DuckDB in milliseconds.
   * Results stream into **Interpreted Chart Molecules** featuring executive takeaways, coordinate pins, and voice narration ("No Naked Charts").
2. **On-The-Fly OpenAPI-to-MCP Transpilation:**
   * Dragging an `openapi.yaml`, Swagger JSON, or cURL endpoint onto the canvas instantly synthesizes an in-memory MCP server.
   * Tools are dynamically tagged and pruned via Laya to eliminate tool context bloat.

---

## Wire Protocols & Schemas

### 1. Declarative A2UI Manifest Schema
```json
{
  "$schema": "https://aidos.dev/schemas/a2ui-v1.json",
  "manifestId": "ui_manifest_42",
  "targetNodeId": "goal_node_audio_buffer",
  "root": {
    "type": "InlineCard",
    "props": { "intent": "accent", "density": "compact", "elevation": "glass" },
    "slots": {
      "header": {
        "type": "Row",
        "children": [
          { "type": "Icon", "props": { "name": "cpu", "intent": "accent" } },
          { "type": "TextSpan", "props": { "text": "Buffer Optimization Result", "weight": "bold" } }
        ]
      },
      "body": {
        "type": "MetricCluster",
        "children": [
          { "type": "MetricValue", "props": { "label": "Latency", "value": "18ms", "delta": "-14ms" } }
        ]
      },
      "footer": {
        "type": "ActionBar",
        "children": [
          { "type": "ActionButton", "props": { "label": "Apply Patch", "actionId": "apply_patch", "intent": "primary" } }
        ]
      }
    }
  }
}
```

### 2. Inter-Compartment Bridge Query Schema
```json
{
  "queryId": "bridge_qry_8819",
  "sourceCompartment": "JOB",
  "targetCompartment": "FAMILY",
  "queryType": "TIME_AVAILABILITY_QUERY",
  "payload": {
    "start": "2026-10-24T14:00:00Z",
    "end": "2026-10-24T17:00:00Z"
  }
}
```

---

## Amendments to Existing RFCs

### RFC-0051 (Desktop)
* **Section 5.3 (Core View Modes):** Deprecated in favor of RFC-0104's **2D Spatial Intent Canvas (Macro)** and **Unified Living Document (Micro)**.
* **Runtime Hosting:** Replaced with loopback client connection to Aidos Engine (`~/.aidos/engine.json`) per RFC-0103.

---

## Implementation Phases & Roadmap

* **Phase 1: Engine Client & Traveling Caret Companion (Immediate)**
  - Connect Compose Desktop shell to local Aidos Engine (`127.0.0.1:18080`).
  - Implement Dictator caret-coordinate tracking for the floating Red Pill with live Whisper audio streaming.
* **Phase 2: Domain-Scoped TCR & A2UI Renderer**
  - Implement `@tcr/core` atomic primitives and the declarative A2UI manifest parser.
  - Implement dynamic domain hydration via Laya intent detection.
* **Phase 3: Infinite Intent Canvas & Spatial Zooming**
  - Build hardware-accelerated 2D canvas hosting RFC-0012 Intent Graph nodes.
  - Connect semantic zoom transitions between canvas nodes and living documents.
* **Phase 4: Multi-Agent Presence & Information Flow Control**
  - Implement directional canvas waves and document line-range reservation locks for worker pills.
  - Partition workspaces into isolated SQLite compartments with the Inter-Compartment Security Bridge.
* **Phase 5: In-Process Analytics & On-The-Fly APIs**
  - Embed DuckDB for local Parquet/CSV data mining.
  - Implement runtime OpenAPI-to-MCP transpiler.
