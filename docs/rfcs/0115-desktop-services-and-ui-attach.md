# RFC-0115: Desktop Background Services and the App as a UI over Them

Status: Accepted for implementation (2026-11-15), in progress: stage 1 (Agent-runtime attach) first, in small steps. D55 to D61 are all accepted by the owner (2026-11-15; `docs/decisions.md`). D56 supports both per-user and system installs; D59 requires the tray icon.

Decisions: D55 to D61, all accepted.

Related: RFC-0002 (runtime), RFC-0051 (LAN sharing), RFC-0055 (runtime socket and lock), RFC-0103 (Aidos Engine), RFC-0107/0108 (remote agent hosting, remote routes), RFC-0033 (Shell), RFC-0035 (secrets), `docs/TCR_UI_DEVELOPMENT_MANUAL.md`.

## Abstract

Today the desktop Engine app starts its own Engine in-process and stops it when the window closes, and the Agent daemon is a separate program that only the CLI talks to. A paired phone, a LAN client or an SSH tunnel can only reach this computer while the app is open. This RFC plans the change that makes the desktop reachable all the time: the Engine and the Agent runtime run as per-user background services, and the desktop app becomes a UI that attaches to them when they are running and falls back to running things in-process when they are not.

## What exists today (audited 2026-11-15)

| Piece | Where | What it is | How it is reached |
|---|---|---|---|
| Desktop Engine app | `engine/desktopapp` | Compose window that hosts the **Engine** in-process through `DesktopEngineHost`: model runtime, loopback HTTP API, AIDIN peers, LAN listener, remote routes, SSH tunnels, the Engine agent runtime behind `/v1/agent` | Calls `host.*` directly. Writes `~/.aidos/engine.json` (port, token) while running |
| Agent daemon | `agent/daemon` (`aidos-daemon`) | **Agent runtime**: projects, sessions, runs, executor, capabilities, shell, knowledge, retention | Unix socket `runtime.sock` plus `runtime.token`, 57 wire methods, client is `SocketRuntimeClient` (used by the CLI) |
| Engine CLI | `engine/cli` | Command line for the Engine | In-process |
| Daemon to Engine | `RuntimeCompositionRoot` | The daemon already treats the Engine as a **separate HTTP service**: `AIDOS_ENGINE_URL` / `AIDOS_ENGINE_TOKEN` (environment variables only; it does not read `engine.json`) | HTTP |

Findings that shape the plan:

1. **The Engine and the Agent runtime are already two services**, in two Gradle builds (`engine/` includes some `agent/` modules by path; `agent/` includes none of `engine/`). "Move the Engine into the daemon" would merge two builds and the native model runtime into one process for no gain. The target is three parties: UI, Engine service, Agent service.
2. **The UI is coupled to `DesktopEngineHost` by direct calls.** Every desktop model (`DesktopLanSharingModel`, `DesktopRemotePeersModel`, `DesktopTunnelsModel`, `DesktopAppsModel`, ...) calls the host or reads its files. Attaching to a separate process needs an interface in between.
3. **The Engine has no management API.** Its HTTP API serves inference and `/v1/agent`; LAN devices, peers, remote access, tunnels, app approvals and settings have none, and the Agent wire protocol is Agent-only.
4. **The runtime socket's token protection is Unix-only.** `RuntimeSocketServer` sets `0600` with `runCatching { setPosixFilePermissions }`, which silently does nothing on Windows, where the token file then has whatever ACL its directory gives it.
5. **Two writers on one set of SQLite files is the real hazard.** The daemon takes per-project locks while it runs; the app's in-process client (added for the Shell tab) takes none. Attached to a running daemon the app must open no project database itself.
6. **`DesktopEngineHost` has no Compose dependency** but lives in the Compose app module, so a headless Engine process cannot reuse it without a module split.

## Goals

