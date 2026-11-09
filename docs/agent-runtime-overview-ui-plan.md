# Plan: Agent Runtime Overview & Control UI with Placement and Egress Tracking

**Authority:** Decision D48, D49, RFC-0103, RFC-0104, RFC-0042  
**Scope:** `aidos-tcr`, `aidos:engine:agenthost`, `aidos:engine:engineui`, `aidos:engine:desktopapp`, `aidos:engine:androidapp`  
**Date:** November 2026  

---

## 1. Objectives

1. **Aidos Engine Agent Runtime UI**: Surface runtime control for the unified Engine/Agent host across Android and Desktop.
2. **Telemetry & Supervisory Inspection**:
   - Inspect all agents across all apps and filter by app (owner token subject) or execution status.
   - Real-time concurrency gauges and runtime metrics.
   - Cooperative cancellation and detailed run inspection.
3. **Model Placement Visibility (RFC-0104)**:
   - Surface model name and derived placement: 🟢 `LOCAL` (on-device), 🔵 `CLUSTER` (trusted AIDIN peers), 🟣 `REMOTE` (external API providers).
   - Show enforced Locality ceiling (`LOCAL_ONLY`, `TRUSTED_CLUSTER`, `ANY`).
4. **Outside Connections & Egress Visibility (RFC-0042)**:
   - Capture and display outside network connections made during agent execution.
   - Show destination hosts, HTTP methods, bytes transferred, and SHA-256 payload audit hashes.
   - Highlight zero-egress runs with an "Air-Gapped / Zero Egress" verified badge.
5. **Decoupled Architecture**:
   - Authoritative shared UI components reside in `dev.aidos.tcr.agent` within `aidos-tcr`.
   - Shared Compose Multiplatform pane resides in `:engineui`.
   - Desktop and Android apps consume the shared pane directly.

---

## 2. Architecture & Seams

### 2.1 aidos-tcr (`dev.aidos.tcr.agent`)
- `AgentModels.kt`: Data contracts for agent run UI representation (`AgentRunUiModel`, `AgentPlacement`, `AgentEgressRecordUi`, `AgentEgressSummaryUi`, `AgentRunStatus`).
- `AgentPlacementBadge.kt`: Visual chip with canonical `TcrColors` (Emerald for Local, Electric Blue for Cluster, Violet for Remote).
- `AgentEgressSummary.kt`: Air-gap verification badge and expandable outside network destination audit list.
- `AgentRunCard.kt`: Atomic card component for a single agent run.
- `AgentStatsBanner.kt`: Top-level runtime metrics (capacity utilization, active runs, runs per app, egress tally).

### 2.2 engine:agenthost (`AgentRuntime.kt`)
- Extend `Run` state tracking to record:
  - `modelId: String?`
  - `locality: Locality?`
  - `placement: Placement?`
  - `egressRecords: List<EgressRecord>`
- Add supervisory methods:
  - `fun listAll(): List<AgentRunDescriptor>`
  - `fun stats(): AgentRuntimeOverviewStats`
  - `fun cancelAny(runId: String): Boolean`

### 2.3 engine:engineui (`AgentOverviewPane.kt`)
- Compose Multiplatform pane for both Android and Desktop.
- Filterable by client app (`owner`) and execution status (`All`, `Active`, `Awaiting Tools`, `Completed`, `Failed`).
- Real-time reactive updates from `AgentRuntime`.
- Detailed inspection sheet/dialog.

### 2.4 Application Wiring
- **Desktop (`DesktopApp.kt`)**: Add Agents tab / view in main layout.
- **Android (`NavHost.kt`, `Routes.kt`)**: Add `EngineRoute.Agents` route and navigation link.
