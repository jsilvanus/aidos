# UI acceptance: desktop and phone

This is the by-hand acceptance script for RFC-0124 (unified UI, intent graph and generative layer). Juha runs it on the desktop (Bonum, RTX 2060) and on the OnePlus 8. When every step passes on both, item 0124.1 in RFC-0124 is done.

How to read it: each step says what to do, then what you should see. If a step's result differs, mark it FAIL in the table at the end and write what you saw. Do not skip steps silently. Steps marked "(if configured)" need speech-to-text or an agent to be set up first; mark them SKIP with a note if they are not.

## 1. Setup

**Desktop.** Build and start the desktop engine the way `docs/desktop-agent-e2e.md` (section 1) describes:

```
cd engine
./gradlew :desktopapp:run        # Windows: gradlew.bat :desktopapp:run
```

Expected: the Aidos window opens. Leave it open for the steps below.

**Phone.** Install the Android APK from the latest successful run of the `android-build-and-publish.yml` workflow:

1. Open the repository's Actions tab on GitHub, pick the newest successful run of "Build and Publish Android APK" on `main`.
2. Under "Artifacts", download `aidos-android-release-apk` and unzip it. You get an `.apk` file.
3. Copy the `.apk` to the phone and open it. Allow "install unknown apps" for your file manager if asked.

Expected: "Aidos" appears in the app list, and "Aidos Life" appears as a second launcher entry.

## 2. Desktop, daemon not running

Make sure no Agent daemon is running. The engine window alone should be running.

Expected: the top bar shows **"Agent: in-app"**. Open the **Projects** view: it shows the **Workspace** project (the desktop title bar reads "Aidos · Workspace").

## 3. Create a project and check that it survives a restart

1. In Projects, create a new project named **Acceptance**, type **code**.
2. Open it in the Outliner. Add one **GOAL** node (any title, for example "Acceptance goal").
3. Add two tasks under the goal: task A with actor **HUMAN** (title "Human step"), and task B with actor **AGENT** (title "Agent step") that depends on task A.
4. Close the app completely and open it again.

Expected: project Acceptance is still listed. Its GOAL and both tasks are still there, with the same titles and the dependency between them. Nothing is duplicated and no node has lost its title.

## 4. Inbox and the blocked agent task

1. Open the **Inbox** view.
2. Find "Human step" (HUMAN task). Complete it from the Inbox.
3. Open the **Cartridge Dock** view.

Expected: "Human step" is done. "Agent step" is no longer shown as blocked in the Cartridge Dock. It is ready to start.

## 5. Start the agent task and approve a tool

1. In the Outliner, select "Agent step" to open its Inspector sheet. Press **Start**.
2. Open the **Gates** view.
3. If the agent asks to run a tool, a tool approval card appears in Gates. Press **Approve** on one.
4. While the task runs, look at the top bar.

Expected: Gates shows the tool approval waiting, and it disappears from Gates after you approve it. A blue pill appears in the top bar while the task is running. When the run stops, the pill goes away. If the agent asks for no tool, mark step 5 item 3 as SKIP and say so in the note.

## 6. Living Document: text and dictation

1. Open the **Document** view. Pick a task (for example "Agent step") and type one paragraph into its document.
2. Close the app and open it again. Open the **Document** view and the same task.

Expected: the paragraph is still there, word for word.

3. (if configured) Use the microphone to dictate one sentence into the same document.

Expected: the sentence appears as text in the document after dictation finishes.

## 7. Open each of the twelve views once

From the view chip row, open each view in turn. For each one, check that it shows the nodes of project Acceptance (the goal and the two tasks, or the done state after step 4).

