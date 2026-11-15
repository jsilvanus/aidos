# RFC-0115: Desktop Background Services and the App as a UI over Them

Status: Draft (planning, 2026-11-15). Nothing here is built. Decisions D1 to D7 below are open and need the owner's answer before implementation starts.

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

Per user, started at login, restarted on failure, logs under `~/.aidos/logs`:

| OS | Mechanism | Notes |
|---|---|---|
| Windows | Scheduled Task "At log on" running the packaged launcher (no admin rights), or a Windows service via a wrapper if D2 says so | No Unix `chmod`: see D3 |
| macOS | `launchd` user agent (`~/Library/LaunchAgents`) | |
| Linux | `systemd --user` unit | `loginctl enable-linger` is optional and documented |

The app installs and removes these ("Run in background" setting), shows state, and can start or stop them. Packaging adds a headless launcher for each service (`aidos-engine`, existing `aidos-daemon`) and the module split in finding 6: `DesktopEngineHost` and its collaborators move to a non-Compose module `engine/desktopengine` used by both the Compose app (in-process) and a new headless `main`.

### Security

- The admin token is the existing `engine.json` token; it is not new authority. On Windows the file gets an owner-only ACL (`AclFileAttributeView`), and a failure to set it is an error, not a `runCatching` (finding 4).
- `/v1/admin/` is refused on the LAN listener and on any request arriving through the AIDIN remote ingress.
- The service process is the vault's only opener while it runs; the app asks it rather than opening the vault itself.
- Closing the window no longer closes the door: the LAN, remote-access and tunnel switches therefore stay as the user left them, and the app shows plainly that the Engine is running in the background.

## Phases

1. **Foundations.** Split `engine/desktopengine` out of the Compose app; fix the Windows token ACL; make the daemon read `~/.aidos/engine.json` for the Engine URL and token when the environment variables are not set.
2. **Agent attach.** App picks `SocketRuntimeClient` when the daemon answers, else the in-process client; single-writer rule; reconnect handling. Small, and removes the dual-database risk first.
3. **EngineControl.** Define the interface and `LocalEngineControl`, move the models onto it with no behaviour change (this is where the tests catch regressions), then add `/v1/admin/` and `HttpEngineControl` one group at a time.
4. **Headless Engine and installers.** `aidos-engine` launcher, the three per-user installers, the "Run in background" setting and state display.
5. **Hardening.** Process-level integration tests (start the services, attach, kill, reattach), upgrade and protocol-version mismatch handling, log rotation, documentation.

## Test plan

- Unit: `EngineControl` contract tests run against both implementations with the same assertions.
- Integration: spawn the headless Engine and daemon, attach, exercise each pane's operations, stop and restart a service under a live app, version mismatch.
- Security: admin routes absent on the LAN listener and the remote ingress; token ACL on Windows; an attached app opens no project database (assert by lock files and file handles).

## Decisions needed before implementation

**D1. Topology.** (a) UI, Engine service and Agent service as three parties (recommended; matches how the code is already split and how the daemon already reaches the Engine). (b) One combined service: needs merging two Gradle builds and the native runtime into the daemon for no functional gain.

**D2. What "service" means per OS.** (a) Per-user background process started at login (Scheduled Task, launchd agent, `systemd --user`) (recommended: no admin rights, the vault and keystore are per user). (b) A true system service per OS (runs before login, needs elevation to install, cannot reach the user's keystore without extra work).

**D3. Windows transport and token protection.** (a) Keep the Unix socket (Java supports it on Windows 10+) and add an owner-only ACL on the token file. (b) Loopback TCP with the token in an owner-only ACL file (works everywhere, easier to probe and debug, but a port any local process can try). Recommendation: (a), with (b) as the fallback if a Java or OS version lacks it.

**D4. Where the Engine's management API lives.** (a) New `/v1/admin/` routes on the Engine's loopback listener, same token (recommended). (b) A second socket for control. (c) Reuse the Agent wire protocol, which would mix two services' concerns.

**D5. Default when the app closes.** (a) If the services are installed, leave them running (the point of the feature); if not, behave as today (recommended). (b) Always ask. (c) Always stop.

**D6. Scope of the first release.** (a) Both stages in the order above, Agent attach first (recommended). (b) Agent attach only, leave the Engine in the app until later. (c) Engine only. Recommendation includes shipping phase 2 on its own, since it removes the two-writer risk even without the Engine work.

**D7. Refactor depth for the UI models.** (a) All panes onto `EngineControl` in one change, mechanical but wide (about a dozen models). (b) Pane by pane, with the app supporting a mix during the transition (recommended: smaller reviews, the in-process path keeps working throughout).

## Implementation Files

```yaml
files: []
```
