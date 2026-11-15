# Trying the desktop services, tray and installers on a real machine

What this checks (RFC-0115): the Engine and the Agent daemon run as background services, the window attaches to them instead of starting its own Engine, and the tray icon shows approvals and controls the services. Everything below is covered by tests that run without a desktop; what those tests cannot do is show a tray icon, register a real Scheduled Task, systemd unit or launchd agent, or sign in as a second user. That is what this checklist is for. Nothing here has been run on a real desktop yet.

Start with the read-only commands. None of them changes anything.

## 0. Before you start

```
cd engine
gradlew.bat :desktopapp:run --args="--tray-check"        # Linux/macOS: ./gradlew :desktopapp:run --args=--tray-check
```

It prints whether this session can show a tray icon, which services answer, whether a vault is waiting for its passphrase, and how many approvals are waiting. If it says the tray is not supported, stop: on Linux a desktop needs a status notifier or AppIndicator host (GNOME needs the AppIndicator extension).

Every install command takes `--dry-run`, which prints the files it would write and the commands it would run, and does neither:

```
gradlew.bat :desktopapp:run --args="--install-service engine --dry-run"
gradlew.bat :desktopapp:run --args="--install-service tray --dry-run"
```

Read the output once. On Windows this is `schtasks /Create ... /SC ONLOGON`; on Linux a unit in `~/.config/systemd/user/`; on macOS a plist in `~/Library/LaunchAgents/`.

## 1. A headless Engine, by hand (no installer yet)

```
gradlew.bat :desktopapp:run --args="--daemon"
```

`~/.aidos/engine.json` should say `READY` with the running process's pid. In a second terminal start the window the normal way: it must show **no second Engine** (the Status tab says the Engine is running on the port in `engine.json`), and Sharing, Peers, Tunnels, Models, Agents and the model detail screen all work. Close the window: the Engine keeps running. Stop it with Ctrl+C in the first terminal, or from the tray (step 3).

## 2. The tray, by hand

```
gradlew.bat :desktopapp:run --args="--tray"
```

An "A" icon should appear in the notification area. Check, in order:

1. Right-click: Open Aidos, Waiting for approval, Start Engine, Stop Engine, Restart Engine, Unlock vault, Quit tray. Start Engine launches a headless Engine (the one from step 1 if still running is left alone); Stop Engine ends it; Restart does both.
2. In the window's Connected Apps tab, simulate a handshake ("Kitchen tablet"). Within about two seconds a toast should say a new app wants to use the Engine. It must not name anything beyond that. Click the toast (or the icon): a popup with Approve and Deny appears. Approve it; the window shows the app approved.
3. If a paired peer's agent runs a tool that changes things, the toast says who asked and which tool; Approve or Deny in the popup answers that one call.
4. Stop the Engine from the menu while a popup is open: the popup closes.

## 3. Installing for the signed-in user

```
gradlew.bat :desktopapp:run --args="--install-service engine"
gradlew.bat :desktopapp:run --args="--install-service tray"
```

Or use the window's Background tab, which does both with one switch. Sign out and in again (or run the task by hand): the Engine and the tray should start without a window. Remove with `--uninstall-service engine` and `--uninstall-service tray`, or the same switch.

The Agent daemon is installed the same way, with its own command line after `--exec` (everything after `--exec` belongs to the daemon):

```
--install-service agent --exec java -cp <the daemon's class path> dev.aidos.daemon.MainKt
```

## 4. Installing for the whole machine (needs an administrator or root shell, and a second account to be useful)

```
--install-service engine --system --run-as <account>
```

On Linux this writes `/etc/systemd/system/aidos-engine.service` with `User=<account>` and `Environment="AIDOS_VAULT_MODE=passphrase"`. On macOS a LaunchDaemon with `UserName`. On Windows a Scheduled Task at startup as that account (`schtasks` asks for its password on the console); Windows needs no passphrase because the vault uses DPAPI as that account. Root and SYSTEM are refused.

Expected: the service starts **locked**. `--tray-check` (run as the signed-in user) reports the vault as locked, tunnels that need a key do not start, and the tray's **Unlock vault** takes the passphrase once and unlocks both the Engine and the Agent daemon. A wrong passphrase leaves it locked and says so.

## What to report

For each step: what happened, and for anything that did not match, the output of `--tray-check` and the last lines of the service's log (`journalctl --user -u aidos-engine` on Linux, `Console` on macOS, Task Scheduler history on Windows). The things most likely to need a fix are the ones the tests cannot see: the Windows `schtasks` quoting of a class path with spaces, systemd `ExecStart` quoting, the tray icon on a high-DPI display, and toast delivery on GNOME.
