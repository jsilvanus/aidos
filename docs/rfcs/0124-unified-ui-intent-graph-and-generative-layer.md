# RFC-0124: Unified Aidos UI: Intent Graph Views, the Generative Layer and the Android Handheld

Status: Accepted 2026-11-16, amended 2026-11-18. Views, shell and placement exist; data wiring, live updates, editing and the generative layer are being built (2026-11-18 plan). Not Implemented: see the Amendment and Post-MVP Work.
Non-MVP Work: **Remaining** (see Post-MVP Work)

Related: RFC-0106 (generative UI), RFC-0110 (dual-mode intent graph), RFC-0111 (presentation paradigms and launchers), RFC-0112 (universal adaptive UI, blinded egress), RFC-0113 (egress gate), RFC-0118 (Android host), RFC-0119 (Engine app UI), RFC-0123 (Trusted Component Registry), D46, D49.

## Abstract

RFC-0106 designed a generative desktop UI. RFCs 0110 to 0112 designed the intent graph and twelve ways to show it, on phone and desktop. They were written separately, so nothing says how a generated component, an intent node and a view fit together, or which views a phone must have to be usable. This RFC is the integrating design. It defines four layers, one view matrix across form factors, how the generative layer attaches to intent nodes, and exactly which Android handheld views are MVP and which come after. It does not replace 0106, 0110, 0111 or 0112; it owns the matrix and the joins between them.

## Motivation

Android is the surface people will use most, and it must be a working app before it is a complete one. The intent graph views already render on Android through shared Compose (`AidosUniversalApp`), but the app offered a desktop-shaped navigation: the infinite canvas sat in the phone's bottom bar although RFC-0112 says it hides behind "Expand Canvas" on a handheld, and Gates, the screen where approvals happen, was a chip nobody would find. Meanwhile the generative layer RFC-0106 describes has no code at all: no A2UI manifest parser or renderer exists, only fixed components. Without one design, the generative work and the intent graph work will keep drifting.

## Goals

- One model of the UI in four layers, each with a named owner RFC and a named place in code.
- One view matrix: for every view, where it is offered on a phone, a foldable or tablet, and desktop or DeX.
- A tight Android handheld MVP that is usable alone: see what needs me, work a task, approve, read and dictate a document, switch project.
- A path from fixed views to a generative layer without reworking the views.

## Non-goals

- New view paradigms. The twelve in `ViewModeCatalog` are the set.
- Changing the intent graph data model (RFC-0110) or the TCR ownership rule (RFC-0123).
- Arbitrary agent-written UI code. Agents only emit manifests over vetted components.

## Design

### Four layers

| Layer | What it is | Owner | In code |
|---|---|---|---|
| 1. Data | Intent nodes, edges, derived status, proposals, approvals | RFC-0110, RFC-0052 | `agent/intent`, `agent/api` |
| 2. Views | Presenters that turn layer 1 into a screen, one per paradigm, plus front doors | RFC-0111, RFC-0112 | `agent/ui-common` presenters and screens |
| 3. Generative layer | Declarative manifests that assemble vetted components next to a node | RFC-0106 section 3 to 4 | not built; fixed `InlineToolBlock` and TCR molecules today |
| 4. Presence | Pills, caret, voice, notifications, approval gates | RFC-0106 section 2, RFC-0112 | `pills/`, `assistant/`, `frontdoors/gates` |

The rule between layers: a view never reads storage, it reads a presenter fed by `RuntimeClient`; the generative layer never draws, it names TCR components; presence never mutates the graph, it proposes (RFC-0110 proposals) or asks for approval.

### Views

The presenters are shared between Android and desktop and are tested on the JVM. `ViewModeCatalog` (in `agent/ui-common`, layout package) is the single list of views and their placement per form factor; `AidosUniversalApp` builds the navigation, the view chips and the command palette from it, and `ResponsiveScaffold` takes a per-form-factor destination list.

