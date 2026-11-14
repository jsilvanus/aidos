# RFC-0111: Intent Graph UI Presentation Paradigms & Multi-Launcher Mobile Topology

Status: **Draft**
Phase: **3 (UX & Multi-Surface)**
Depends on: **0010 (Projects), 0019 (Execution Graph), 0024 (Resource Graph), 0050 (Mobile Architecture), 0051 (Desktop Architecture), 0052 (Runtime API), 0103 (Aidos Engine), 0106 (Desktop Generative UI), 0109 (Capture Device Protocol), 0110 (Dual-Mode Intent Graph)**
Related: **0008 (Agent Loop), 0012 (Intent Graph — superseded), 0025 (Prompt Assembly), 0049 (Platform Capability Profiles), 0057 (Glanceable and Hands-Free)**

## Abstract

This RFC defines the visual presentation paradigms, multi-agent spatial interaction models, and Android APK deployment architecture for the **Dual-Mode Intent Graph (RFC-0110)**. It specifies **seven distinct presentation paradigms** tailored for Life Management and Coding Management across Desktop and Mobile surfaces, codifies how the **Dictator Red Pill (Voice Caret)** and subagent worker pills (🔵 Blue, 🟢 Green, 🟣 Violet) project across each presentation mode, and establishes the **APK and Android Launcher topology** for Aidos Engine, Aidos Agent, and the System Assistant.

---

## 1. Android APK & Launcher Topology

### 1.1 Mobile Topology: Single Host App with Multi-Process Native Crash Isolation (D49 Settled)

Under **Decision D49** (`docs/decisions.md` lines 583–600, amended 2026-11-14):
- Android hosts everything in **one APK (`fi.italeino.aidos.engine`)**, maintaining a **single user-facing persistent notification** and eliminating notification tray clutter.
- **Multi-Process Crash Resilience**: To isolate heavy C++ native segfaults (llama.cpp / whisper.cpp) and Android Low Memory Killer (LMK) sweeps from crashing the user interface or corrupting active SQLite transactional state, the single APK uses Android's native `android:process` isolation:
  1. **Main Process (`fi.italeino.aidos.engine`)**: Lightweight process (~120–150 MB RSS) hosting UI activities (`TasksMainActivity`, `AidosMainActivity`), the System Assistant overlay, Project Management (`.aidos/state.db`), and the Dual-Mode Intent Graph engine.
  2. **Native Inference Process (`:native_inference`)**: Dedicated isolated process hosting heavy model weights (llama.cpp, whisper.cpp, ~2–4 GB RSS).
  3. **Resilience Contract**: If native inference segfaults or Android's Low Memory Killer terminates `:native_inference` under memory pressure, the Main Process (UI, Intent Graph, and active documents) **remains alive**. The runtime catches the dead Binder link (`DeadObjectException`), logs the fault, alerts the user gracefully, and warm-restarts the native inference service without losing user typing or SQLite transactional state.

**Desktop Topology Note**: Desktop hosts both Engine and Agent Runtime in one daemon process behind the loopback API (D48). Desktop proceeds joined initially, with the architectural flexibility to remain one process or split into one, two, or multiple dedicated processes/apps later as multi-window or standalone server needs evolve.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│             SINGLE HOST APK (D49 TOPOLOGY: fi.italeino.aidos.engine)                   │
├────────────────────────────────────────────────────────────────────────────────────────┤
│  OS ENTRY POINTS (Same APK, zero Binder IPC overhead for Intent Graph):                │
│  1. 👤 "Aidos Tasks" Launcher Activity  ──► Boots into "personal" Life Project         │
│  2. 💻 "Aidos Projects" Launcher Activity ──► Boots into Developer / Git Workspace     │
│  3. 🔴 System Assistant (VoiceInteraction) ──► Floating Red Pill System HUD            │
├────────────────────────────────────────────────────────────────────────────────────────┤
│  PROCESS 1 (Main / UI & Runtime — ~150 MB):                                            │
│  • TasksMainActivity & AidosMainActivity                                               │
│  • VoiceInteractionSession (Floating Red Pill Overlay)                                 │
│  • Agent Runtime & Dual-Mode Intent Graph (.aidos/state.db)                            │
│  • Execution Graph (RFC-0019) & Resource Graph (RFC-0024)                              │
├────────────────────────────────────────────────────────────────────────────────────────┤
│                         ▲ Binder IPC (Crash-Isolated Boundary)                         │
│                         ▼ (android:process=":native_inference")                        │
├────────────────────────────────────────────────────────────────────────────────────────┤
│  PROCESS 2 (:native_inference — ~2.5 GB):                                              │
│  • Engine Core: llama.cpp / whisper.cpp native binaries & model weights in VRAM        │
│  • Local loopback HTTP inference server (RFC-0103) & ACP sink (RFC-0109)               │
└────────────────────────────────────────────────────────────────────────────────────────┘
```
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Multi-Launcher Manifest Architecture

A single installed APK (`dev.aidos.androidapp`) provides two distinct home screen launcher icons using distinct `android:taskAffinity` attributes. This gives each surface its own entry in the Android App Switcher (Recents screen) while sharing the same in-memory `RuntimeClient` and SQLite database:

```xml
<!-- 1. Life Management Launcher -->
<activity
    android:name=".ui.tasks.TasksMainActivity"
    android:label="Aidos Tasks"
    android:icon="@mipmap/ic_launcher_tasks"
    android:taskAffinity="dev.aidos.tasks"
    android:launchMode="singleTask"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>

