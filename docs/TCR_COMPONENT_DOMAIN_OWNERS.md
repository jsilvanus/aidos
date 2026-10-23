# Trusted Component Registry (TCR) & Component Domain Owners

**Status:** Ecosystem Architecture Standard · Settled  
**Authority:** Aidos Architecture Council · Decision D46 & RFC-0051  
**Scope:** `aidos`, `dictator`, `agentic-messenger` (AMP), and ecosystem consumers  
**Last Updated:** October 2026  

---

## 1. Vision & Purpose: Why We Have the TCR

**TCR stands for Trusted Component Registry.**

The ultimate end goal of the Aidos UI architecture is the **Desktop Generative UI** (specified in [RFC-0051: Desktop](0051-desktop.md) and [RFC-0103: Aidos Engine](0103-aidos-engine.md)). In a generative UI paradigm, autonomous AI sessions, intent graphs, and agent runs generate and orchestrate interactive interfaces on an infinite spatial canvas.

However, **Generative UI must not generate raw, untrusted, or unstandardized UI controls from scratch.** 

Doing so creates visual chaos, inconsistent interaction grammars, hallucinated action loops, and severe accessibility/security regressions. Instead, Generative UI operates by assembling, configuring, and binding **pre-existing, cryptographically safe, and human-tested components** registered in the **Trusted Component Registry (TCR)**.

### Architectural Rules of the TCR:
1. **The Generative UI Consumer Contract**:
   - The Desktop Generative UI uses components from the TCR to materialize solutions, dashboards, workflows, conversation threads, and editor sessions.
2. **Domain Ownership by Originating Apps**:
   - Other projects in the ecosystem are not Generative UI hosts themselves. However, **they create, author, and authoritatively own their domain-specific UI components**.
   - UI components remain owned and maintained by the applications that first came up with them.
3. **Future TCR Evolution**:
   - It is likely that the TCR will at some point move into a dedicated, standalone repository (e.g., `aidos-tcr` / `trusted-component-registry`).
   - For now, the TCR operates as a unified federated registry where each domain owner maintains and exposes its canonical components for ecosystem-wide consumption.
4. **No Component Re-invention**:
   - If an application needs a capability outside its domain (e.g., if **Aidos Messenger** or **AMP** needs document editing or Red Pill voice dictation), it **must come and fetch those components from the domain owner (Dictator)** rather than re-implementing them.

---

## 2. Component Domain Ownership Matrix

The federated registry maps each functional domain to its authoritative repository, exposed modules, and canonical TCR components:

| Domain | Authoritative Owner | Source Repository | Primary Module(s) | Canonical TCR Components & Primitives |
|---|---|---|---|---|
| **Text Editing & Document Composition** | **Dictator** | `jsilvanus/dictator` | `dictator-core`<br>`dictator-android`<br>`(Compose / KMP)` | • `DocumentInputSection`<br>• `VoiceEditor`<br>• `AiHighlight` (provenance)<br>• Active Document Tail & Demarcation Line |
| **Actor Pills & Caret Staging (The Red Pill)** | **Dictator** | `jsilvanus/dictator` | `dictator-core`<br>`dictator-android` | • `PersistentRedPill` (Ember Caret Actor `0xFFEF4444`)<br>• `FloatingActorPill`<br>• `PillConstellationOverlay`<br>• `ActorPillBadge`<br>• `RadialCountdownRing` (with radar sweep)<br>• `ActionGlyph`<br>• `HappeningsHUD` (1-click telemetry HUD)<br>• `TranscriptSplitDrawer` (2-click split drawer)<br>• `CadenceEngine` (calibrated fuse timers)<br>• `ActorSelectionHolder` ("holding shelf") |
| **Conversation Mesh & Timelines** | **Agentic Messenger (AMP)** | `jsilvanus/agentic-messenger` | `amp-ui`<br>`amp-core` | • `MessageBubble`<br>• `ContactRow`<br>• `DualViewToggle` (Natural vs Cue ticker)<br>• `CueTickerRow`<br>• `ConversationInputBar` |
| **Universal Envelopes & Cryptographic Badges** | **Agentic Messenger (AMP)** | `jsilvanus/agentic-messenger` | `amp-ui`<br>`amp-crypto` | • `ProvenanceBadge` (Human Direct vs Delegated Runner)<br>• `PrivacySealBadge` (E2EE vs Local Inference)<br>• `IdentityCard`<br>• `PresenceIndicator` |
| **Progressive Disclosure & Co-Pilots** | **Agentic Messenger (AMP)** | `jsilvanus/agentic-messenger` | `amp-ui` | • `ApprovalCard` (Escrowed Topic Requests)<br>• `CalendarCopilotCard`<br>• `WaveformVisualizer` (Voice Playback) |
| **Generative UI Registry & Timeline Dispatcher** | **Agentic Messenger (AMP)** | `jsilvanus/agentic-messenger` | `amp-ui` | • `GenerativeUiRegistry`<br>• Timeline Model mappers (`UniversalEnvelope` → Compose) |
| **Generative UI Host & Desktop Spatial Canvas** | **Aidos** | `jsilvanus/aidos` | `agent/desktop`<br>`agent/kernel` | • Infinite Canvas Viewport & Node Coordinator<br>• Execution Graph Visualizer & Inspection Trees<br>• Spatial Node Frames & Presence Waves |
| **Core Agent Loop & Runtime Execution** | **Aidos** | `jsilvanus/aidos` | `kernel`<br>`engine`<br>`sdk` | • `AgentRuntime`, `ToolBroker`<br>• Model Adapters (`llama.cpp`, `ONNX Runtime`)<br>• `SystemOne` Decision Routing<br>• STT Transcribe Endpoint |

---

## 3. Deep-Dive: Domain Specifications & TCR Components

