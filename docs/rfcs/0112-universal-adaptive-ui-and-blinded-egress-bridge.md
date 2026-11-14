# RFC-0112: Universal Adaptive UI Architecture & The Blinded Egress Bridge

**Status:** Accepted 2026-11-14  
**Date:** 2026-11-14  
**Authors:** Aidos Core Architecture Team  
**Related RFCs:** [RFC-0010](0010-projects.md), [RFC-0027](0027-trust-and-taint.md), [RFC-0042](0042-networking-and-egress.md), [RFC-0047](0047-project-templates-and-project-types.md), [RFC-0050](0050-android.md), [RFC-0051](0051-desktop.md), [RFC-0106](0106-desktop-generative-ui.md), [RFC-0110](0110-dual-mode-intent-graph.md), [RFC-0111](0111-intent-graph-ui-paradigms-and-mobile-topology.md)  
**Related Decisions:** D42 (Taint), D49 (Single APK, Multi-Process Native Isolation), D50 (Blinded Egress Bridge)

---

## 1. Executive Summary & Problem Statement

This RFC addresses two core architectural requirements:

1. **Universal UI Parity Across Android and Desktop:**
   Users must be able to run every UI presentation paradigm (Mind-Map / 2D Canvas, Tree-List, Pipeline, Kanban, Timeline, Radar, Living Document) on **both Desktop and Android**. On phones, compact touch-friendly views (Inbox, Tree-List, Kanban) are default, while expansive spatial views (Infinite ZUI Canvas, Living Document side-by-side) are tucked into an expander or triggered automatically when entering **OTG / Desktop mode** (e.g., Samsung DeX, Motorola Ready For, external USB-C monitor, keyboard/mouse docking). The entire UI codebase ships in the single Android APK (`fi.italeino.aidos.engine`).

2. **The Messaging & Egress Dilemma (`LIFE` vs. `WORK`/`CODE`):**
   `LIFE` projects enforce strict privacy (`routing.remote_egress = never` per RFC-0047), ensuring personal notes, family transcripts, and financial records never leave the local device. However, messaging (Matrix, Signal, Telegram, SMS, Email) naturally involves communication with external contacts about life events (e.g. "Pick up groceries", "Family dinner") or code/work tasks (e.g. "PR #42 is ready"). If a project cannot emit network packets, how can messaging interact with Life projects without compromising security, violating taint rules (RFC-0027), or leaking private project data?

---

## 2. Universal Adaptive UI Architecture

### 2.1 View Mode Parity Matrix

Both Compose Multiplatform (Desktop) and Jetpack Compose (Android) share the same underlying composable implementations. No view is omitted from the Android APK.

| View Paradigm | Mobile Handheld Default | Mobile OTG / DeX / Tablet | Desktop Default |
|---|---|---|---|
| **Action Inbox** | **Primary Screen** (Tab 1) | Collapsible Left HUD | Top-level Drawer / `Cmd+I` |
| **Hierarchical Tree-List** | **Projects Screen** (Tab 2) | Left Project Sidebar | Left Intent Drawer |
| **Hierarchical Kanban** | Swipeable View | Multi-column Swimlane | Multi-column Swimlane |
| **Phase-Gated Pipeline** | Vertical Stepper Rail | Horizontal Flow Rail | Horizontal Flow Rail |
| **Focus Ring / Radar** | Concentric Circle Overlay | Widget in Dashboard | Macro HUD Overlay |
| **Timeline / Gantt** | Vertical Chrono-List | Full Gantt Plane | Full Gantt Plane |
| **2D Infinite Canvas (ZUI)** | Hidden under `[Expand Canvas]` | **Active Viewport** | **Active Viewport** |
| **Unified Living Document** | Fullscreen Sheet | Split-pane with Canvas | Continuous Micro Surface |

### 2.2 Layout Adaptability & OTG Transition

Aidos evaluates the device configuration dynamically using Android's `WindowSizeClass` and display attributes:

```kotlin
enum class FormFactor {
    HANDHELD_COMPACT,   // Normal phone in portrait (< 600dp width)
    HANDHELD_EXPANDED,  // Foldable unfolded / small tablet (600dp - 840dp)
    DESKTOP_DOCKED      // OTG monitor, Samsung DeX, or Desktop App (>= 840dp, mouse/keyboard present)
}
```

