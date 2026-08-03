# RFC-0050: Android

Status: Accepted 2026-08-03

## Abstract

Android is the first Aidos frontend. The app hosts the runtime **in-process inside a foreground
service**, behind the same `RuntimeClient` interface every frontend uses (RFC-0052, D5). Its
purpose is one sentence: make progress on a Git project, offline, from a phone — read, ask,
edit, review, commit. This RFC specifies the app's structure, its background execution model,
where projects live under scoped storage, and the screens that serve that sentence. It is
deliberately opinionated about what *not* to build, because the previous version of this document
listed nine screens and left the two that matter — reviewing a change and editing a file — as an
optional bullet and an omission respectively.

## Motivation

1. **The phone is where the spare time is.** Commutes, queues, waiting rooms. Today that time is
   unusable for real work on a codebase.
2. **Offline is the normal state**, not a degraded one — transport, abroad, no signal.
3. **Voice** is the one input where a phone genuinely beats a laptop.
4. **Instant access** without opening a laptop.

### What Android can and cannot do, stated up front

Android is the most constrained profile and the architecture treats that explicitly rather than
discovering it during implementation (RFC-0049):

| | |
|---|---|
| Available | filesystem (app-private), Git via JGit, local models, bundled native code, HTTP MCP when online |
| **Not available** | general shell, arbitrary subprocesses, stdio MCP, `git worktree`, exact timers, arbitrary filesystem paths, unbounded background execution |

None of these block the core use case. What they require is that the app never pretends
otherwise:

- Unavailable tools are **never offered to the model** (RFC-0008), so it cannot propose them.
- A project declaring `tools = ["shell"]` reports shell as degraded **when the project opens**,
  not when a Run fails halfway through.
- Worker isolation uses **treeless workers** — commits built directly against the object
  database, no second checkout (RFC-0053).
- Runs execute in interruptible, checkpointed steps, so eviction resumes rather than restarts
  (RFC-0009).

An earlier version of this RFC claimed "Android scheduler enables long-running tasks" and that
phones "work offline better than laptops". Both were motivated reasoning. Android background
execution is *harder*, not easier, and offline capability comes from local models and local Git,
not from the platform. The case for Android first rests on where the user is.

## Goals

1. Specify how the app hosts the runtime, and why in-process.
2. Specify the background execution model, precisely, against D24.
3. Specify where projects live under scoped storage, and what that forecloses.
4. Specify the screens that serve the thesis sentence — and name what is deliberately absent.
5. Specify distribution.

## Non-goals

This RFC does not specify visual design, Compose patterns, or navigation implementation.

This RFC does not address iOS.

This RFC does not define cloud sync or collaboration. D16 settles sync: none now, a Git-backed
subset at v1.x, pairing at v2, never full state sync. There is no account and no server.

## Design

### Application identity

Package: **`fi.italeino.aidos`**. Application ID and namespace are the same.

### Hosting the runtime

**The runtime runs in-process, inside a foreground service, in the app's own process** (D5).

```
┌──────────────────────────────────────────────┐
│  fi.italeino.aidos                           │
│                                              │
│  ┌────────────────┐    ┌──────────────────┐  │
│  │  Compose UI    │───▶│  RuntimeClient   │  │
│  └────────────────┘    │  (in-process)    │  │
│                        └────────┬─────────┘  │
│  ┌──────────────────────────────▼─────────┐  │
│  │  AidosService : LifecycleService       │  │
│  │  runtime, executor, broker, models     │  │
│  └────────────────────────────────────────┘  │
└──────────────────────────────────────────────┘
```

No IPC, no local socket, no RPC authentication, no TLS between UI and runtime — those exist on
DESKTOP, where the runtime is a daemon serving several frontends (RFC-0055). On Android there is
one frontend and one process. A previous version of this RFC specified a separate process and an
authenticated socket; that was carried over from the desktop design and would have bought
nothing but serialization cost and an Android-only crash surface.

The UI holds no state the runtime does not. It renders `RuntimeClient` responses and the event
stream, and it survives process death by re-subscribing with `sinceSequence` (RFC-0052) rather
than by caching.