### 3.1. Dictator: Text Editing, Document Flow & The Red Pill

Dictator (`jsilvanus/dictator`) is the ecosystem's dedicated voice-first, cursor-aware document editor. It owns all text-editing interfaces, continuous document flows, and caret-anchored actor pills.

#### 1. The Red Pill (`PersistentRedPill` / `FloatingActorPill` / Ember Caret Actor)
- **Role:** Voice dictation orchestrator and primary STT cursor anchor.
- **Palette:** Ember Red (`0xFFEF4444`).
- **Behaviors:**
  - **Single Click / Tap:** Starts/stops voice dictation or triggers the user-level AI orchestrator. Toggles the **Happenings HUD** to inspect live actions.
  - **Double Click / Long Press:** Opens the **50% Split Transcript Drawer**, displaying real-time transcribed utterances, confidence scores, and multi-speaker breakdowns.
  - **Drag-to-Move:** Moves the caret smoothly across lines, or drags context onto a specific prior document block (`attachedTargetLabel`).

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

#### 4. Document Input Section (`DocumentInputSection`)
- Molecular Compose component featuring a soft-elevation active document tail, horizontal demarcation line, inline Ember cursor brush (`SolidColor(0xFFEF4444)`), and context target indicator.

---

### 3.2. Agentic Messenger (AMP): 4-Party Conversation Mesh & UAE

Agentic Messenger (`jsilvanus/agentic-messenger`) owns all collaborative and protocol-level messaging interfaces where humans and autonomous agents communicate over Universal Agentic Envelopes (UAE).

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

### 3.3. Aidos: Generative UI Host, Engine & Canvas

Aidos (`jsilvanus/aidos`) provides the foundational runtime, local inference models, and the desktop spatial canvas:

- **Host Canvas:** Arranges chat nodes, document nodes, and terminal sessions as spatial cards on an infinite zoomable surface.
- **Engine Connectors:** Provides local inference over `llama.cpp` (GGUF LLMs), `ONNX Runtime` (embeddings, audio, System One typed decisions), and local STT audio pipelines.
- **TCR Consumer:** In Aidos Desktop, the Generative UI engine dynamically instantiates Dictator's `DocumentInputSection` and `PillConstellationOverlay` for document/code tasks, and AMP's `MessageBubble` and `ApprovalCard` for communication tasks.

---

## 4. Cross-Repository Consumption Model

### How Ecosystem Apps Fetch & Share Components

When an application in the ecosystem needs UI components outside its core domain, it **fetches the canonical implementation from the owning repository**:

```
                       ┌────────────────────────────────────────┐
                       │          Aidos Host Runtime            │
                       │    (Infinite Canvas, Engine, SDK)      │
                       └──────────────────┬─────────────────────┘
                                          │
                  ┌───────────────────────┴────────────────────────┐
                  ▼                                                ▼
     ┌────────────────────────┐                       ┌────────────────────────┐
     │   Dictator (Owner)     │                       │ Agentic Messenger (AMP)│
     │  • DocumentInputSection│                       │  • MessageBubble       │
     │  • PersistentRedPill   │                       │  • 4-Party Mesh Cards  │
     │  • CadenceEngine       │                       │  • ApprovalCard        │
     └───────────┬────────────┘                       └───────────┬────────────┘
                 │                                                │
                 │   AMP consumes Document Editing & Red Pill     │
                 └───────────────────────►────────────────────────┘
```

### Concrete Consumer Scenarios:

#### 1. Aidos Messenger / AMP Standalone Consuming Dictator Components
- **Requirement:** Aidos Messenger requires a document composition panel or voice dictation trigger inside its active chat timeline or document view mode.
- **Implementation:** AMP does **not** create its own voice recorder pill or text input box. Instead, AMP imports:
  - `DocumentInputSection` from `dictator` for the document tail and demarcation flow.
  - `PersistentRedPill` / `FloatingActorPill` from `dictator` for the Ember Caret voice recording capsule, audio dispatch, and Happenings telemetry.

#### 2. Aidos Desktop Consuming Dictator & AMP Components
- **Requirement:** Aidos Desktop renders an IDE-style code/markdown editor card and a collaborative messenger card on the Infinite Canvas (RFC-0051).
- **Implementation:**
  - Dictator's `PillConstellationOverlay` and `CadenceEngine` are layered directly over the editor canvas.
  - AMP's `GenerativeUiRegistry.RenderItem()` is invoked directly to display incoming envelope turns and progressive disclosure approvals on spatial nodes.

---

## 5. Design System Tokens & Vocabulary

All repositories adhere to shared TCR Design Tokens to guarantee visual consistency:

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

- **Aidos (`aidos`)**: [docs/TCR_COMPONENT_DOMAIN_OWNERS.md](file:///c:/Users/jsilv/Code/aidos/docs/TCR_COMPONENT_DOMAIN_OWNERS.md) · [docs/decisions.md#d46](file:///c:/Users/jsilv/Code/aidos/docs/decisions.md) · [docs/rfcs/0051-desktop.md](file:///c:/Users/jsilv/Code/aidos/docs/rfcs/0051-desktop.md)
- **Dictator (`dictator`)**: [README.md - UI Component Ownership & Design Vocabulary (D46)](file:///c:/Users/jsilv/Code/dictator/README.md)
- **Agentic Messenger (`agentic-messenger`)**: [README.md - UI Component Domain Ownership](file:///c:/Users/jsilv/Code/agentic-messenger/README.md) · [docs/aidos-joint-app-ux.md](file:///c:/Users/jsilv/Code/agentic-messenger/docs/aidos-joint-app-ux.md)

When components are extracted into a dedicated `aidos-tcr` repository in the future, this document will serve as the initial manifest and governance specification.
