# Trusted Component Registry (TCR) & Component Domain Owners

**Status:** Ecosystem Architecture Standard · Settled  
**Authority:** Aidos Architecture Council · Decision D46 & RFC-0051  
**Scope:** `aidos-tcr`, `aidos`, `dictator`, `agentic-messenger` (AMP), and ecosystem consumers  
**Last Updated:** November 2026  

---

## 1. Vision & Purpose: Why We Have the TCR

**TCR stands for Trusted Component Registry.**

The ultimate end goal of the Aidos UI architecture is the **Desktop Generative UI** (specified in [RFC-0051: Desktop](0051-desktop.md) and [RFC-0103: Aidos Engine](0103-aidos-engine.md)). In a generative UI paradigm, autonomous AI sessions, intent graphs, and agent runs generate and orchestrate interactive interfaces on an infinite spatial canvas.

However, **Generative UI must not generate raw, untrusted, or unstandardized UI controls from scratch.** 

Doing so creates visual chaos, inconsistent interaction grammars, hallucinated action loops, and severe accessibility/security regressions. Instead, Generative UI operates by assembling, configuring, and binding **pre-existing, cryptographically safe, and human-tested components** registered in the **Trusted Component Registry (TCR)**.

### Architectural Rules of the TCR:
1. **The TCR Repository is the Sole Owner of Shared UI Components**:
   - The dedicated Multiplatform repository **`aidos-tcr`** (`dev.aidos.tcr:aidos-tcr:0.1.0`) is the **canonical owner, registry, and publisher of all shared UI components across the ecosystem**.
   - No application repository (`dictator`, `agentic-messenger`, `aidos`) maintains parallel, duplicate, or fork implementations of shared UI components. All shared Compose Multiplatform UI elements reside directly in `aidos-tcr`.
2. **Domain Specification by Originating Apps**:
   - Applications act as functional domain specification authorities (e.g. Dictator defines the behavioral specs for text editing and caret pills; AMP defines the behavioral specs for 4-party messaging and UAE disclosure).
   - However, once specified, **components are implemented, tested, and maintained authoritatively in `aidos-tcr`**.
3. **Strict Downstream Consumption**:
   - All client applications (`dictator-desktop`, `dictator-android`, `amp-ui`, `aidos-desktop`) are **pure consumers** of `dev.aidos.tcr.*`.
   - Any enhancements, bug fixes, animation physics (e.g., hover cones, autonomous snapping), or telemetry hooks must be made directly within `aidos-tcr` so every consumer benefits immediately without code divergence.

---

## 2. Component Domain Ownership Matrix

The Trusted Component Registry maps each functional domain to its authoritative UI owner (`aidos-tcr`), package namespace (`dev.aidos.tcr.*`), and originating domain specification:

| Domain | Authoritative Component Owner | Canonical TCR Package | Originating Domain Spec | Canonical TCR Components & Primitives |
|---|---|---|---|---|
| **Text Editing & Document Composition** | **aidos-tcr** | `dev.aidos.tcr.document` | Dictator (D46) | • `DocumentInputSection`<br>• `DocumentChatFlow`<br>• Context Target Indicators<br>• Active Document Tail & Demarcation Line<br>• Ember Cursor Brushes |
| **Actor Pills & Caret Staging (The Red Pill)** | **aidos-tcr** | `dev.aidos.tcr.pill`<br>`dev.aidos.tcr.actor` | Dictator (D46) / AMP | • `PersistentRedPill` (Ember Caret Actor `0xFFEF4444`)<br>• `FloatingActorPill`<br>• `PillConstellationOverlay`<br>• `ActorPillBadge`<br>• `RadialCountdownRing` (with radar sweep)<br>• `ActionGlyph`<br>• `HappeningsHUD` (1-click telemetry HUD)<br>• `TranscriptSplitDrawer` (2-click split drawer)<br>• `CadenceEngine` (calibrated fuse timers)<br>• `ActorSelectionHolder` ("holding shelf")<br>• Fast-Go / Fast-Stop relocation & Volumetric Projection Cones |
| **Conversation Mesh & Timelines** | **aidos-tcr** | `dev.aidos.tcr.mesh` | Agentic Messenger (AMP) | • `MessageBubble`<br>• `ContactRow`<br>• `DualViewToggle` (Natural vs Cue ticker)<br>• `CueTickerRow`<br>• `ConversationInputBar` |
| **Universal Envelopes & Cryptographic Badges** | **aidos-tcr** | `dev.aidos.tcr.mesh`<br>`dev.aidos.tcr.model` | Agentic Messenger (AMP) | • `ProvenanceBadge` (Human Direct vs Delegated Runner)<br>• `PrivacySealBadge` (E2EE vs Local Inference)<br>• `IdentityCard`<br>• `PresenceIndicator`<br>• `UniversalEnvelope` & `UiModels` |
| **Progressive Disclosure & Co-Pilots** | **aidos-tcr** | `dev.aidos.tcr.mesh` | Agentic Messenger (AMP) | • `ApprovalCard` (Escrowed Topic Requests)<br>• `CalendarCopilotCard`<br>• `WaveformVisualizer` (Voice Playback) |
| **Generative UI Registry & Timeline Dispatcher** | **aidos-tcr** | `dev.aidos.tcr.generative` | Agentic Messenger / Aidos | • `GenerativeUiRegistry`<br>• Timeline Model mappers (`UniversalEnvelope` → Compose) |
| **Generative UI Host & Desktop Spatial Canvas** | **Aidos Desktop** | `dev.aidos.desktop` | Aidos (RFC-0051) | • Infinite Canvas Viewport & Node Coordinator<br>• Execution Graph Visualizer & Inspection Trees<br>• Spatial Node Frames & Presence Waves |
| **Core Agent Loop & Runtime Execution** | **Aidos Engine** | `dev.aidos.kernel`<br>`dev.aidos.engine` | Aidos (RFC-0103) | • `AgentRuntime`, `ToolBroker`<br>• Model Adapters (`llama.cpp`, `ONNX Runtime`)<br>• `SystemOne` Decision Routing<br>• STT Transcribe Endpoint |