**The service is what keeps the app alive**, and it is the same service in both directions: it is
required for local inference (below) and it is what allows a Run to keep going when the user
switches away.

### Background execution

This is where the previous version was wrong in a way worth stating precisely, because it
promised behaviour the architecture had already decided against — and because the correction is
narrower than it first appears.

**The scenario is right.** Start an analysis, put the phone in your pocket, come back to a result:
that is exactly what D24's primary path is for. A foreground service holding a wake lock runs for
minutes, which is long enough for a full inference step and for many chained steps. The user
switching apps or locking the screen does not stop the Run.

**The mechanism named was wrong.** The old text attributed this to `WorkManager` and added "app
can be killed, task resumes". `WorkManager` gives roughly ten minutes, no timing guarantee, a
fifteen-minute floor for periodic work, and is deferred entirely by Doze. Chained inference steps
do not fit in it. The split is:

| Work | Mechanism | Why |
|---|---|---|
| Any Run that reaches a model call | **Foreground service** with an ongoing notification and a wake lock | D24(a). The window is long enough; the notification is the honest signal that the phone is working |
| Deterministic preparation — index, fetch, reconcile Git, assemble context | `WorkManager` | Bounded, interruptible, resumable, and genuinely suited to a ten-minute window |
| A Run that reaches a model call with no foreground service | **Parks** | D24(d) |

The parked case is not an error state. Without a foreground service — the user declined the
notification, or the OS withheld it — a background Run does its deterministic work, reaches the
model call, parks with `SuspendedOperation.ForegroundRequired`, and posts a *"ready to
continue"* notification. Inference happens when the user opens the app, and it starts faster
because the context is already assembled.

**The ongoing notification is a UI surface, not a compliance tax.** It is the app's face while
the user is elsewhere: what is running, which project, how many steps in, and a Cancel action. An
agent consuming your battery should say so, and a user who can stop it from the shade does not
have to reopen the app to feel in control.

### Storage under scoped storage

Projects live in **app-private storage**:

```
/data/data/fi.italeino.aidos/files/
├── projects/
│   └── <slug>/               ← the user's Git repository
│       ├── .git/
│       └── .aidos/
│           └── state.db      ← RFC-0054 project scope, RFC-0040 schema
├── aidos/
│   ├── user.db               ← user scope
│   ├── secrets/vault.db      ← RFC-0035
│   └── models/               ← weights, user scope (RFC-0054)
└── cache/
```

This follows D2 (`.aidos/` lives inside the project directory, Git-ignored) and RFC-0054's three
scopes. A previous version placed project state at `~/.aidos/projects/<id>/storage.db`, which put
runtime state *outside* the project and implied Aidos relocates repositories. It does not.

Two consequences to state plainly rather than discover:

**`ProjectLocation.LocalPath` is rejected on MOBILE**, even though the transport is in-process and
the type system permits it. Scoped storage means an arbitrary path is not reliably writable, and
JGit needs a real filesystem path rather than a Storage Access Framework document URI. Android
accepts `RuntimeManaged` and `CloneOf` only, and reports this through `AvailabilityReport` rather
than by failing at create time.

**Uninstalling the app deletes the projects.** That is the cost of app-private storage, and it is
acceptable for one reason: Git is the handoff. Work that matters has been pushed to a remote or
exported (RFC-0041). The app says this once, at first project creation, rather than letting a
user discover it. `MANAGE_EXTERNAL_STORAGE` would avoid it and is rejected — an app that reads
and writes your whole filesystem is not the app this project is trying to be, and F-Droid
distribution does not make that permission less invasive.

### Screens

Screens are derived from what the runtime is actually doing, not from a general-purpose app
skeleton. Two rules generate the rest:

- **The user's question when they pick up the phone is "what needs me?"**, not "which project?".
  They have ninety seconds in a queue.
- **Approval is not an interruption; it is the main loop.** Every `Mutate` requires a `Preview`
  (RFC-0030), so a working session generates a steady stream of small decisions.

**1 · Inbox (home).** Everything waiting on the user, across all projects, newest first:

```
  ● Approve    write src/http/Client.kt           aidos · 2m
                 retry with backoff on 5xx     [ +9  -2 ]
  ● Continue   needs foreground to run the model  notes · 14m
  ● Review     4 changes ready to commit          aidos · 1h
  ● Failed     push rejected, remote moved        aidos · 3h
```

This is not a new concept — it is `listPending()` plus Tasks in `AWAITING_APPROVAL`,
`AWAITING_INPUT`, and `ForegroundRequired`, all of which the Execution Graph already tracks. The
project list is one tap away and is not the front door.

**2 · Approval / review card.** One change, its reason, keep or reject. This is the most used
component in the app and it is **the same component** used for reviewing hunks at commit time
(D25) — a `Preview.Diff` mid-Run and a hunk at commit time are the same decision at different
moments. Building it once halves the work and makes the two flows feel identical, which they
should, because they are.

**3 · Session.** Opens on the **Run Summary** — one page, no scrolling, computed from the
Execution Graph (RFC-0057). That is the glance surface: what changed, what is pending, what
failed, in two seconds at a crossing.

Below it, a timeline of steps, **rendered from the same graph**, not a chat transcript. RFC-0019 says the graph is the program rather than a log of it; the UI should show
that program. Model prose is collapsed by default — on a phone it is the least valuable use of
the screen — and a step expands to its detail, its tool call, and its result. This also makes
resume-after-eviction render for free: the graph shows exactly where execution stopped and what
it is waiting for.

**4 · Project.** Git status, branch, recent Runs, and a one-line availability banner from
`AvailabilityReport` — *"shell unavailable · 3 tools degraded"* — at open, never mid-Run
(RFC-0049).

**5 · Commit.** The residue of what the user has already approved:

```
Commit · 8 files, 213 lines
  ✓ 11 changes you approved as they happened
  ! 2 changes not individually reviewed
        ├─ pulled from origin/main
        └─ edited directly
```

Attention goes to the unreviewed set, reviewed changes remain openable, and line-level review is
the hunk card stack from D25. **This screen is not optional.** The previous version of this RFC
had the diff viewer as a bullet inside a section marked *Optional* — the most important screen in
the product, filed under "maybe".

**6 · Editor.** A plain text editor with a soft keyboard, opened from a file or from a diff.

Typing code on a phone is miserable, so most edits should come from the model and be reviewed
rather than typed. But the thesis sentence contains the word *edit*, and "fix this one line" must
not require asking a model to do it. The MVP editor is deliberately minimal — open, edit, save;
no completion, no refactoring, no multi-file operations — and every save is an ordinary `Mutate`
through the broker, so it is audited and previewable like any other change. The previous version
of this RFC contained no editor at all.

**7 · Voice capture.** Microphone → local STT → **editable transcript** → send. Not voice
commands: an unstructured intent spoken while walking is the valuable case, and a structured
command vocabulary is a worse keyboard.

**8 · Eyes-free.** The Run Summary spoken through a local TTS model, on demand or on a terminal
event, and benign approvals answerable by voice (RFC-0057, D26). This is the mode the input side
already assumed: dictating an intent in one second and then reading a transcript for two minutes
is not a usable exchange. Voice may answer only the *benign* class — in-project, reversible,
untrusted-free, already-granted — and anything else parks with *"that one needs your eyes"*.
Spoken approval prompts are composed from runtime-owned fields only, never from file content or
model output, because a hostile repository could otherwise write a sentence that sounds
approvable to someone who cannot see the screen.

### Deliberately absent

| Not built | Why |
|---|---|
| Intent Graph visualization | A graph view on a phone screen is bad, and the Intent Graph is a leaf in the dependency graph — late and small (RFC-0099) |
| Artifacts browser as a top-level destination | Artifacts are outputs of Runs. Reach them from the Run that produced them |
| Account, login, logout | There is no account and no server (RFC-0046) |
| Sync status | There is no sync (D16) |
| Cloud STT or TTS fallback | Contradicts offline-first at the exact point it was promised — and eyes-free is the mode most likely to have no signal |
| Wake words, always-on listening | Privacy-hostile, and nothing needs them |
| In-app tutorials, branding, app shortcuts | Not now |