| View | Phone (compact) | Foldable, tablet | Desktop, DeX | Android tier |
|---|---|---|---|---|
| Action Inbox | primary | primary | primary | MVP |
| Causal Outliner | primary | primary | primary | MVP |
| Gates (approvals, egress, split verification) | primary | primary | more | MVP |
| Living Document | primary | primary | primary | MVP |
| Projects | primary | primary | primary | MVP |
| Mind-Map Canvas (ZUI) | more ("Expand Canvas") | primary | primary | MVP |
| Cartridge Dock (kanban) | more | more | primary | MVP |
| Timeline | more | more | primary | MVP |
| Relay River | more | more | more | MVP |
| Radial Radar | more | more | more | MVP |
| Transit Metro Map | more (strip form) | more | more | Post-MVP |
| Logic Breadboard | more (strip form) | more | more | Post-MVP |

The narrow strip forms of the Metro Map and the Breadboard come with 0124.5.

"Primary" is persistent navigation, at most five on a phone. "More" is the chip row and the command palette. A view an Android MVP lists as reachable is never hidden on any form factor. The two hidden designs are wide diagrams from RFC-0111 (Designs 2 and 5) that need a narrow-screen rework; they stay in the palette as an escape hatch until then.

### Generative layer

A manifest (RFC-0106 section "Wire Protocols", `$schema` a2ui-v1) names a `targetNodeId` and a tree of component types with props and slots. The renderer resolves every `type` against the TCR manifest (`manifest/tcr-manifest.json`, with a contract version per component, RFC-0123) and refuses a type or prop it does not know. It places the result as an inline block next to the target node: in the Living Document (`InlineToolBlock`), as a node card in the Outliner, or as a detail sheet from the Canvas. It never opens a free-floating panel. The same manifest renders on phone and desktop because the components are shared; density and elevation props adapt to the form factor.

Authority: a manifest is data from an agent, so it carries the taint of whatever produced it (RFC-0027), cannot contain actions beyond ids the host registered (`actionId`), and any action with an effect goes through the normal approval path. Egress to a remote model is shown in Gates, not in a manifest.

### Presence

The Red Pill and worker pills overlay every view (`MultiAgentPillOverlay`). On a phone the caret companion does not apply; the Assistant sheet (voice capture, ghost transcript, propose task) is the entry point. Document line reservations (`LineReservationLock`) and canvas wave signalling show which worker is acting where. Compartments (RFC-0106 section 4) separate working contexts and are desktop-first.

### Android handheld MVP

A phone user can: see the inbox of things that need them; open a task and its dependencies in the Outliner; approve or refuse effects, egress and split verification in Gates; read and dictate in the Living Document; switch project; expand the canvas on demand; and use the Assistant sheet. Foldables and tablets add the canvas, timeline and kanban as active views. Everything above is built and shared with desktop; what was missing was the placement (this RFC and `ViewModeCatalog`) and a device run.

Acceptance, not yet run on a device: install the app on a phone, create a project, add a task by voice, see it in the Inbox and the Outliner, approve one effect in Gates, expand the canvas, rotate and unfold and see the navigation change. The checklist is item 0124.1.

## Amendment 2026-11-18: finishing and wiring the views

The views are shells over fixed or in-memory data today. The decisions below were taken for the finishing work (plan `plans/ui-build-plan.md`, sections 2 and 3), each as the option that keeps most possibilities open and is easiest to revert. None changes an Agent API v1 shape in a breaking way, and none changes the schema.