---

## 3. Deep-Dive: Domain Specifications & TCR Components

### 3.1. Text Editing, Document Flow & The Red Pill (Spec: Dictator)

Dictator (`jsilvanus/dictator`) specifies the requirements for voice-first, cursor-aware document editing. Its components have been consolidated into `aidos-tcr`:

#### 1. The Red Pill (`PersistentRedPill` / `FloatingActorPill` / Ember Caret Actor)
- **Role:** Voice dictation orchestrator and primary STT cursor anchor.
- **Palette:** Ember Red (`0xFFEF4444`).
- **Behaviors:**
  - **Single Click / Tap:** Starts/stops voice dictation or triggers the user-level AI orchestrator. Toggles the **Happenings HUD** to inspect live actions.
  - **Double Click / Long Press:** Opens the **50% Split Transcript Drawer**, displaying real-time transcribed utterances, confidence scores, and multi-speaker breakdowns.
  - **Drag-to-Move:** Moves the caret smoothly across lines, or drags context onto a specific prior document block (`attachedTargetLabel`).
  - **Autonomous Relocation & Projections:** Projects volumetric translucent cones (`TargetConeHighlight`) to active edit targets, supports Fast-Go / Fast-Stop physics, and snaps autonomously into place.

#### 2. Worker Pill Constellation (`PillConstellationOverlay`)
- **Blue Pill (`DETERMINISTIC_EDITOR` - `0xFF3B82F6`):** Executes deterministic text edits, paragraph restructuring, and holds active text ranges in the `ActorSelectionHolder` ("holding shelf").
- **Green Pill (`GENERATIVE_AI` - `0xFF10B981`):** Anchors directly to target text sections and streams generative tokens emerging from the pill tip.

#### 3. Cadence Engine (`CadenceEngine`) & Radial Countdown
- Implements human-friendly verification: *"Work, as a concept, requires time."*
- Calibrated radial timers before mutation commit:
  - **Minor Edit:** 250ms
  - **Structural Block Edit:** 500ms
  - **Destructive Deletion:** 900ms – 1200ms
- **Spring & Freeze Gesture:** Dragging an active worker pill 20dp pauses the countdown ring; dragging 80dp tears and aborts the action.

#### 4. Document Components (`DocumentInputSection` & `DocumentChatFlow`)
- Molecular Compose components featuring a soft-elevation active document tail, horizontal demarcation line, inline Ember cursor brush (`SolidColor(0xFFEF4444)`), and context target indicator.

---

### 3.2. 4-Party Conversation Mesh & UAE (Spec: Agentic Messenger)

Agentic Messenger (`jsilvanus/agentic-messenger`) specifies collaborative messaging protocols over Universal Agentic Envelopes (UAE). Its components have been consolidated into `aidos-tcr`:

#### 1. The 4-Party Mesh & Cryptographic Provenance
- Displays multi-party interaction:
  - `HUMAN_DIRECT`: Direct human utterance.
  - `AGENT_RUNNER`: Delegated autonomous Aidos agent.
  - `HUMAN_ONLY_E2EE`: Zero-knowledge, agent-blinded end-to-end encryption.
- Rendered via atomic components: `ProvenanceBadge`, `PrivacySealBadge`, and `MessageBubble`.

#### 2. Progressive Disclosure & Escrow Cards
- When an agent requests private or escrowed data (e.g., ETA, live GPS, home passcode):
  - Renders `ApprovalCard` with one-tap Approve / Decline workflows.
  - Inline co-pilots render `CalendarCopilotCard` with conflict checks and instant meeting confirmations.

#### 3. Generative UI Registry (`GenerativeUiRegistry`)
- Dispatches and renders incoming `UniversalEnvelope` payloads directly into atomic and molecular Compose items without manual view wiring.

---

### 3.3. Generative UI Host, Engine & Canvas (Aidos)