1. The Engine (inference, LAN listener, AIDIN, tunnels, remote agent hosting) is reachable whenever the user is logged in, with the app closed.
2. The Agent runtime runs the same way, so runs, scheduled work and knowledge indexing survive the window closing.
3. The app shows the same panes either way: attached to services, or running them in-process when they are not installed or not running.
4. No loss of the security properties already built: per-app approvals, per-device LAN grants, the remote-access switch, host-key pinning, the vault.

## Non-goals

- Android. Its Engine is already a foreground service and its UI is separate.
- A system-wide (all users) service. Data, the vault and OS keystore access are per user.
- Remote administration. The management API is local only; nothing about it is exposed on the LAN listener or over a peer link.
- Merging the Engine and Agent builds.

## Design

### Topology

```
 Desktop app (UI)  ---- EngineControl ---->  Engine service   (HTTP, loopback, local admin token)
        |                                         ^
        |                                         |  HTTP (AIDOS_ENGINE_URL/TOKEN, now read from engine.json)
        +------- RuntimeClient ----------->  Agent service    (socket, token)
        
 fallback: the app runs either service in-process (today's behaviour) when it is not running
```

### The two control interfaces

- **`RuntimeClient`** (Agent side) already exists with an in-process (`RealRuntimeClient`) and a socket (`SocketRuntimeClient`) implementation. The app picks one at startup and on reconnect.
- **`EngineControl`** (new, in `dev.aidos.engine.control`, `commonMain`/`jvmAndAndroidMain`): the operations the panes need today, grouped as `status`, `models`, `apps` (approvals, agent grant, locality), `lan` (enable, devices, pairing), `peers` (hosting, preferred, remote access, hints, tool allowlists, tool approvals), `tunnels`, `settings`. Two implementations: `LocalEngineControl` over `DesktopEngineHost`, and `HttpEngineControl` over the Engine's loopback API. Each model takes an `EngineControl` instead of the host; state that is a `StateFlow` today becomes a server-sent event stream or a short poll behind the interface.

### Engine management API

New routes under `/v1/admin/` on the Engine's **loopback** listener only (never installed on the LAN listener, never reachable by a Binder or LAN token): same bearer token as `engine.json`, so the trust boundary is unchanged ("whoever can read the user's `~/.aidos/engine.json` is the user"). They are thin wrappers over what `DesktopEngineHost` already exposes, one route group per `EngineControl` group, with a version header so an old app and a new service can tell.

### Attach, detach and fallback

1. App start: read `~/.aidos/engine.json` and the runtime socket and token paths; probe each with a short timeout and a protocol-version check.
2. Reachable: use the remote implementation and open **no** project database in the app process (finding 5).
3. Not reachable: start in-process as today, unless "Run in background" is installed, in which case offer to start the service.
4. A service that appears later is offered, not silently adopted (an in-process Engine already holds the port and the databases).
5. Closing the app stops only what the app started itself.

### Service installation

Two modes (D56). **Per user** (default, no administrator rights): started at login, restarted on failure, logs under `~/.aidos/logs`. **System** (when the installer is run as administrator and the user chooses it): started at boot as a named user account, never SYSTEM or root, since the data and vault are per user; before that user logs in there is no OS keystore session, so the vault runs in passphrase mode and the services start with it locked until unlocked. The tray (below) shows "locked" and asks for the passphrase. Per-user mechanisms:

| OS | Mechanism | Notes |
|---|---|---|
| Windows | Scheduled Task "At log on" running the packaged launcher (no admin rights), or a Windows service via a wrapper if D2 says so | No Unix `chmod`: see D597 |
| macOS | `launchd` user agent (`~/Library/LaunchAgents`), or a launchd daemon with `UserName` (system mode) | |
| Linux | `systemd --user` unit (`loginctl enable-linger` optional), or a system unit with `User=` (system mode) | |

The app installs and removes these ("Run in background" setting), shows state, and can start or stop them. Packaging adds a headless launcher for each service (`aidos-engine`, existing `aidos-daemon`) and the module split in finding 6: `DesktopEngineHost` and its collaborators move to a non-Compose module `engine/desktopengine` used by both the Compose app (in-process) and a new headless `main`.

### Tray icon, notifications and quick approvals (D59)