| ID | Decision | Reason |
|---|---|---|
| U1 | Presenters and screens stay in aidos `agent/ui-common`. Only data-agnostic pieces (A2UI) go to aidos-tcr. Duplicated leaf components are not touched (owner decision 10, 2026-11-17). | TCR cannot depend on `agent/api`; moving later is a package move, no API change. |
| U2 | Desktop: views stay in the desktop engine's Workspace mode. No new "Aidos Desktop" app now. | `ui-common` is a library; a future Aidos Desktop app embeds `AidosUniversalApp` the same way. |
| U3 | Android: views stay in `agent/androidapp` (`fi.italeino.aidos`). The D49 merge into `fi.italeino.aidos.engine` is not part of this plan. | All new code is host-agnostic; a merge moves activities only. |
| U4 | Desktop workspace uses `AgentRuntimeAttach.connect()`: daemon when it answers, else DB-backed in-process. Demo seed removed. | Same rule the Shell grants screen already follows (RFC-0115, D60). |
| U5 | No magic project ids. A `WorkspaceBootstrap` finds the project by name and type, or creates it (`RuntimeManaged` slug). No new storage for the choice. | Purely client side; no schema change. |
| U6 | Live updates: additive `RuntimeEvent.IntentGraphChanged` **plus** a 5 s poll fallback (covers writes by other processes). | Additive API v1 change; drop either half later. |
| U7 | No batch "get graph" API. One `ProjectGraphStore` per project fetches once and feeds all views. | Add an API later only if measured slow. |
| U8 | Living Document text is stored in the selected node's `description` (via `updateNode`). With no node selected it edits a per-project GOAL node titled "Notes", created on first save. | No schema change; a later `documents` table can import descriptions. |
| U9 | Gates shows five sources: parked egress, parked tool approvals (new additive list API), pending capability requests, intent proposals, NEEDS_REVIEW verification. Diffs come from `runtimeClient.diff`. | Uses existing approval paths; nothing new decides on its own. |
| U10 | "Start" on a node: additive `IntentCommands.startNodeRun` using the existing `IntentRunCoordinator`. Auto-dispatch of `AUTO_WHEN_READY` nodes is not in this plan. | One new method with a default body; auto-dispatch is a runtime feature, separate. |
| U11 | Metro Map and Breadboard get a narrow "strip" form and are offered under "More" on phones (0124.5). | One line per view in `ViewModeCatalog`. |
| U12 | A2UI model, parser, validator, catalog and renderer live in aidos-tcr, package `dev.aidos.tcr.a2ui`. The catalog is an explicit allowlist; each entry names a `tcr-manifest.json` id and contract version. | Registry owns what it can render (RFC-0123); nothing else depends on it. |
| U13 | Manifests reach the UI as the persisted arguments of a built-in agent tool `present_ui` (the `tool_calls` row the executor already writes), read back through an additive `IntentCommands.listUiManifests()`. No new event, no schema change. The executor checks structure and size only; full validation happens at render time and fails closed. Reason: the executor emits no `RuntimeEvent`s in production today (even `ToolApprovalRequired` is never emitted), so an event-only path would not reach the UI. | No schema change; reads data that is already stored; a dedicated table can come later. |
| U14 | Manifest actions v1: `open_node`, `complete_node`, `start_node`, `reply_to_agent`. A manifest from an UNTRUSTED run asks for confirmation before any action. Effects still go through normal approvals. | Small fixed set; more ids are additive. |
| U15 | UI tests run as JVM Compose desktop tests in `ui-common` now. Android emulator tests (0124.10) stay open. | Shared code is the same; emulator CI can be added later. |
| U16 | RFC-0124 open questions: keep the selected view when the form factor changes (current behaviour); the palette stays the escape hatch and lists every view; Gates gets a badge count. | Current behaviour kept; badge is additive. |
| U17 | aidos-tcr stays JVM-only. `agent/androidapp` already consumes it on Android. If the Android CI build fails once `ui-common` uses TCR in `commonMain`, run contingency package UI-07 (add `androidTarget` to TCR). | Do nothing unless needed. |

**Wiring layer.** One `ProjectGraphStore` per open project feeds every view presenter. A `WorkspaceController` holds the selected node and the current view, and is the only place views call to mutate. Views read nothing else: not storage, not `RuntimeClient` directly.

## Security

- The generative layer renders only registered components with registered actions; unknown types fail closed.
- Gates stays one tap from anywhere on a phone because approvals are the human control point (RFC-0003).
- Compartment bridges and the blinded egress gate (RFC-0112, RFC-0113) are unchanged; this RFC adds no new egress path.

## MVP

Built: the twelve views with presenters tested on the JVM; the adaptive shell (`FormFactor`, `ResponsiveScaffold`, DeX state preservation); the front doors (Inbox, Projects, Gates); pills, caret, the Assistant sheet and voice capture; fixed TCR components and inline tool blocks; `ViewModeCatalog` as the single placement matrix with tests, used by the app root and the palette. The Android MVP above is satisfied in code; the device run is the acceptance test.

## Open Questions

1. Should Gates carry a badge count in the bottom bar? It needs a count from `ApprovalGatePresenter`.
   Answered 2026-11-18: yes. Gates gets a badge count (U16); it is additive and lands with 0124.6.