Aidos (`jsilvanus/aidos`) provides the foundational runtime, local inference models, and the desktop spatial canvas:

- **Host Canvas:** Arranges chat nodes, document nodes, and terminal sessions as spatial cards on an infinite zoomable surface.
- **Engine Connectors:** Provides local inference over `llama.cpp` (GGUF LLMs), `ONNX Runtime` (embeddings, audio, System One typed decisions), and local STT audio pipelines.
- **TCR Consumer:** In Aidos Desktop, the Generative UI engine dynamically instantiates TCR's `DocumentInputSection` and `PillConstellationOverlay` for document/code tasks, and TCR's `MessageBubble` and `ApprovalCard` for communication tasks.

---

## 4. Cross-Repository Consumption Model

### Architecture: `aidos-tcr` as Authoritative UI Foundation

All client applications in the ecosystem fetch their UI components directly from `aidos-tcr`:

```
                      ┌────────────────────────────────────────────────────────┐
                      │                 aidos-tcr (TCR Repo)                   │
                      │         Authoritative Owner of All UI Components       │
                      │                                                        │
                      │  • dev.aidos.tcr.pill (PersistentRedPill, HUD, etc.)   │
                      │  • dev.aidos.tcr.document (DocumentInputSection, Flow) │
                      │  • dev.aidos.tcr.actor (CadenceEngine, Constellation)  │
                      │  • dev.aidos.tcr.mesh (MessageBubble, Cards, Badges)   │
                      │  • dev.aidos.tcr.generative (GenerativeUiRegistry)     │
                      │  • dev.aidos.tcr.theme (TcrTheme tokens & colors)      │
                      └───────────────────────────┬────────────────────────────┘
                                                  │
                 ┌────────────────────────────────┼────────────────────────────────┐
                 ▼                                ▼                                ▼
   ┌───────────────────────────┐    ┌───────────────────────────┐    ┌───────────────────────────┐
   │         Dictator          │    │  Agentic Messenger (AMP)  │    │       Aidos Desktop       │
   │  Consumes dev.aidos.tcr   │    │  Consumes dev.aidos.tcr   │    │  Consumes dev.aidos.tcr   │
   │  (Pill, Document, Actor)  │    │  (Mesh, Document, Pill)   │    │  (Spatial Canvas, UAE)    │
   └───────────────────────────┘    └───────────────────────────┘    └───────────────────────────┘
```

---

## 5. Design System Tokens & Vocabulary

All repositories adhere to shared TCR Design Tokens defined in `dev.aidos.tcr.theme.TcrTheme`:

### 5.1. Color Roles
- **Ember Red Orchestrator:** `0xFFEF4444` — Voice dictation cursor, Red Pill, immediate cancel actions.
- **Electric Blue Editor:** `0xFF3B82F6` — Deterministic text editing, selection shelf, paragraph manipulation.
- **Emerald Green Generative AI:** `0xFF10B981` — Inline streaming token emergence, agent autonomous completions.
- **Amber Warning / Escrow:** `0xFFF59E0B` — Progressive disclosure requests, pending human-in-the-loop approvals.
- **Violet System / Protocol:** `0xFF8B5CF6` — Cryptographic assertions, blinded envelope stamps.

### 5.2. Cadence & Animation Specifications
- **Pill Sinusoidal Pulse:** Ambient idle pulse with a 900ms ease-in-out period (`FastOutSlowInEasing`, alpha `0.4f` to `1.0f`).
- **Commit Snap Flash:** 100ms crisp execution snap upon timer completion.
- **Spring & Freeze Thresholds:** 20dp drag displacement enters paused state; 80dp drag displacement tears and cancels the operation.

---

## 6. Repository References & Governance

Every repository in the ecosystem references this canonical document in its top-level documentation:

- **TCR (`aidos-tcr`)**: [aidos-tcr](file:///c:/Users/jsilv/Code/aidos-tcr) · Authoritative repository for all shared Compose Multiplatform UI components.
- **Aidos (`aidos`)**: [docs/TCR_COMPONENT_DOMAIN_OWNERS.md](file:///c:/Users/jsilv/Code/aidos/docs/TCR_COMPONENT_DOMAIN_OWNERS.md) · [docs/decisions.md#d46](file:///c:/Users/jsilv/Code/aidos/docs/decisions.md) · [docs/rfcs/0051-desktop.md](file:///c:/Users/jsilv/Code/aidos/docs/rfcs/0051-desktop.md)
- **Dictator (`dictator`)**: [README.md - UI Component Ownership & Design Vocabulary (D46)](file:///c:/Users/jsilv/Code/dictator/README.md)
- **Agentic Messenger (`agentic-messenger`)**: [README.md - UI Component Domain Ownership](file:///c:/Users/jsilv/Code/agentic-messenger/README.md) · [docs/aidos-joint-app-ux.md](file:///c:/Users/jsilv/Code/agentic-messenger/docs/aidos-joint-app-ux.md)
