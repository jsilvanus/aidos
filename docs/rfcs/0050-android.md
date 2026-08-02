# RFC-0050: Android

Status: Draft

## Abstract

Android is the first Aidos UI, built with Jetpack Compose. It provides an offline-first mobile
experience for making progress on Git projects without a network. The app hosts the runtime
**in-process inside a foreground service**, behind the same `RuntimeClient` interface every
frontend uses (RFC-0052, RFC-0055). Users manage projects, interact with sessions, review
diffs, commit work, and run local models on the device.

## Motivation

The primary Aidos use case is **making progress on Git projects, offline, from a phone**:
reading and understanding code, planning, editing, reviewing, and committing. Not running CI on
a handset.

1. **The phone is where the spare time is.** Commutes, queues, and waiting rooms are when a
   project gets thought about. Today that time is unusable for real work.
2. **Offline is the normal state**, not a degraded one — on transport, abroad, or with no
   signal.
3. **Native input surfaces**: microphone for voice capture, camera for documents.
4. **Instant access** without opening a laptop.

### What Android can and cannot do, stated up front

Android is the most constrained profile, and the architecture treats that explicitly rather
than discovering it during implementation (RFC-0049):

| | |
|---|---|
| Available | filesystem, Git via JGit (object DB and working tree), local models, bundled native tools, HTTP MCP when online |
| **Not available** | general shell, arbitrary subprocesses, stdio MCP, `git worktree`, exact timers, unbounded execution windows |

None of these block the core use case. Reading, planning, editing, reviewing, and committing
require none of them. What they do require is that the app never pretends otherwise:

- Unavailable tools are **never offered to the model** (RFC-0008), so it cannot propose them.
- A project declaring `tools = ["shell"]` reports shell as degraded **when the project opens**,
  not when a Run fails halfway through.
- Worker isolation uses **treeless workers** — commits built directly against the object
  database with no second checkout (RFC-0049). Cheaper than a worktree and available here.
- Runs execute in interruptible, checkpointed steps, so eviction resumes rather than restarts
  (RFC-0009).

An earlier version of this RFC claimed "Android scheduler enables long-running tasks" and that
phones "work offline better than laptops." Both were motivated reasoning: Android background
execution is *harder*, not easier, and offline capability comes from local models and local
Git, not from the platform. The case for Android first rests on where the user is, not on the
platform being permissive.

## Goals

1. **Define Android architecture**: How does the app interact with the runtime?
2. **Specify UI components**: What screens and workflows are included?
3. **Establish offline operation**: How does the app work without network?
4. **Define notifications**: How are users alerted to session completion?
5. **Clarify voice input**: How does voice capture and transcription work?
6. **Explain background scheduler**: How do long tasks run in background?
7. **Define installation and distribution**: How users obtain the app.

## Non-goals

This RFC does not specify every UI screen (that is design document detail).

This RFC does not mandate specific Compose UI patterns (implementation detail).

This RFC does not address iOS in the MVP (future port).

This RFC does not define in-app messaging or tutorials (future).

## Design

### Architecture

Android app communicates with headless runtime via IPC:

```
┌─────────────────────────────────────┐
│      Android Jetpack Compose UI     │
│  (Screens, navigation, gestures)    │
└──────────────┬──────────────────────┘
               │ IPC (local socket)
               ↓
┌──────────────────────────────────────┐
│   Aidos Headless Runtime             │
│  (Engine, Tool Broker, Sessions)     │
│  (Runs in separate process)          │
└──────────────────────────────────────┘
               │
               ↓
        (Git, Filesystem, etc.)
```

The app itself is stateless: it's a frontend displaying runtime state.

### IPC Communication

Android ↔ Runtime via local socket/RPC:

```
Android app:
  1. Detect runtime process (local socket)
  2. Connect via IPC
  3. Register as UI session
  4. Subscribe to events
  5. Send commands to runtime

Example session:
  App: "Get list of projects"
  Runtime: [Returns projects]
  
  App: "Create session on project X"
  Runtime: [Creates session, returns session ID]
  
  App: "Subscribe to session events"
  Runtime: [Streams events to app]
  
  App: "Send command: Analyze file"
  Runtime: [Executes, publishes completion event]
```

### Core Screens and Workflows

**Projects Screen:**

```
List all projects:
  - Project name, description
  - Last modified date
  - Storage size
  - Sync status (if cloud sync exists)
  
Actions:
  - Tap project → Open project view
  - Long-press → Context menu (export, delete, etc.)
  - FAB → Create new project
  - Swipe-down → Refresh/sync
```