2. When the form factor changes mid-session and the selected view is no longer primary, keep it selected (current behaviour) or return to the Inbox?
   Answered 2026-11-18: keep the selected view, which is the current behaviour (U16).
3. Whether the command palette should hide views that a form factor does not offer, or stay the escape hatch.
   Answered 2026-11-18: the palette stays the escape hatch and lists every view (U16).

## Implementation Files

```yaml
files:
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/layout/ViewModeCatalog.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/schematic/BreadboardSchematicView.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/schematic/BreadboardSchematicPresenter.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/schematic/BreadboardLayout.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/schematic/BreadboardGlyphs.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/schematic/BreadboardComponentStrip.kt
  - agent/ui-common/src/jvmTest/kotlin/dev/aidos/ui/views/BreadboardViewTest.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/layout/ResponsiveScaffold.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/layout/WindowSizeClass.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/layout/AdaptiveLayoutState.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/AidosUniversalApp.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/dex/DexStatePreserver.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/workspace/WorkspaceBootstrap.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/workspace/ProjectGraphStore.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/workspace/WorkspaceController.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/inspector/NodeInspectorPresenter.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/inspector/NodeInspector.kt
  - agent/ui-common/src/jvmTest/kotlin/dev/aidos/ui/NodeInspectorTest.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/paradigms/livingdoc/InlineToolBlock.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/pills/MultiAgentPillOverlay.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/pills/PillDerivation.kt
  - agent/ui-common/src/jvmTest/kotlin/dev/aidos/ui/views/PresenceTest.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/assistant/AssistantSheetOverlay.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/frontdoors/gates/ApprovalGatesScreen.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/frontdoors/gates/GateCards.kt
  - agent/ui-common/src/jvmTest/kotlin/dev/aidos/ui/ViewModeCatalogTest.kt
  - agent/ui-common/src/jvmTest/kotlin/dev/aidos/ui/SpatialCanvasTest.kt
  - agent/ui-common/src/jvmTest/kotlin/dev/aidos/ui/WorkspaceCoreTest.kt
  - agent/ui-common/src/jvmTest/kotlin/dev/aidos/ui/GatesViewTest.kt
  - agent/ui-common/src/jvmTest/kotlin/dev/aidos/ui/PresentersOnStoreTest.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/paradigms/outliner/CausalOutlinerPresenter.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/paradigms/pipeline/RelayPipelinePresenter.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/paradigms/kanban/CartridgeDockPresenter.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/paradigms/kanban/DockRules.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/paradigms/kanban/CartridgeDockScreen.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/paradigms/kanban/TaskCartridgeView.kt
  - agent/ui-common/src/jvmTest/kotlin/dev/aidos/ui/views/KanbanViewTest.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/paradigms/timeline/TimelineGanttPresenter.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/schematic/TransitMetroPresenter.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/schematic/TransitMetroMapView.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/schematic/MetroNextStopStrip.kt
  - agent/ui-common/src/jvmTest/kotlin/dev/aidos/ui/views/MetroViewTest.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/schematic/BreadboardSchematicPresenter.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/radar/FocusRadarPresenter.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/radar/RadialRadarScreen.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/radar/MobileHorizonArcView.kt
  - agent/ui-common/src/jvmTest/kotlin/dev/aidos/ui/views/RadarViewTest.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/canvas/SpatialCanvasPresenter.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/canvas/InfiniteZuiCanvas.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/canvas/CanvasView.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/canvas/ForceDirectedLayout.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/nodes/ConstraintShieldNode.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/edges/BezierCausalEdge.kt
  - agent/ui-common/src/jvmTest/kotlin/dev/aidos/ui/views/CanvasViewTest.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/frontdoors/inbox/ActionInboxPresenter.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/frontdoors/projects/ProjectHubPresenter.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/frontdoors/projects/ProjectHubScreen.kt
  - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/frontdoors/projects/ProjectSlugs.kt
  - agent/ui-common/src/jvmTest/kotlin/dev/aidos/ui/views/ProjectsViewTest.kt
  - agent/androidapp/src/androidMain/kotlin/fi/italeino/aidos/MainActivity.kt
  - agent/androidapp/src/androidMain/kotlin/fi/italeino/aidos/LifeMainActivity.kt
  - agent/androidapp/src/androidMain/kotlin/fi/italeino/aidos/ProjectActivity.kt
  - agent/intent/src/commonMain/kotlin/dev/aidos/intent/IntentStartCheck.kt
  - agent/api/src/jvmTest/kotlin/dev/aidos/api/StartNodeRunTest.kt
  - agent/api/src/jvmTest/kotlin/dev/aidos/api/ListUiManifestsTest.kt
```