| # | View | Shows the Acceptance nodes |
|---|---|---|
| 1 | Inbox | yes/no |
| 2 | Outliner | yes/no |
| 3 | Gates | yes/no |
| 4 | Document | yes/no |
| 5 | Projects | yes/no |
| 6 | Canvas | yes/no |
| 7 | Cartridge Dock | yes/no |
| 8 | Timeline | yes/no |
| 9 | Relay River | yes/no |
| 10 | Radial Radar | yes/no |
| 11 | Metro Map | yes/no |
| 12 | Breadboard | yes/no |

Expected: all twelve open without an error screen. Each one that shows nodes shows the Acceptance nodes.

## 8. Agent daemon running, then restart the app

1. Install the Agent daemon for your signed-in user as `docs/desktop-services-e2e.md` (section 3) describes, and start it.
2. Restart the Aidos window.

Expected: the top bar shows **"Agent: daemon"** instead of "Agent: in-app". The same Acceptance project, nodes and Document text show, with no data missing.

## 9. Agent card from `present_ui`

If an agent run in this project produced a card (the agent called the `present_ui` tool), check it.

Expected: the card shows under the node that produced it, in the Outliner (in that node's section) and in the Living Document. If no run produced a card, mark step 9 as SKIP and say so.

## 10. Phone

Use the Aidos app (main launcher) for steps 3 to 7 of this script. Use Aidos Life (Life launcher) to check that the Life project opens.

1. **Create the project and tasks.** Same as step 3: project "Acceptance", a GOAL, a HUMAN task and an AGENT task that depends on it. Close the app and open it again. Expected: everything is still there.
2. **Inbox and approval.** Same as step 4: complete the HUMAN task in the Inbox. Expected: the AGENT task is no longer blocked.
3. **Gates in one tap.** Expected: **Gates** is in the bottom bar. One tap opens it, with no menu in between.
4. **Views from More.** Open **More** in the bottom bar. Expected: the list shows the views that are not in the bottom bar, including **Canvas**. Press **Canvas** (the "Expand Canvas" view). Expected: the canvas opens and shows the Acceptance nodes.
5. **Rotate.** Turn the phone sideways, then back to upright. Expected: the navigation changes with the orientation (for example the bottom bar and the More list change how they are arranged). The selected view stays the same when the orientation changes.
6. **Metro Map and Breadboard.** Open each from More. Expected: each shows its narrow strip form and the Acceptance nodes, and neither shows an error.
7. **Views 1 to 12 (step 7 above).** Walk through the list. Expected: all twelve open, with the same results as on desktop.

If a foldable or DeX is available (RFC-0124 0124.1 covers a foldable and DeX as well), repeat step 10 on the unfolded device and note the device in the table.

## 11. Results table

Fill one row per step. Use PASS, FAIL or SKIP. Add a note for anything that is not a plain PASS.

| Step | Pass / fail | Note | Device | Date |
|---|---|---|---|---|
| 1. Setup (desktop) | | | Bonum | |
| 1. Setup (phone) | | | OnePlus 8 | |
| 2. Daemon not running, "Agent: in-app", Workspace | | | Bonum | |
| 3. Create project, restart, data kept | | | Bonum | |
| 4. Inbox, blocked task unblocks | | | Bonum | |
| 5. Start agent task, approve tool, blue pill | | | Bonum | |
| 6. Document text kept after restart; dictation (if configured) | | | Bonum | |
| 7. Twelve views open and show nodes | | | Bonum | |
| 8. Daemon running, "Agent: daemon", same data | | | Bonum | |
| 9. Agent card from present_ui (or SKIP) | | | Bonum | |
| 10. Phone, steps 3 to 7 with the launchers | | | OnePlus 8 | |
| 10. Phone, Gates in one tap | | | OnePlus 8 | |
| 10. Phone, Canvas from More | | | OnePlus 8 | |
| 10. Phone, rotation changes navigation | | | OnePlus 8 | |
| 10. Phone, Metro Map and Breadboard strip forms | | | OnePlus 8 | |
| 10. Foldable or DeX (optional) | | | | |

When every row is PASS (or SKIP for the optional items), record the result in RFC-0124 item 0124.1.