### Notifications

Three kinds, and no more (RFC-0044):

1. **Ongoing** — the foreground service. What is running, and Cancel.
2. **Needs you** — a Run parked on approval, input, or foreground. Taps through to the inbox.
3. **Terminal** — completed or failed, with the reason.

Rate-limited, never silently repeated. Per-step progress notifications are not built: a phone
buzzing eight times per Run trains the user to disable the channel, and the approval notification
is then lost with it.

### Distribution

**F-Droid.** Reproducible build, no proprietary dependencies, no analytics, no third-party crash
reporting. Diagnostics stay on the device and leave only in a bundle the user sends deliberately
(RFC-0037).

## Data Model

The app holds no persistent model of its own. UI state is `RuntimeClient` responses plus the
event stream; everything durable is in the runtime's databases under the layout above. There is
no `LocalCache` of projects, artifacts, or embeddings on the UI side — a second copy of runtime
state in the frontend is a consistency bug waiting for a reason to appear.

## Security

1. **One process.** The UI and the runtime share a trust domain; there is no IPC boundary to
   authenticate. The boundaries that matter are capability grants (RFC-0018) and taint
   (RFC-0027), and they are enforced in the runtime regardless of which frontend is attached.
2. **Approval requires a `user_interactive` connection** (RFC-0055). On Android that means the
   Activity is in the foreground — a notification action alone can cancel work, never approve a
   capability request.
3. **Secrets** live in `vault.db` (RFC-0035), never in `SharedPreferences`, never in a log, an
   event, or a prompt.
4. **Android permissions** are requested at the point of use and each maps to one feature:
   `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` plus its declared type, `RECORD_AUDIO` for voice,
   `INTERNET` for remote models and Git remotes. No storage permission is requested, because none
   is used.
5. **Data at rest** relies on Android full-disk encryption. Per-project encryption is not in the
   MVP, and the honest statement is that a device unlocked by an attacker exposes the projects.

## MVP

1. Compose UI: inbox, Run Summary, approval/review card, session timeline, project, commit,
   editor.
2. Runtime in-process in a foreground service, with the ongoing notification as a real surface.
3. `WorkManager` for deterministic preparation only; parking with `ForegroundRequired` otherwise.
4. Availability reported at project open.
5. Diff and commit review — hunk card stack, pending D25 sign-off.
6. Notifications: ongoing, needs-you, terminal — the strip density of the Run Summary.
7. Spoken summaries and voice approvals where a local TTS model is installed (RFC-0057).
8. F-Droid build.

Voice capture and spoken output are in Phase 4 but are the first items to cut if the phase slips
(M33) — they are the only ones not on the critical path of the thesis sentence. **The Run Summary
projection and the benign-approval classifier are not cuttable with them:** the summary is the
primary way a user sees what happened, and the classifier is a security boundary the approval
card needs whether or not anything is ever spoken.

## Future Work

- **Per-project encryption at rest**, keyed by the device keystore.
- **User-chosen project locations** via the Storage Access Framework, once JGit can be fed a
  document-URI-backed filesystem — or once the cost of a shim is justified by demand.
- **Camera and OCR** for documents and whiteboards.
- **Voice commands**, if transcription proves reliable enough that a command vocabulary adds
  anything over dictation.
- **Wearable companion** for the inbox: approve or reject from the wrist. Small, and the inbox
  model is what makes it expressible at all.
- **Paired execution** (RFC-0099 Phase 6) — the phone delegates a test run to the user's desktop
  and resumes when it returns. The highest-value feature for this use case, and the reason the
  profile model exists.

## Open Questions

- Should the inbox aggregate across projects by default, or scope to the last-opened project?
  Aggregating is right for the ninety-second case and may be noisy with many projects.
- Should the editor be able to open a file *outside* the project (a scratch note)? Currently no,
  because a capability handle is project-scoped and the exception would be the only path in the
  app that is not.
- How many parked Runs before the inbox needs grouping rather than a flat list?