## Post-MVP Work

```yaml
post_mvp:
  - id: "0124.1"
    title: "Run the Android handheld acceptance on a phone, a foldable and DeX, and record the results"
    status: todo
    done_when: "the acceptance script in this RFC passes on a physical phone and an unfolded device"
  - id: "0124.2"
    title: "A2UI manifest parser and validator against the TCR manifest, failing closed on unknown types and props"
    status: todo
    phase: P1
    done_when: "a manifest with an unknown component type is rejected and a valid one yields a typed tree"
  - id: "0124.3"
    title: "TCR renderer for validated manifests, placed inline next to the target node in the Living Document and Outliner"
    status: todo
    phase: P1
    depends_on: ["0124.2"]
    done_when: "an agent-emitted manifest renders as an inline card on desktop and Android"
  - id: "0124.4"
    title: "Registered action ids for manifests, with effects routed through approval and taint carried from the producing agent"
    status: todo
    phase: P1
    depends_on: ["0124.3"]
  - id: "0124.5"
    title: "Rework the Metro Map and Breadboard for a narrow screen and offer them on a phone"
    status: done
    note: "Metro Next Stop strip (UI-40) and Breadboard component strip (UI-41) on HANDHELD_COMPACT; both offered under More on phones via ViewModeCatalog."
    files:
      - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/schematic/MetroNextStopStrip.kt
      - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/schematic/BreadboardSchematicView.kt
      - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/schematic/BreadboardSchematicPresenter.kt
      - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/schematic/BreadboardLayout.kt
      - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/schematic/BreadboardGlyphs.kt
      - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/spatial/schematic/BreadboardComponentStrip.kt
      - agent/ui-common/src/commonMain/kotlin/dev/aidos/ui/layout/ViewModeCatalog.kt
      - agent/ui-common/src/jvmTest/kotlin/dev/aidos/ui/ViewModeCatalogTest.kt
      - agent/ui-common/src/jvmTest/kotlin/dev/aidos/ui/views/BreadboardViewTest.kt
  - id: "0124.6"
    title: "Gates badge count in the navigation and a refusal path from the notification"
    status: todo
  - id: "0124.7"
    title: "Caret companion and travelling voice caret in the desktop Living Document (RFC-0106 phase 1)"
    status: todo
  - id: "0124.8"
    title: "Isolated compartments and the inter-compartment bridge (RFC-0106 phase 4)"
    status: todo
  - id: "0124.9"
    title: "In-process analytics and on-the-fly API synthesis (RFC-0106 phase 5)"
    status: todo
  - id: "0124.10"
    title: "Compose UI tests for the views on Android, run on the emulator in CI"
    status: todo
  - id: "0124.11"
    title: "ProjectGraphStore and WorkspaceController: one live, polled graph per project feeding all views"
    status: todo
    phase: P1
    done_when: "two views opened on the same project show the same node list, and a node added through one appears in the other within 5 seconds"
  - id: "0124.12"
    title: "Additive runtime APIs the views need: IntentGraphChanged event, listParkedEffects, startNodeRun, listUiManifests"
    status: todo
    phase: P1
    done_when: "each of the four APIs has a default body in the Agent API, a test, and is served over the socket"
  - id: "0124.13"
    title: "Hosts use real projects and the real runtime (no magic project ids, no in-memory desktop client)"
    status: todo
    phase: P1
    done_when: "a task created in the desktop and Android hosts is still there after the app restarts, and no host opens the project ids default-workspace or life"
  - id: "0124.14"
    title: "Node Inspector shared by all views: edit, link, start, complete, archive"
    status: todo
    phase: P1
    done_when: "a node can be edited, linked, started, completed and archived from the Inspector sheet opened by any view"
```
