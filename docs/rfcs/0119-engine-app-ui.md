# RFC-0119: Engine App UI and Desktop Control

Status: Accepted 2026-11-15, partially implemented. Detail: (2026-11-15, written from the code and RFC-0115/D58/D61). Partially implemented on desktop; the Android screens are listed in RFC-0118.

Related: RFC-0115 (desktop services; owns the service split, tray and install), RFC-0103 (Engine), RFC-0051 LAN and RFC-0105/0107/0108 (the features the panes control), RFC-0106 and RFC-0123 (generative UI and the component registry), D58, D59, D61.

## Abstract

The Aidos Engine app is the user's control surface for the Engine: which models are installed and running, which apps may use them, which peers and tunnels are paired, which MCP servers and agents are allowed, and what the Engine is doing. This RFC defines that app's panes and the `EngineControl` seam through which every pane reaches the Engine, so the same UI can run against an in-process Engine today and a background service tomorrow (RFC-0115).

## Motivation

The panes were built one feature at a time and each RFC mentions only its own. There is no statement of what the app as a whole shows, which panes are shared between desktop and Android, or how a pane stays correct when the Engine is a separate process. D61 decided the seam (an `EngineControl` interface with in-process and HTTP implementations, pane by pane) but nothing records the pane list or its rules.

## Goals

- One pane list, shared between desktop and Android where the feature exists on both.
- Panes depend on a control interface, never on `DesktopEngineHost` directly, so attach and fallback (RFC-0115) change nothing in the UI.
- Security-relevant decisions (approve an app, allow agent runs, allow a peer, adopt an MCP tool) are explicit one-step user actions that name what is being granted.
- Presenters are JVM-testable without Compose.

## Non-goals

- The generative, spatial UI (RFC-0106) and the intent-graph views (RFC-0111/0112); this app is a settings-and-status surface.
- Service installation and the tray (RFC-0115).

## Design

### Panes

| Pane | Controls |
|---|---|
| Status | Engine state, loaded model, memory, request counters, version |
| Models | installed models, Hugging Face browser, downloads with licence acceptance, run settings (context, mmap), load and unload |
| Model detail | per-model verdict (fit, KV-cache estimate), cookbook entries, storage |
| Connected Apps | pending, approved and denied apps, per-app locality limit, "Allow agent runs" |
| Agents | live agent runs and tool approvals (RFC-0120) |
| MCP Servers | registered servers, adoption of their tools, vault-backed secrets (RFC-0031) |
| Groups and Peers | pairing by code or QR, group membership, split hosting (RFC-0105) |
| Remote | per-peer remote-access switch, SSH tunnels, host keys, setup key (RFC-0108) |
| Sharing | LAN listener on/off, certificate fingerprint, per-device grants (RFC-0051 LAN) |
| Settings | Engine-wide locality limit, run defaults, background and tray preferences |

### The control seam

Each pane has a presenter (`Desktop*Model`) that talks to a `*Control` interface (`LocalModelsControl`, `LocalAppsControl`, `LocalPeersControl`, `LocalTunnelsControl`, `LocalLanControl`, `LocalStatusControl`, `LocalApprovalsControl`, `LocalModelDetailControl`). The in-process implementations call the host; the HTTP implementations call the loopback-only `/v1/admin/` routes (D58), which are never installed on the LAN listener or reachable through remote ingress. `EngineAttach` chooses attach or in-process at start and a mix is supported during the transition, so the in-process path keeps working (D61).

### Rules

1. A pane never shows a secret. Vault values appear as set/unset; the SSH setup page shows only the public `authorized_keys` line.
2. Granting is separate from pairing: pairing a peer does not allow remote access or agent runs; each is its own switch, default off.
3. Revoking takes effect on the next request (401), and the pane says so.
4. A pane that lists work in progress (downloads, agent runs) reconciles from the authoritative state on open and after reconnect; notifications are advisory.

## Security

- `/v1/admin/` uses the loopback listener and the `engine.json` token. A page cannot cause an admin call to be reachable from the network.
- Approval prompts show the requesting package and the specific grant, never a generic "allow".

### Where the panes live

The Engine panes are implemented in the Trusted Component Registry (`aidos-tcr`, packages `dev.aidos.tcr.engine`, `.agent`, `.mcp`; RFC-0123) and the desktop app imports them (for example `LanSharingPane`). `engine/engineui` still holds its own older copies of several panes (Status, Connected Apps, Agents, MCP Servers), which breaks the single-implementation rule and should be removed once Android consumes the registry versions. Presenters and the control seam stay in Aidos.