Closing the window leaves the services running, so something must still show that they are, and let the user act on them. A separate lightweight **tray process** (`aidos-tray`, started at login, cheap enough to stay resident; the Compose window is launched on demand) provides:

- **Context menu:** Open Aidos (starts or focuses the window); for each service (Engine, Agent runtime) its state with Start, Stop and Restart; Close daemon (stops the Agent runtime and, if the user chooses, the Engine); Run on login on or off; Quit tray. State is colour-coded on the icon (running, needs attention, stopped, vault locked).
- **Quick approvals:** every service publishes a *pending approvals* feed that the tray subscribes to: new-app approval requests (`AppApprovalStatus.PENDING`), tool-call approvals on tainted remote runs (`ToolApprovalQueue`, which refuses after a minute), capability and effect approvals parked by the Agent runtime, and LAN device pairing requests. A new item raises an OS toast; clicking the toast or the tray icon opens a small popup listing the pending items with Approve and Deny. Toasts themselves are informational (cross-platform toasts cannot carry reliable buttons), and the decision always happens in the popup, so the same path works on all three systems.
- **What it must not do:** approve anything without a click, cache an approval, or show content (arguments, file text) in a toast; a toast names who is asking and what kind of thing, nothing more. Approvals keep the semantics of their source (per call, timeout is refusal).
- **Needs from the services:** a pending-approvals stream and decide calls on both control interfaces (`EngineControl` and the Agent socket), a state and start/stop/restart surface, and the vault-locked state.

### Security

- The admin token is the existing `engine.json` token; it is not new authority. On Windows the file gets an owner-only ACL (`AclFileAttributeView`), and a failure to set it is an error, not a `runCatching` (finding 4).
- `/v1/admin/` is refused on the LAN listener and on any request arriving through the AIDIN remote ingress.
- The service process is the vault's only opener while it runs; the app asks it rather than opening the vault itself.
- Closing the window no longer closes the door: the LAN, remote-access and tunnel switches therefore stay as the user left them, and the app shows plainly that the Engine is running in the background.

## Phases

0. **Tray and approvals feed.** Needs the services' pending-approvals streams first, so it lands with phases 2 and 3; the first useful cut is state, start/stop and the approvals popup for what the Engine already queues.
1. **Foundations.** Split `engine/desktopengine` out of the Compose app; fix the Windows token ACL; make the daemon read `~/.aidos/engine.json` for the Engine URL and token when the environment variables are not set.
2. **Agent attach.** App picks `SocketRuntimeClient` when the daemon answers, else the in-process client; single-writer rule; reconnect handling. Small, and removes the dual-database risk first.
3. **EngineControl.** Define the interface and `LocalEngineControl`, move the models onto it with no behaviour change (this is where the tests catch regressions), then add `/v1/admin/` and `HttpEngineControl` one group at a time.
4. **Headless Engine and installers.** `aidos-engine` launcher, the three per-user installers, the "Run in background" setting and state display.
5. **Hardening.** Process-level integration tests (start the services, attach, kill, reattach), upgrade and protocol-version mismatch handling, log rotation, documentation.

## Test plan

- Unit: `EngineControl` contract tests run against both implementations with the same assertions.
- Integration: spawn the headless Engine and daemon, attach, exercise each pane's operations, stop and restart a service under a live app, version mismatch.
- Security: admin routes absent on the LAN listener and the remote ingress; token ACL on Windows; an attached app opens no project database (assert by lock files and file handles).

## Decisions (all accepted 2026-11-15)

**D55 (was D1, settled: three parties).** (a) UI, Engine service and Agent service as three parties (recommended; matches how the code is already split and how the daemon already reaches the Engine). (b) One combined service: needs merging two Gradle builds and the native runtime into the daemon for no functional gain.