```
                     ┌───────────────────────────────┐
                     │ WindowSizeClass & Input Check │
                     └───────────────┬───────────────┘
                                     │
           ┌─────────────────────────┼─────────────────────────┐
           ▼                         ▼                         ▼
   [HANDHELD_COMPACT]       [HANDHELD_EXPANDED]        [DESKTOP_DOCKED]
   • Bottom Navigation      • Dual-Pane Navigation    • Top Menu & Command Bar
   • Glanceable Inbox       • Tree + Detail Editor    • Full 2D ZUI Canvas
   • Tree-List & Cards      • Split Kanban Columns    • Multiverse Scale 0.0-1.0
   • Canvas is 1-Tap Toggle • Floating Red Pill       • Docked Red Pill Caret
```

#### The OTG / DeX Experience:
When a phone is plugged into a USB-C dock or monitor:
1. `Configuration.uiMode` and `WindowMetrics` report desktop-class dimensions ($\ge 1920\times 1080$).
2. The UI immediately transitions from the mobile tabbed navigation to the **RFC-0106 Infinite Canvas** with full mouse wheel zooming, right-click context menus, and multi-window living documents.
3. The background inference (`:native_inference`) and agent runtime (`fi.italeino.aidos.engine`) continue uninterrupted without restarting.

#### On Handheld Mobile:
The 2D Infinite Canvas is accessible via a persistent floating action button or gesture:
- Tapping **`[◎ Canvas]`** in the top bar expands the project into full-screen pan-and-zoom mode.
- Two-finger pinch zooms between Galaxy view and node clusters.
- Tapping any node collapses back to the mobile-optimized detail view or opens the single-column Living Document.

---

## 3. The Blinded Egress Bridge (Solving Messaging vs. Egress-Free Life)

### 3.1 The Architectural Conflict

- **Rule 1 (RFC-0047 & RFC-0036):** A `personal` (`LIFE`) project has `routing.remote_egress = never`. The runtime actively rejects any network socket creation, HTTP request, or external RPC originating from or holding taint from this project.
- **Rule 2 (RFC-0027):** **Taint Propagation.** Data read from a confidential scope taints downstream variables. If an agent reads a private journal node, its memory becomes tainted; if it then attempts to send a Telegram message, taint tracking blocks the operation.
- **The Requirement:** The user receives a message: *"Are you free for dinner at 7 PM?"* or *"Can you buy milk?"*. The system needs to check schedule availability or add a grocery task to the Life project, without exposing the user's private database to the network.

### 3.2 The Three Compartments & The Blinded Bridge