<!-- 2. Developer & Coding Workspace Launcher -->
<activity
    android:name=".ui.coding.AidosMainActivity"
    android:label="Aidos"
    android:icon="@mipmap/ic_launcher_aidos"
    android:taskAffinity="dev.aidos.coding"
    android:launchMode="singleTask"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>

<!-- 3. System Assistant Service (Power Button / Corner Swipe) -->
<service
    android:name=".assistant.AidosVoiceInteractionService"
    android:label="Aidos Assistant"
    android:permission="android.permission.BIND_VOICE_INTERACTION"
    android:exported="true">
    <meta-data
        android:name="android.voice_interaction"
        android:resource="@xml/voice_interaction_service" />
    <intent-filter>
        <action android:name="android.service.voice.VoiceInteractionService" />
    </intent-filter>
</service>
```

### 1.3 Project Hierarchy & Unified Action In-Basket

1. **System Root Project (`"personal"`)**: Upon installation, Aidos provisions a root project at `<internal_storage>/life/` (`project_type = 'LIFE'`). Tapping **Aidos Tasks** bypasses all project pickers and boots straight into this personal Intent Graph.
2. **Developer Projects (`"~/src/<repo>"`)**: Developer projects (`project_type = 'CODE'`) are Git-backed repositories selected inside the **Aidos (Coding)** launcher.
3. **Cross-Project Aggregation**: `TasksMainActivity` displays personal errands while automatically projecting unblocked `HUMAN` review items from coding projects (e.g. `[my-backend] Review Auth.kt patch`), enabling the user to approve AI agent outputs in one unified feed.

---

## 2. Seven Presentation Paradigms for the Intent Graph

RFC-0110 defines nodes (`GROUP`, `GOAL`, `SUB_GOAL`, `ATOMIC_TASK`, `CONSTRAINT`, `ACCEPTANCE_CRITERION`), edges (`DEPENDS_ON`, `CONSTRAINS`, `ACCEPTS`), actor ownership (`HUMAN`, `AGENT`, `HYBRID`), derived statuses (`NOT_STARTED`, `IN_PROGRESS`, `BLOCKED`, `NEEDS_REVIEW`, `DONE`, `STALE`, `CONFLICTED`), and dispatch policies (`INHERIT`, `MANUAL_START`, `AUTO_WHEN_READY`).

Below are seven distinct visual presentation paradigms that map these data structures into user interfaces.

---

### Design 1: The Relay River (Dual-Track Handoff Flow)

* **Mental Model:** A **relay race along a flowing river**, where execution moves downstream while a "baton" is passed back and forth between two parallel tracks: the **Human Track** and the **Agent Track**.
* **Visual Layout & Mechanics:**
  * Two parallel flowing lanes (Left = *Human Track*, Right = *Agent Track*), separated by a central *Handoff Channel* where hybrid tasks, checkpoints, and acceptance criteria dock.
  * Nodes appear as tactile stones sized by granularity (`GROUP` = wide reservoir, `ATOMIC_TASK` = compact stone).
  * `DEPENDS_ON` edges are directional currents flowing downstream. When an edge crosses lanes (e.g. Agent $\rightarrow$ Human), it renders as an animated **Baton Vector**.
  * `CONSTRAINS` edges appear as lateral guide rails. `ACCEPTS` criteria appear as physical locks docked on the output edge.
  * **Desktop:** Dual-pane widescreen canvas with an inspection drawer for file diffs and step logs.
  * **Mobile:** Single-column vertical stream with a pinned **"Current Baton"** card in the thumb zone for one-tap completion or handoff.
* **Life Management:** Highlights reciprocal handoffs (Agent drafts flight options $\rightarrow$ passes baton to Human *"Select flight"* $\rightarrow$ passes baton back to Agent *"Book reservation"*). Recurring tasks resemble water wheels resetting upstream.
* **Coding Management:** Mobile leaf tasks feature phone badges and fuel meters for `estimated_steps`. Unverified mobile runs dock in the center channel glowing in amber `NEEDS_REVIEW` until a desktop test confirmation flushes the downstream river.

---

### Design 2: The Transit Metro Map (Multi-Scale Topological Network)

* **Mental Model:** A **schematic urban transit map** (e.g., London Underground, Tokyo Metro).
* **Visual Layout & Mechanics:**
  * Top-level `GOAL`s and `GROUP`s are color-coded **Metro Lines** (*Blue Line: Database Migration*, *Orange Line: Weekly Errands*).
  * `SUB_GOAL`s are major **Transfer Hubs** where lines intersect; `ATOMIC_TASK`s are **Local Stations** along the line.
  * Tracks are `DEPENDS_ON` edges. Signals ahead are green (`DONE`) or red (`BLOCKED`). `ACCEPTANCE_CRITERION` nodes appear as **Ticket Turnstiles** at station exits.
  * Station platform styling denotes ownership: solid circular platforms for Human, automated turnstile platforms for Agent, and dual-tone platforms for Hybrid.
  * **Desktop:** Semantic-zoomable map from macro network down to station line telemetry.
  * **Mobile:** **"Next Stop" Commuter Strip** showing the current station, upcoming stops, transfer requirements, and line delays.
* **Life Management:** Glancing at the "Daily Commute Line" highlights stations requiring human action as "Boarding Stations", while the agent acts as the automated train advancing between stops.
* **Coding Management:** Desktop planners lay down new "Spur Lines". A mobile phone model pulls into a local shuttle station, but encounters a **Customs / Inspection Gate** (`NEEDS_REVIEW`). Later, the desktop express engine conducts the `SHELL_COMMAND` safety inspection, turns the signal green, and advances the line.

---

### Design 3: The Modular Cartridge Dock (Tactile Kanban-Matrix)

* **Mental Model:** A **hot-swappable hardware assembly rack / cartridge bay**.
* **Visual Layout & Mechanics:**
  * A 2D structured matrix where rows represent **Intent Hierarchies** (`GOAL` $\rightarrow$ `SUB_GOAL`) and columns represent **Readiness Horizons** (`BLOCKED (Upstream Missing)` $\rightarrow$ `READY TO DISPATCH` $\rightarrow$ `IN FLIGHT` $\rightarrow$ `VERIFY & DOCK` $\rightarrow$ `DONE`).
  * Nodes are physical **Execution Cartridges** displaying:
    * Actor badge (`HUMAN` hand tool, `AGENT` chip, `HYBRID` handshake).
    * Device badge (`📱 Mobile-Local`, `🖥️ Desktop`, `☁️ Cluster`).
    * Fuel meter for `estimated_steps` and file count badges for `scope_paths`.
    * Dispatch toggle (`AUTO` lightning bolt vs `MANUAL` press-to-run).
  * `DEPENDS_ON` edges are physical locking pins on the cartridge edge: when locked, the cartridge cannot be dragged or dispatched out of the `BLOCKED` column.
  * **Desktop:** Full multi-goal matrix board with diff side-by-side inspection.
  * **Mobile:** **"Cartridge Deck"** carousel filtered strictly for `target_profile = MOBILE` tasks that fit local phone memory: swipe right to dispatch to local model, tap to complete manually.
* **Life Management:** Tangible index-card feel. Hybrid tasks appear as split cards where the top half shows the agent's drafted summary and the bottom half presents the user's action trigger.
* **Coding Management:** Guards against context blowouts by showing repo file paths as tactile chips (*"Touches 2 files, 5 estimated steps — safe for local phone model"*). Completed mobile runs park in `VERIFY & DOCK` until a one-click desktop test run verifies them into `DONE`.

---

### Design 4: The Radial Radar & Horizon (Concentric Actionability Rings)

* **Mental Model:** An **air traffic control radar / orbital gravity well**.
* **Visual Layout & Mechanics:**
  * Concentric circular rings radiating outward from the center:
    * **Center (The Strike Zone):** Unblocked, immediately actionable tasks (`NOT_STARTED` with 0 blocking prerequisites).
    * **Inner Orbit (In Flight):** `IN_PROGRESS` and `NEEDS_REVIEW` tasks.
    * **Outer Orbit (Horizon):** `BLOCKED` tasks waiting on upstream dependencies, grouped into radial goal wedges.
    * **Perimeter Ring (Sealed):** Completed `DONE` nodes fading out into constellation stars.
  * `DEPENDS_ON` edges are gravitational tractor beams pulling outer blocked nodes toward the center as their prerequisites complete.
  * **Desktop:** 360-degree interactive radar HUD with causal trajectory inspection.
  * **Mobile:** **180-degree forward horizon arc** along the bottom thumb zone: only tasks in the immediate Strike Zone are reachable, with upcoming blocked tasks visible along the curved trajectory above.
* **Life Management:** Radical reduction of cognitive overload. Users only see the 2 or 3 blips currently inside the Strike Zone. As the agent finishes prerequisites in the background, dependent human tasks glide into the center zone.
* **Coding Management:** Mobile devices automatically filter the Strike Zone for blips matching `target_profile IN ('MOBILE', 'ANY')` and `LOCAL_ONLY`. When a mobile run finishes, it docks in the Inner Orbit as an amber blip until a desktop sweep validates criteria and pulls the next atomic task into the Strike Zone.

---

### Design 5: The Logic Circuit Breadboard (Active Power & Schematic Flow)

* **Mental Model:** An **electronic breadboard / printed logic circuit** with voltage rails, logic gates, and illuminated conductive traces.
* **Visual Layout & Mechanics:**
  * Clean, dark-mode electronic schematic. Power enters from the left (User Intent & Root Goals) and flows rightward toward Output Terminals (Delivered Value).
  * Nodes are **Integrated Circuit (IC) Chips**:
    * Input pins on the left are `DEPENDS_ON` dependencies. Output pins on the right lead downstream.
    * Top/bottom pins are `CONSTRAINS` (resistors/bounds) and `ACCEPTS` (multimeter/test probes).
  * Edges are **Conductive Wire Traces**:
    * Cold/dim wire = upstream incomplete (`BLOCKED`).
    * Glowing energized wire (with pulsing electron particles) = prerequisite fulfilled, voltage flowing into downstream chip.
  * Component styling encodes actor: `HUMAN` = physical toggle switch; `AGENT` = automated silicon IC; `HYBRID` = solenoid relay.
  * **Desktop:** Full zoomable schematic CAD board with terminal runners and audit oscilloscopes.
  * **Mobile:** Illuminated **Component Strip** showing live input leads, an action toggle, and output leads that light up when flipped.
* **Life Management:** Crystal-clear causal determinism. Shows the cold, unpowered wire leading from the agent's incomplete task. Flipping a human switch sends an immediate surge of glowing power down the wire to wake the agent.
* **Coding Management:**
  * `MANUAL_START` chips feature a physical push-button; `AUTO_WHEN_READY` chips feature a transistor gate that auto-fires once input voltage hits high logic level.
  * Split verification is represented as a **Diode / Voltage Gate** labeled `ACCEPTANCE CRITERIA (SHELL_COMMAND)`. The gate blocks forward current (`NEEDS_REVIEW`) until the Desktop Test Runner applies a passing test signal, breaking down the barrier and releasing current downstream.

---

### Design 6: The Causal Outliner & Action River (Linear-First System)

* **Mental Model:** A **keyboard-driven hierarchical outliner with inline dependency jump pills**.
* **Visual Layout & Mechanics:**
  * Multi-level nested list structured into three collapsible sections:
    1. **Action Horizon:** Flat stream of unblocked actionable items sorted by priority and due date.
    2. **Intent Tree:** Collapsible `GROUP` $\rightarrow$ `GOAL` $\rightarrow$ `SUB_GOAL` $\rightarrow$ `ATOMIC_TASK` outline.
    3. **Verification Ledger:** Items in `NEEDS_REVIEW` parked with acceptance criteria test blocks.
  * Rows feature actor badges, scope chips, and derived status indicators.
  * `DEPENDS_ON` edges are represented via:
    * Inline Causal Badges: `"Blocked by: #12 (Add milk)"` (clicking jumps directly to prerequisite).
    * Gutter Traces: Vertical hairline brackets connecting prerequisite rows to dependent rows on hover.
  * `ACCEPTS` criteria nest directly underneath atomic task rows as checklist items with check types (`[SHELL_COMMAND] ./gradlew test`).
  * **Desktop:** 3-column split view (Goal Outline | Active Task Stream | Inspector Drawer with diffs & proposals). Full keyboard navigation (`j`/`k`, `tab`, `space`).
  * **Mobile:** Tactile single list with swipeable actions (swipe right = complete/approve, swipe left = show dependency sheet).
* **Life Management:** Fast, familiar task manager feel (Todoist/Things 3). Agent tasks show live spinners; unblocking a human task promotes it to the top of "Today" with an ambient chime.
* **Coding Management:** High-density code outline view. Pinned `scope_paths` appear as clickable tokens opening files in the IDE. Completed mobile tasks display an inline amber verification pill with a one-click desktop test execution trigger.

---

### Design 7: The Intent Mind-Map Constellation (Desktop "Whole Picture" Canvas)

* **Mental Model:** A **galaxy of intent (infinite 2D/3D node-link spatial graph)**.
* **Visual Layout & Mechanics:**
  * Infinite zoomable canvas built on Compose Canvas / WebGL with semantic zoom:
    * *Macro Level (Zoom < 30%)*: System Galaxy. `GROUP` nodes appear as labeled nebulas or clusters. Edges between groups represent macro system constraints.
    * *Meso Level (Zoom 30% - 70%)*: Goal Architecture. `GOAL` and `SUB_GOAL` nodes expand into visual cards with completion progress arcs and dependency highways.
    * *Micro Level (Zoom > 70%)*: Atomic Blueprint. Nodes expand into full inspection cards displaying `scope_paths`, criteria test specs, step counts, and live execution terminals.
  * **Node Styling**:
    * `GROUP`: Translucent bounding hulls grouping child nodes.
    * `GOAL` / `SUB_GOAL`: Rounded hexagonal nodes with progress rings.
    * `ATOMIC_TASK`: Compact capsules color-coded by actor (Gold = `HUMAN`, Cyan = `AGENT`, Purple = `HYBRID`).
    * `ACCEPTANCE_CRITERION`: Diamond satellite nodes orbiting tasks connected via `ACCEPTS` lines.
    * `CONSTRAINT`: Shield nodes broadcasting a boundary aura via `CONSTRAINS` lines.
  * **Edge Geometry**: Bézier curved lines with animated directional flow particles. Thickness denotes critical path weight; pulsing red lines denote `CONFLICTED` merge states.
  * **Desktop:** Full multi-monitor canvas with minimap radar, force-directed DAG layout auto-arrangement, and lasso multi-selection for proposal batches.
  * **Mobile:** Gyroscope-assisted spatial viewer or localized cluster viewer centered on the currently active node with breadcrumb navigation back to root.
* **Life Management:** Spatially organizes life balance: "Home" cluster in one sector, "Fitness" in another, "Side Projects" in another. Agent handoffs bridge light beams across clusters.
* **Coding Management:** High-altitude architecture comprehension. Clicking any goal illuminates the critical path sequence across multiple modules. Nodes tagged `target_profile = MOBILE` are badged with mobile device indicators, clearly visualizing which subset of work is mobile-executable.

---

## 3. Multi-Agent Pill & Dictator Integration Across All 7 Designs

Per **RFC-0106** and **RFC-0051**, Dictator provides speech-to-intent transcription, and multi-agent pills provide tangible visual embodiment for AI coordination:
* 🔴 **Red Pill (Orchestrator)**: Dictator voice companion, caret anchor, intent parser.
* 🔵 **Blue Pill (Code Worker)**: Executes atomic coding tasks, edits files within `scope_paths`.
* 🟢 **Green Pill (Test/Verifier Worker)**: Runs checks, verifies acceptance criteria.
* 🟣 **Violet Pill (Life / R&D Worker)**: External research, web search, scheduling.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                   THE PILL ECOSYSTEM IN THE UI                                  │
├─────────────────────────────────────────────────────────────────────────────────────────────────┤
│  🔴 Red Pill (Dictator STT & Orchestrator):                                                     │
│     Glides to active caret / selected node -> listens ("Add test for Auth") ->                 │
│     proposes node/edge mutations -> spawns worker pills to execute.                             │
│                                                                                                 │
│  🔵 Blue Pill (Code Worker):                                                                    │
│     Pours into atomic task node -> locks scope_paths -> streams step count & diff.              │
│                                                                                                 │
│  🟢 Green Pill (Verifier):                                                                      │
│     Attaches to ACCEPTANCE_CRITERION -> runs SHELL_COMMAND / checks -> turns criteria green.    │
│                                                                                                 │
│  🟣 Violet Pill (Life / R&D):                                                                   │
│     Handles web APIs, shopping list scraping, calendar syncing -> hands off to 👤 Human.        │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Pill Integration Matrix Across Paradigms

| Paradigm | 🔴 Red Pill (Dictator Voice Orchestrator) | 🔵 Blue Pill (Code Worker) | 🟢 Green Pill (Test Verifier) | 🟣 Violet Pill (Life & R&D) |
|---|---|---|---|---|
| **1. Relay River** | Floats as a buoyant buoy; spoken intents ripple the water and create tasks across lanes | Swims down Agent track; docks at active station stones | Dives into central handoff channel; seals unverified checkpoints | Gathers external data on right track; passes baton to Human track |
| **2. Transit Metro** | Acts as Dispatcher's Tower; dictating speech builds spur lines in real time | Rides train between local stations, lighting up track behind it | Operates Ticket Turnstiles; inspects arriving mobile shuttle trains | Automates shuttle schedules; surfaces boarding notifications |
| **3. Cartridge Dock** | Robotic magnetic arm; spoken dictation inserts task cartridges into rack slots | Inserts directly into cartridge slot; powers step meter and file chips | Snaps into `VERIFY & DOCK` bay to conduct mechanical check | Populates shopping/calendar cartridges with external API data |
| **4. Radial Radar** | Sits at Epicenter Bulls-eye; voice commands send radar pings into orbital wedges | Drone probe orbiting task blips, pulling them toward Strike Zone | Sweeps outer perimeter, scanning `NEEDS_REVIEW` blips into confirmed stars | Pulls researched personal items from outer horizon into Strike Zone |
| **5. Logic Circuit** | Functions as Logic Probe; touching any IC chip lets user dictate edits | Plugs into IC chip socket, drawing current and lighting registers | Oscilloscope probe clipped to test pins; gates forward voltage | Solenoid relay charging downstream human toggle switches |
| **6. Causal Outliner** | Native home: sits at typing caret; live ghost text streams into new outline rows | Badged onto active rows: `[🔵 Auth.kt (step 2/5)]` with shimmer gutter | Badged onto criteria rows: `[🟢 Running tests...]` $\rightarrow$ checkmark | Injects external research summaries into child item notes |
| **7. Mind-Map** | Spatial drone following cursor; speech emits radial ripples spawning child nodes | Glides across canvas between nodes trailing glowing particle traces | Orbits diamond criteria satellites, pulsing green when verified | Gathers data across goal clusters, linking life and code nodes |

---

## 4. UI Design Selection Matrix

| Surface | Recommended Primary Design | Recommended Secondary / Focus Design |
|---|---|---|
| **Mobile — Life Tasks (`Aidos Tasks`)** | **Design 6: Causal Outliner** | **Design 1: Relay River** |
| **Mobile — Quick Glance / Assistant Overlay** | **Design 4: Radial Horizon Arc** | **Design 1: Pinned Baton Card** |
| **Mobile — Coding Tasks (`Aidos`)** | **Design 3: Filtered Cartridge Deck** | **Design 6: Causal Outliner** |
| **Desktop — Architecture & Whole Picture** | **Design 7: Mind-Map Constellation** | **Design 2: Transit Metro Map** |
| **Desktop — Active Coding Execution** | **Design 3: Modular Cartridge Dock** | **Design 5: Logic Breadboard** |
| **Desktop — Rapid Keyboard Navigation** | **Design 6: Causal Outliner** | **Design 3: Modular Cartridge Dock** |