**Project View:**

```
Shows project details:
  - Intent Graph visualization (tree/graph view)
  - Recent artifacts
  - Sessions list
  - Resources/architecture view
  - Git status
  
Actions:
  - Start session
  - View artifacts
  - Edit Intent Graph
  - View Git log
  - Export project
```

**Session Interaction:**

```
Active session screen:
  - Chat-like interface
  - Session status and progress
  - Current task/goal
  - Result preview
  - Action buttons (pause, cancel, export result)
  
Input modes:
  - Text input
  - Voice input (with transcription)
  - Quick actions (predefined prompts)
  - File picker
  
Output:
  - Artifacts displayed inline
  - Real-time progress
  - Completion notifications
```

**Artifacts Browser:**

```
Browse project artifacts:
  - Grid/list view of artifacts
  - Filter by type
  - Search
  - Preview pane (inline or full screen)
  - Share/export actions
  
Artifact view:
  - Full content display
  - Metadata (created by, time, provenance)
  - Related artifacts
  - Dependencies
  - Export options
```

**Git Browser (Optional):**

```
View Git history:
  - Commit log
  - Branch view
  - Diff viewer
  - Blame view (future)
  
Actions:
  - Create branch
  - Checkout (if multi-branch workflow)
  - View commit details
```

### Offline-First Operation

App functions fully offline:

```
Offline capability:
  - All data cached locally
  - Can create/view projects
  - Can run sessions (if model offline-capable)
  - Can browse artifacts
  - Can create/edit resources
  
Sync when online:
  - Background sync enabled
  - Detects network restoration
  - Syncs with cloud (if configured)
  - Merges changes from other devices (future)
  
No cloud required:
  - Projects stored on device
  - Runtime on device
  - All computation on device
  - Optional cloud for backup only
```

### Notifications

Users are notified of session completion:

```
Notification types:
  1. Session started: "Session 'analysis' started"
  2. Session progress: "Running step 2/5"
  3. Session completed: "Session 'analysis' completed" + preview
  4. Session failed: "Session 'analysis' failed: timeout"
  5. Resource updated: "Architecture updated by session X"

Configuration:
  Settings → Notifications
    - Enable/disable types
    - Notification channel priority
    - Do-not-disturb scheduling
    - Persistent notification for long tasks

Implementation:
  - Android WorkManager for background tasks
  - Notification channels for categorization
  - PendingIntent to open relevant screen
  - Big text for preview + action buttons
```

### Voice Input

Capture and transcribe voice:

```
Voice input flow:
  1. User taps microphone icon
  2. App requests RECORD_AUDIO permission
  3. Displays recording UI
  4. Captures audio (MediaRecorder)
  5. Stops on user release or timeout
  6. Sends to STT model (local or cloud)
  7. Displays transcript in input field
  8. User can edit before sending

STT options:
  - Local: Whisper (or similar model)
  - Cloud: Google Speech-to-Text (if online)
  
Voice commands (future):
  - "Start analysis session"
  - "Show last artifact"
  - "Cancel session"
```

### Background Scheduler

Long-running tasks work in background:

```
Background task scenario:
  User starts 30-minute analysis
  Puts phone down
  App moves to background
  
Android handles:
  - WorkManager schedules background task
  - Runtime continues execution
  - App can be killed, task resumes
  - Notification shows progress
  - On completion, notification triggers
  
User can:
  - Switch apps (task continues)
  - Lock phone (task continues)
  - Return to app later (see results)
```

### Storage and Caching

Mobile storage strategy:

```
Storage layout:
  /data/data/com.anthropic.aidos/
    ├── projects/
    │   ├── project-id-1/
    │   │   ├── .git/
    │   │   ├── storage.db
    │   │   ├── artifacts/
    │   │   └── resources/
    │   └── project-id-2/
    ├── cache/
    │   ├── embeddings/
    │   ├── model_cache/
    │   └── ui_cache/
    └── logs/

App-specific storage:
  - App-level cache (/cache) for quick access
  - User-accessible storage (future) for exports
  
Privacy:
  - Projects encrypted at-rest (future)
  - Cache cleared on logout (future)
  - No data in shared storage without permission
```

### Settings and Configuration

User settings screen:

```
Settings:
  Account:
    - User email/identity
    - Logout
    
  Projects:
    - Default project
    - Auto-backup (local)
    
  Runtime:
    - Model selection (local vs. cloud)
    - GPU acceleration (if available)
    - Memory limit
    - Thread pool size
    
  UI:
    - Theme (light/dark/auto)
    - Font size
    - Gesture preferences
    
  Notifications:
    - Enable/disable
    - Priority channels
    - Do-not-disturb
    
  Privacy:
    - Analytics (optional)
    - Crash reporting (optional)
    - Remote model usage (opt-in)
    
  Advanced:
    - Clear cache
    - Export logs
    - Reset app
```

## Data Model (Conceptual)

```
AndroidApp {
  ipc_client: IPCClient
  runtime_connection: Connection
  
  ui_state: UiState
  cache: LocalCache
  
  session_subscriptions: Map<SessionId, Subscription>
}

UiState {
  projects: List<ProjectSummary>
  active_sessions: Map<SessionId, SessionView>
  
  current_screen: Screen
  navigation_history: Stack<Screen>
}

LocalCache {
  projects: Map<ProjectId, ProjectData>
  artifacts: Map<ArtifactId, ArtifactData>
  embeddings: Map<EmbeddingId, Bytes>
}

SessionView {
  session_id: UUID
  status: String
  progress: Int?
  current_step: String?
  
  messages: List<Message>
  artifacts_preview: List<ArtifactPreview>
}
```

## Security

Android app security:

1. **Process isolation**: App runs as separate process from runtime.
2. **Permission model**: Request Android permissions explicitly.
3. **IPC authentication**: App authenticates to runtime.
4. **Secrets handling**: No secrets hardcoded; use secure storage.
5. **SSL/TLS**: IPC can be encrypted (if socket connection).
6. **Data at rest**: Project storage encrypted (future).
7. **Audit trail**: All runtime operations logged.

## MVP Scope

MVP includes:

1. **Jetpack Compose UI**: Core screens (projects, project view, session, artifacts).
2. **IPC communication**: Connect to local runtime.
3. **Offline operation**: Full functionality without network.
4. **Background scheduler**: Tasks run while app in background.
5. **Notifications**: Session completion and progress notifications.
6. **Voice input**: Audio capture and local transcription (Whisper).
7. **Settings**: Basic configuration.
8. **Git integration**: View project Git status.

Not included:

- iOS port (future).
- Cloud sync/backup (future).
- In-app tutorials (future).
- Collaborative editing (future).
- Voice commands (future).
- Vision/camera integration (future).
- Wearable support (future).

## Future Work

### Cloud Sync

Sync projects to cloud:

```
Cloud sync architecture:
  - Detect network connectivity
  - Sync project changes to server
  - Detect conflicts (multi-device)
  - Merge or ask user resolution
  - Keep local copy for offline access
  
Sync strategy:
  - Differential sync (only changes)
  - Git push/pull for version history
  - Storage sync for metadata/logs
```

### Collaborative Features

Invite collaborators:

```
Collaboration:
  - Share project with others
  - See changes from collaborators
  - Concurrent session management
  - Conflict resolution UI
  
Implementation:
  - Cloud server for coordination
  - Operational Transformation (OT) or CRDT for merging
```

### Vision Integration

Camera and vision capabilities:

```
Camera features:
  - Document scanning (OCR future)
  - Visual Q&A
  - Code recognition
  
Integration:
  - Vision model processes frames
  - Results embedded in artifacts
  - ML Kit for on-device vision
```

### Voice Commands

Natural language control:

```
Voice commands:
  "Start analysis on current project"
  "Show last artifact"
  "Create new project"
  
Implementation:
  - Intent recognition
  - Command routing to sessions
```

### Wearable Support

Smartwatch companion:

```
Wearable capabilities:
  - Quick status view
  - Voice input for commands
  - Notifications
  
Example:
  User taps watch: "Analyze this"
  Captures 10s audio
  Sends to phone
  Phone runs analysis
  Watch shows result
```

### Desktop Sync

Sync projects across devices:

```
Cross-device workflow:
  - Start project on phone
  - Continue on laptop
  - Desktop has full IDE integration
  - Changes auto-sync
  - Works offline on each device
```

## Open Questions

- Should the app run the runtime in-process or out-of-process?
- How should we handle very large projects on mobile?
- Should voice commands be built-in or a future feature?
- How should we handle multi-device sync conflicts?
- Should Android app support external storage (USB, cloud)?
- Should we support custom domains for UI branding?
- How should app updates be handled (self-hosted OTA)?
- Should we support Android app shortcuts for quick actions?