## MVP

Built (desktop): Status, Models, Model detail, Connected Apps, Agents, MCP Servers, Groups, Remote and Tunnels, Sharing, with presenters tested on the JVM; the control seam for the listed controls with in-process implementations and the HTTP admin path for status; the tray (RFC-0115). Android has its own Compose screens (RFC-0118). The Compose panes themselves are not UI-tested.

## Open Questions

- Which panes move to HTTP controls first now that Agent-runtime attach is in progress (RFC-0115 stage 1)?
- Should Android and desktop share the pane Composables in `engineui` fully, or keep platform-specific shells?

## Implementation Files

```yaml
files:
  - engine/engineui/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/theme/AidosEngineTheme.kt
  - engine/engineui/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/ui/AgentsPane.kt
  - engine/engineui/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/ui/Components.kt
  - engine/engineui/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/ui/ConnectedAppsPane.kt
  - engine/engineui/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/ui/GroupsPane.kt
  - engine/engineui/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/ui/McpServersPane.kt
  - engine/engineui/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/ui/ModelDetailContent.kt
  - engine/engineui/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/ui/ModelsPanes.kt
  - engine/engineui/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/ui/QrCode.kt
  - engine/engineui/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/ui/SampleAgentRuns.kt
  - engine/engineui/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/ui/SettingsContent.kt
  - engine/engineui/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/ui/StatusPane.kt
  - engine/engineui/src/jvmTest/kotlin/fi/italeino/aidos/engine/ui/QrCodeTest.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/AgentRuntimeAttach.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopApp.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopBackgroundModel.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopEngineHost.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopEnvironment.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopGroups.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopLanSharing.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopLanSharingModel.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopMcpModel.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopModelDetailModel.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopModelsModel.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopPreferences.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopRemoteAgent.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopRemotePeersModel.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopRuntimeClient.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopShellGrantsModel.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopTunnels.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopTunnelsModel.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/EngineAttach.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/EngineMcpTools.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/FileAppApprovalStore.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalApprovalsControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalAppsControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalLanControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalModelDetailControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalModelsControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalPeersControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalStatusControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalTunnelsControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/Main.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/McpManager.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/StatusAndAppsModels.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/VaultGate.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/install/InstallCli.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/install/ServiceDefinitions.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/install/ServiceInstaller.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/tray/AgentApprovals.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/tray/TrayActions.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/tray/TrayApp.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/tray/TrayCheck.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/tray/TrayModel.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/AgentRuntimeAttachTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/AgentsControlTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/ApprovalsFeedTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/AppsControlTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopBackgroundModelTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopEngineHostTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopLanEndToEndTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopLanSharingTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopModelDetailModelTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopPreferencesRoutingTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopPreferencesRunSettingsTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopRemoteAgentTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopRemotePeersModelTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopShellGrantsModelTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopTunnelsTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/EngineMcpToolsTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/FileAppApprovalStoreTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/HeadlessEngineAttachTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/LoadedModelTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/McpManagerTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/ModelDetailControlTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/ModelsControlTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/StatusControlTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/VaultGateTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/install/ServiceInstallTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/tray/TrayModelTest.kt
```

## Post-MVP Work

Checklist for work beyond the MVP. Each item has a stable id; set `status` to `partial` or `done` and list the implementing `files` (checked by `scripts/rfc/postmvp.py`).

```yaml
post_mvp:
  - id: "0119.1"
    title: "Move every pane to an HTTP-backed EngineControl (RFC-0115 stage 2)"
    status: todo
    phase: P1
  - id: "0119.2"
    title: "Compose UI tests for the panes"
    status: todo
    phase: P2
  - id: "0119.3"
    title: "Shared pane Composables across desktop and Android"
    status: todo
    phase: P2
  - id: "0119.4"
    title: "Settings pane: background services, tray and install preferences"
    status: todo
    phase: P1
  - id: "0119.5"
    title: "Accessibility pass (keyboard, screen reader) on all panes"
    status: todo
    phase: P3
  - id: "0119.6"
    title: "Remove the engineui pane copies that duplicate dev.aidos.tcr.engine, agent and mcp"
    status: todo
    phase: P1
```