**D56 (was D2, amended: both modes).** Settled direction: per user by default, system service when installed as administrator (see Service installation). Original question: what "service" means per OS. (a) Per-user background process started at login (Scheduled Task, launchd agent, `systemd --user`) (recommended: no admin rights, the vault and keystore are per user). (b) A true system service per OS (runs before login, needs elevation to install, cannot reach the user's keystore without extra work).

**D57 (was D3). Windows transport and token protection.** (a) Keep the Unix socket (Java supports it on Windows 10+) and add an owner-only ACL on the token file. (b) Loopback TCP with the token in an owner-only ACL file (works everywhere, easier to probe and debug, but a port any local process can try). Recommendation: (a), with (b) as the fallback if a Java or OS version lacks it.

**D58 (was D4, accepted; may be revisited). Where the Engine's management API lives.** (a) New `/v1/admin/` routes on the Engine's loopback listener, same token (recommended). (b) A second socket for control. (c) Reuse the Agent wire protocol, which would mix two services' concerns.

**D59 (was D5, settled: keep running, with a tray icon). Default when the app closes.** (a) If the services are installed, leave them running (the point of the feature); if not, behave as today (recommended). (b) Always ask. (c) Always stop.

**D60 (was D6). Scope of the first release.** (a) Both stages in the order above, Agent attach first (recommended). (b) Agent attach only, leave the Engine in the app until later. (c) Engine only. Recommendation includes shipping phase 2 on its own, since it removes the two-writer risk even without the Engine work.

**D61 (was D7). Refactor depth for the UI models.** (a) All panes onto `EngineControl` in one change, mechanical but wide (about a dozen models). (b) Pane by pane, with the app supporting a mix during the transition (recommended: smaller reviews, the in-process path keeps working throughout).

## Platform notes

The JVM parts (daemon, CLI, Engine core, headless services) run on Linux and macOS as well as Windows; the agent and engine suites already run on `ubuntu-latest`. Risks to carry: the Compose window and tray have only been exercised on Windows; the Java tray needs the AppIndicator extension on GNOME and a status-notifier host on some desktops; on Linux the vault uses libsecret when present and the passphrase mode otherwise.

## Progress

- **Phase 1, done (2026-11-15):** owner-only token files (`OwnerOnlyFiles`: POSIX `rw-------`, an owner-only ACL on Windows, failure is an error and the file is removed; used for the runtime token and `engine.json`); the daemon finds the Engine through `engine.json` (`EngineDiscovery`, environment variables still win, a dead pid is ignored).
- **Phase 2, first step done:** `AgentRuntimeAttach` in the desktop app attaches to a running Agent daemon through `SocketRuntimeClient` and falls back to the in-process runtime; when a daemon answers the in-process runtime (and so any project database in the app) is never created. The Shell tab uses it and says which runtime it is on. Not yet: the other panes, reconnect while the app is open, an integration test that spawns a real daemon.
- **Phase 3, first group done:** `LanControl` (the LAN sharing group of `EngineControl`) with `LocalLanControl` (in-process) and `HttpLanControl` (loopback admin API); `/v1/admin/lan` served on the loopback listener only, to the Engine's own local client only (an approved app's token and a wrong token are refused; absent on the LAN listener); the LAN pane's presenter knows only `LanControl`, and `EngineAttach` picks the HTTP implementation when another live Engine process owns `engine.json`. Tested end to end through the real desktop host.
- **Approvals feed and tray, first cut done:** `ApprovalsControl` (pending items: apps asking to use the Engine, effectful tool calls on tainted remote runs; ids `app:`/`tool:`), `/v1/admin/approvals` and `POST /v1/admin/shutdown` (headless Engine only), both loopback and admin only. The tray (`--tray`, `tray/` package) polls the feed, toasts each new item once with who is asking and never content, opens an Approve/Deny popup on click, and has Open, Start, Stop and Restart Engine; the watcher and actions are unit tested, the AWT shell is not (needs a desktop).
- **Installers, first cut done:** `--install-service <engine|tray>` and `--uninstall-service` (`install/` package). Per user: systemd `--user` unit, launchd LaunchAgent, Scheduled Task at logon. Machine-wide (`--system --run-as NAME`): systemd system unit with `User=`, LaunchDaemon with `UserName`, Scheduled Task at startup as the named user (schtasks asks for the password); root and SYSTEM are refused, a non-elevated shell gets a hint and nothing is written, a failed install removes its files. The tray is per-user only. Generated definitions and the installer are unit tested for all three OS families; applying them to a real service manager is not yet exercised.
- **Agent daemon in the tray and installers, done:** the tray polls the Engine and the Agent daemon (capability requests, ids `cap:`) through one composite feed; one source failing does not hide the other. `--install-service agent --exec <command>` installs the daemon the same way as the Engine.
- **Run in background, done:** a Background tab (aidos-tcr `BackgroundPane`, `DesktopBackgroundModel`) with one switch that registers or removes the per-user Engine service and tray icon through the installer, showing each service's state and any failure. Machine-wide installs stay on the command line.
- **Tunnels group, done:** `TunnelsControl` with `LocalTunnelsControl` and `HttpTunnelsControl`, `/v1/admin/tunnels` (list, create, probe, confirm, start, stop, rotate-key, delete; loopback and admin only), the Tunnels presenter knows only the interface and `EngineAttach.tunnelsControl` picks the implementation. The same create-probe-confirm-start walk is tested in-process and through the admin API.
- **Peers and remote access group, done:** `PeersControl` (status, hosting switch, preferred hosts, remote access, hints, tool allowlists) with Local and Http implementations and `/v1/admin/peers`; tool approvals are answered through the approvals feed (`tool:<id>`). The Peers presenter knows only the two interfaces and runs its actions one at a time. The switches and a tool prompt are tested in-process and through the admin API against a real paired node.
- **Connected apps group, done:** `AppsControl` (list, approve, deny, revoke, undo deny, agent use, locality limit; an unknown app is `false`, a bad limit is an error) with Local and Http implementations and `/v1/admin/apps`; the Connected Apps presenter polls it and keeps the last list through a failed poll. Tested through the admin API against the real store. The debug handshake button stays on the in-process host.
- **Status group and no second Engine, done:** `StatusControl` (running, state name with port, resident models, linked peers) with Local and Http implementations and `GET /v1/admin/status`; the Home presenter composes it with `AppsControl`, and shows OFF when the service stops answering. The app no longer starts its own Engine, nor lets the toggle start one, while a live Engine service owns `engine.json`.
- **Models group, list pane done:** `ModelsControl` (installed, Hub search with filters, one-click suggested installs with progress, delete) with Local and Http implementations and `/v1/admin/models`; installs are owned by the Engine and carry on in a service after the window closes, the presenter reads their progress back by polling. Tested through the admin API (including slashes in model ids).
- **Model detail group, done:** `ModelDetailControl` (facts and fit table, install state, license, download with progress, load and unload, run settings, embedding and self tests) with Local and Http implementations and `/v1/admin/model-detail/<action>?model=<id>`; the presenter was rewritten onto it, downloads and loaded models belong to the Engine. Written test-first against the echo fixture, in-process and through the admin API, plus the presenter over the admin API (load, chat self test, settings saved in the Engine's preferences, unload). One behaviour note: the offline fallback that asked for acceptance of a license name when Hugging Face could not be reached is gone, because it depended on presenter state; with no reachable license text there is nothing to accept.
- **Agents group, done:** `AgentsControl` (overview of stats and runs, routing, run launch config, supervisory cancel) in `agenthost` with Local and Http implementations and `/v1/admin/agents` (mounted through a new `adminExtraRoutes` hook, so enginehost does not depend on agenthost); the Agents presenter was moved onto it and pushes the user's routing choices to the Engine. Tested in-process and through the admin API with a real run on the echo fixture.
- **Every pane that has a real Engine behind it is now attached** (Status, Models, model detail, Agents, Apps, Sharing, Peers, Tunnels, Background). The Tools pane shows sample data and uses no host, so there is nothing to attach until it is wired to a real MCP broker.
- **Cross-process end to end, done:** `HeadlessEngineAttachTest` starts a real headless Engine as its own JVM, attaches the window's controls (status, apps, vault, agents, LAN) through `engine.json` and the admin API, checks the window would not start a second Engine, and has the tray's stop make the process exit.
- **Next:** trying the tray and installers on a real Windows task / Linux unit / macOS agent, and the Agent daemon's own vault mode.

## Implementation Files

```yaml
files:
  - agent/api/src/jvmMain/kotlin/dev/aidos/api/socket/OwnerOnlyFiles.kt
  - agent/api/src/jvmTest/kotlin/dev/aidos/api/socket/OwnerOnlyFilesTest.kt
  - agent/daemon/src/jvmMain/kotlin/dev/aidos/daemon/RuntimeSocketServer.kt
  - agent/daemon/src/jvmMain/kotlin/dev/aidos/daemon/EngineDiscovery.kt
  - agent/daemon/src/jvmMain/kotlin/dev/aidos/daemon/RuntimeCompositionRoot.kt
  - agent/daemon/src/jvmTest/kotlin/dev/aidos/daemon/EngineDiscoveryTest.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/AgentRuntimeAttach.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/AgentRuntimeAttachTest.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopEngineHost.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopShellGrantsModel.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/control/LanControl.kt
  - engine/enginehost/src/jvmMain/kotlin/fi/italeino/aidos/engine/control/HttpLanControl.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/http/AdminRoutes.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/http/EngineHttpServer.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalLanControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/EngineAttach.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopLanSharingModel.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopLanEndToEndTest.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/control/ApprovalsControl.kt
  - engine/enginehost/src/jvmMain/kotlin/fi/italeino/aidos/engine/control/HttpApprovalsControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalApprovalsControl.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/ApprovalsFeedTest.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/tray/TrayModel.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/tray/TrayActions.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/tray/TrayApp.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/tray/TrayModelTest.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/install/ServiceDefinitions.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/install/ServiceInstaller.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/install/InstallCli.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/install/ServiceInstallTest.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/tray/AgentApprovals.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopBackgroundModel.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopBackgroundModelTest.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/control/TunnelsControl.kt
  - engine/enginehost/src/jvmMain/kotlin/fi/italeino/aidos/engine/control/HttpTunnelsControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalTunnelsControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopTunnelsModel.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/control/PeersControl.kt
  - engine/enginehost/src/jvmMain/kotlin/fi/italeino/aidos/engine/control/HttpPeersControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalPeersControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopRemotePeersModel.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/control/AppsControl.kt
  - engine/enginehost/src/jvmMain/kotlin/fi/italeino/aidos/engine/control/HttpAppsControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalAppsControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/StatusAndAppsModels.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/AppsControlTest.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/control/StatusControl.kt
  - engine/enginehost/src/jvmMain/kotlin/fi/italeino/aidos/engine/control/HttpStatusControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalStatusControl.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/StatusControlTest.kt
  - agent/vault/src/jvmMain/kotlin/dev/aidos/vault/KeyProviders.kt
  - agent/vault/src/jvmMain/kotlin/dev/aidos/vault/DesktopVault.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/control/VaultControl.kt
  - engine/enginehost/src/jvmMain/kotlin/fi/italeino/aidos/engine/control/HttpVaultControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/VaultGate.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/VaultGateTest.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/control/ModelsControl.kt
  - engine/enginehost/src/jvmMain/kotlin/fi/italeino/aidos/engine/control/HttpModelsControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalModelsControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopModelsModel.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/ModelsControlTest.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/control/ModelDetailControl.kt
  - engine/enginehost/src/jvmMain/kotlin/fi/italeino/aidos/engine/control/HttpModelDetailControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/LocalModelDetailControl.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopModelDetailModel.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/ModelDetailControlTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/DesktopModelDetailModelTest.kt
  - engine/agenthost/src/main/kotlin/fi/italeino/aidos/engine/agent/AgentsControl.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/AgentsControlTest.kt
  - engine/desktopapp/src/test/kotlin/fi/italeino/aidos/engine/desktop/HeadlessEngineAttachTest.kt
```