To resolve this, Aidos introduces **The Blinded Egress Bridge**:

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 AIDOS RUNTIME ENVIRONMENT                              │
├───────────────────────────────┬───────────────────────────┬────────────────────────────┤
│     COMPARTMENT 1: MESSAGING  │   COMPARTMENT 2: BRIDGE   │    COMPARTMENT 3: LIFE     │
│   (Egress Allowed: Matrix,    │ (Information Flow Control │   (Air-Gapped: SQLite,     │
│    Signal, SMS, Telegram)     │  & Human Approval Gate)   │    Local Models, Git)      │
├───────────────────────────────┼───────────────────────────┼────────────────────────────┤
│                               │                           │                            │
│  Incoming Network Message     │                           │                            │
│  "Free for dinner at 7?" ────►│ Blinded Intent Query ────►│ Schedule Evaluation        │
│                               │ { action: "CHECK_BUSY",   │ (Reads private calendar)   │
│                               │   window: "19:00-21:00" } │ Checks conflicts: YES      │
│                               │                           │                            │
│                               │ Blinded Boolean Reply ◄───│ Returns sanitized response │
│                               │ { busy: true }            │ (No titles, no notes)      │
│                               │                           │                            │
│  Draft Outgoing Reply         │                           │                            │
│  "I'm busy at 7 tonight." ◄───┤ Checks Taint & Policy     │                            │
│                               │                           │                            │
│  [Human Review / Send] ──────►│ Network Egress Permitted  │                            │
│                               │                           │                            │
└───────────────────────────────┴───────────────────────────┴────────────────────────────┘
```

### 3.3 Protocol Specifications for the Blinded Bridge

#### 1. Ingress (External Message $\rightarrow$ Life Intent Graph)
Incoming messages have **no confidential taint** (they are external inputs).
- A message arriving via Matrix/SMS/Email is received by the **Messaging Adapter** in the user-scope workspace.
- The user can route the intent into the `LIFE` project using typed Intent Proposals:
  ```kotlin
  data class IngressIntentProposal(
      val sourceContact: String, // e.g. "+358401234567"
      val rawContent: String,    // "Can you pick up oat milk?"
      val targetProjectId: UUID  // Life project
  )
  ```
- **The In-Flight Privacy Vault (RFC-0106 §5)** ingests the proposal into the Life project:
  - Direct identifiers are localized.
  - The node is created as a `TODO` item under the Life project's `Grocery` goal node.
  - **Crucially:** Data flows **inward** (Low Confidentiality $\rightarrow$ High Confidentiality). Under Bell-LaPadula / Biba integrity models, writing inward is fully permitted.

#### 2. Egress (Life Knowledge $\rightarrow$ Outgoing Message)
Data flowing **outward** (High Confidentiality $\rightarrow$ Low Confidentiality/Network) is strictly forbidden from carrying raw node contents or unredacted transcripts.

To communicate outward, the system uses **Blinded Queries**:
1. **Typed Capability Contracts:**
   The messaging agent cannot call `readNode()` on a Life project. It can only call strongly-typed, schema-validated questions:
   - `CheckAvailability(timeRange: InstantRange): BusyStatus`
   - `GetContactAlias(handle: String): ContactHandle`
2. **Boolean / Enum Sanitization:**
   The Life orchestrator executes the query locally against its encrypted SQLite database and returns **only** `{ status: BUSY }` or `{ status: FREE }`.
   - It **never** returns event descriptions ("Dinner with Alice"), locations, attendees, or notes.
   - The returned boolean is un-tainted metadata.
3. **Drafting and the Outbox Queue:**
   When an agent in the messaging service generates an outgoing message draft:
   - If the content was derived from human input or a blinded query, it enters the **Messaging Outbox**.
   - If the intent originated from a `LIFE` node directly (e.g. an agent in Life generated a grocery delivery order to an external API), the operation **Parks** (RFC-0006) and surfaces an **Explicit Human Egress Gate Card**:
     ```
     ┌────────────────────────────────────────────────────────┐
     │ ⚠️  OUTGOING MESSAGE APPROVAL                          │
     │ Project: Personal Life                                 │
     │ Destination: +358 40 123 4567 (SMS)                   │
     │ Content: "I can't make it at 7, let's meet at 8."      │
     │                                                        │
     │ [ Reject & Keep Local ]         [ Approve & Transmit ] │
     └────────────────────────────────────────────────────────┘
     ```
   - Tapping **Approve & Transmit** creates an explicit cryptographic capability grant that declassifies *that specific message buffer* for network dispatch. The Life database itself remains completely air-gapped.

---

## 4. Multi-Process Android Implementation Details

As approved in **Decision D49**:
- **Package:** `fi.italeino.aidos.engine` (Single APK).
- **Process 1 (`:main`):**
  - UI layer (Compose Multiplatform adaptive screens: Phone, Foldable, DeX).
  - Agent Runtime (`SessionManager`, `IntentGraphRepository`, `ToolBroker`).
  - Scoped storage Git management (`JGit`).
  - Blinded Egress Bridge controller.
- **Process 2 (`:native_inference`):**
  - C++ native shared libraries (`libllama.so`, `libwhisper.so`).
  - Isolated from UI and agent memory; crashes (SIGSEGV/OOM) are caught via Unix domain socket / Binder and restarted transparently without killing active project edits or sessions.
- **Foreground Notifications:**
  - Standard mode: One persistent notification for the active engine/runtime service.
  - When intensive local inference is active: An informative progress channel indicates GPU/NPU utilization and tokens-per-second, satisfying Android OS background runtime constraints.

---

## 5. Summary of Architectural Guarantees

1. **Every screen on every platform:** The complete 2D Infinite Canvas, Living Document, Tree-List, Pipeline, and Kanban exist inside the single APK, automatically adapting to phone screens, tablets, foldables, and external desktop monitors (OTG/DeX).
2. **Zero accidental leaks:** `LIFE` projects remain zero-egress. External communication occurs only through typed Blinded Queries (inward flow or Boolean outward flow) or explicit user-confirmed Egress Gates.
3. **Seamless cross-domain coordination:** You can manage work repositories, personal goals, and cross-channel messaging in a single unified operating environment.
