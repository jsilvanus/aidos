# RFC-0051: Desktop

Status: Accepted

## Abstract

Desktop is the second Aidos UI, built with Compose Multiplatform for macOS, Windows, and Linux. It is runtime-independent: users can run the headless runtime locally or connect to a remote one. The desktop app offers multiple view modes (IDE-like, Obsidian-like, Chat, Timeline, Intent Graph viewer, Project Explorer) to suit different workflows. Power users and developers use desktop for deep project management.

## Motivation

Desktop complements mobile:

1. **Screen real estate**: Larger displays for complex visualizations.
2. **Keyboard-driven**: Power users want keyboard shortcuts and CLI.
3. **Developer-focused**: Deep Git integration, artifact inspection, Intent Graph editing.
4. **Long sessions**: Sustained work on laptops vs. short bursts on phones.
5. **Multi-monitor**: Support wide workflows.
6. **Headless runtime**: App can be separate from runtime (user's choice).

Desktop uses Compose Multiplatform to share code across platforms while providing native feel.

## Goals

1. **Define desktop architecture**: How does app relate to runtime?
2. **Specify view modes**: What display concepts are supported?
3. **Establish remote runtime**: How to connect to non-local runtime?
4. **Define keyboard support**: Shortcuts, command palette, navigation.
5. **Clarify project management**: Create, import, delete projects.
6. **Explain code editing**: Integration with Intent Graph and artifacts.
7. **Define extensibility**: How can developers customize desktop?

## Non-goals

This RFC does not mandate a specific IDE paradigm (multiple options are supported).

This RFC does not specify every keyboard shortcut (that is design detail).

This RFC does not address collaborative editing in the MVP (future).

This RFC does not define plugin architecture for desktop (future).

## Design

### Architecture

Desktop app communicates with local or remote runtime:

```
┌─────────────────────────────────────┐
│   Desktop Compose Multiplatform     │
│  (Views, project management, IDE)   │
└──────────────┬──────────────────────┘
               │ IPC or Network
               ↓
┌──────────────────────────────────────┐
│   Aidos Headless Runtime             │
│  (Local or remote)                   │
│  (Engine, Tool Broker, Sessions)     │
└──────────────────────────────────────┘
```

The app can:
- **Embedded runtime**: Spawn runtime as child process (local).
- **Remote runtime**: Connect to running Aidos server (user's machine or cloud).

### Runtime Configuration

Users choose runtime mode:

```
Settings → Runtime:

  [x] Use local runtime
      └─ Automatically start on app launch
      └─ Embedded in app process
      
  [ ] Use remote runtime
      └─ Host: 127.0.0.1:9000
      └─ Authentication: [token]
      └─ SSL: [enabled]

Switching modes requires app restart.
```

### Core View Modes

Desktop supports multiple visualization styles. Users choose preferred mode:

**IDE Mode:**

```
┌─────────────────────────────────────┐
│ File Explorer | Editor | Artifacts  │
├─────────────────────────────────────┤
│                                     │
│  src/main.rs                        │ Artifacts
│  ────────────────────────────────── │ ─────────
│  fn main() {                        │ analysis-1
│      // code here                   │ summary.md
│  }                                  │ code-review
│                                     │
├─────────────────────────────────────┤
│ Problems | Terminal | Git           │
└─────────────────────────────────────┘

Like VS Code:
  - File tree on left
  - Editor in center
  - Right sidebar for artifacts
  - Terminal below
  - Navigation tabs
  - Command palette (Cmd+Shift+P)
```

**Obsidian-like Mode:**

```
┌─────────────────────────────────────┐
│ Backlinks | Document | Sidebar      │
├─────────────────────────────────────┤
│                                     │
│  <- architecture.md                 │ Related
│     ├─ overview.md                  │ ─────
│     ├─ design.md                    │ • overview
│     └─ api.md                       │ • design
│                                     │ • api
│  [Graph View] [Timeline]            │
│                                     │
└─────────────────────────────────────┘

Like Obsidian:
  - Backlinks/graph on left
  - Main document in center
  - Sidebar with related items
  - Bi-directional linking between artifacts
  - Graph visualization
  - Timeline of changes
```

**Chat Mode:**

```
┌─────────────────────────────────────┐
│ Projects | Session Chat             │
├─────────────────────────────────────┤
│                                     │
│ Projects:                           │
│ • myapp                             │
│ • research                          │
│                                     │
│ Session: Analysis                   │
│ ──────────────────────────────────  │
│ User: Analyze performance           │
│ AI: Running analysis...             │
│ [████████░░] 80%                    │
│ AI: Results ready                   │
│ [View Artifact]                     │
│                                     │
│ [Text input] [Attach] [Send]       │
└─────────────────────────────────────┘

Like ChatGPT:
  - Conversation-focused
  - Session history on left
  - Chat in center
  - Artifacts as rich messages
  - Streamable responses
```

**Timeline Mode:**

```
┌─────────────────────────────────────┐
│ Filter: All | Sessions | Git        │
├─────────────────────────────────────┤
│ ──────────────────────────────────── │
│ 10:30 AM   Session "analysis" run   │
│             Result: optimization.md │
│ ──────────────────────────────────── │
│ 10:15 AM   Git commit: Add feature  │
│             Files: 3 changed         │
│ ──────────────────────────────────── │
│ 09:45 AM   Resource updated: arch   │
│             By: session-worker-1    │
│ ──────────────────────────────────── │
│                                     │
│ [← Previous] [Next →]               │
└─────────────────────────────────────┘

Timeline of project events:
  - Chronological view
  - Filterable by type
  - Detailed view on click
  - Jump to specific time
```

**Intent Graph Mode:**

```
┌─────────────────────────────────────┐
│ View: Tree | Force-Graph | List     │
├─────────────────────────────────────┤
│                                     │
│         [Main Goal]                 │
│              |                      │
│        ┌─────┼─────┐                │
│        |     |     |                │
│    [Sub1]  [Sub2] [Sub3]            │
│                                     │
│ Status: Sub1 [Done]                 │
│         Sub2 [In Progress]          │
│         Sub3 [Todo]                 │
│                                     │
│ [Edit] [Collapse] [Export]         │
└─────────────────────────────────────┘

Intent Graph visualization:
  - Tree view (hierarchical)
  - Force-directed graph
  - List with collapsing
  - Status indicators
  - Drag-to-reorder (future)
  - Edit nodes inline
```

**Project Explorer Mode:**

```
┌─────────────────────────────────────┐
│ myapp                               │
├─────────────────────────────────────┤
│ ─ Git                               │
│  └─ Branches: main, feature/x       │
│  └─ Commits: 234                    │
│  └─ Status: clean                   │
│                                     │
│ ─ Storage                           │
│  └─ Size: 5.2 GB                    │
│  └─ Projects: 3                     │
│                                     │
│ ─ Intent Graph                      │
│  └─ Goals: 12                       │
│  └─ Status: 8 complete, 2 in prog   │
│                                     │
│ ─ Resources                         │
│  └─ architecture.md                 │
│  └─ standards.md                    │
│                                     │
│ ─ Sessions                          │
│  └─ Active: 1                       │
│  └─ Completed: 45                   │
│                                     │
│ [Backup] [Export] [Settings]        │
└─────────────────────────────────────┘

Project overview:
  - All metadata in one view
  - Quick stats
  - Action buttons
  - Drill-down to details
```

### Keyboard and Navigation

Desktop is keyboard-driven:

```
Global Shortcuts:
  Cmd/Ctrl+K        → Command Palette
  Cmd/Ctrl+N        → New Project
  Cmd/Ctrl+O        → Open Project
  Cmd/Ctrl+S        → Save/Sync
  Cmd/Ctrl+,        → Settings
  Cmd/Ctrl+Shift+P  → Search Projects
  Cmd/Ctrl+Shift+F  → Global Search
  Cmd/Ctrl+Alt+V    → Switch View Mode
  Cmd/Ctrl+Alt+T    → Terminal

Editor Mode:
  Cmd+F             → Find
  Cmd+H             → Replace
  Cmd+/             → Comment
  Cmd+B             → Toggle sidebar
  Cmd+J             → Toggle terminal
  Cmd+D             → Select next
  Cmd+Shift+L       → Multi-select

Navigation:
  Tab               → Next panel
  Shift+Tab         → Previous panel
  Arrow keys        → Navigate within panel
  Enter             → Activate/Open
  Escape            → Close/Cancel
  Space             → Expand/Preview
```

### Project Management

Desktop handles project lifecycle:

```
Create Project:
  1. File → New Project
  2. Dialog: Name, description, template (optional)
  3. Choose runtime (local or remote)
  4. Project created, opens in current view

Open Project:
  1. File → Open
  2. Browse projects
  3. Open (or double-click)
  4. Switches to project view

Import Project:
  1. File → Import
  2. Select .aidos-project file
  3. Optional password for encrypted
  4. Project imported

Export Project:
  1. Project → Export
  2. Dialog: Encryption, signing options
  3. Save to file

Delete Project:
  1. Right-click project → Delete
  2. Confirmation dialog
  3. Local copy deleted (backups unaffected)

Settings:
  1. Cmd/Ctrl+, → Settings
  2. Tabs: Appearance, Runtime, Privacy, Advanced
```

### Code Integration (Future)

Deep Git and code editing:

```
IDE Mode features (MVP):
  - File browser with Git status
  - Read-only artifact viewer
  - Git log viewer
  - Blame viewer (future)

Future:
  - Edit project files directly
  - Real-time Git staging
  - Merge conflict resolution UI
  - Code review tools
```

### Search and Discovery

Find projects and content:

```
Command Palette (Cmd/Ctrl+K):
  > new project        → Create new project
  > open               → Open project picker
  > search             → Global search
  > run session        → Start session
  > view artifacts     → Browse artifacts
  > git log            → View history
  > export             → Export project

Global Search (Cmd/Ctrl+Shift+F):
  - Search across artifacts
  - Search resources
  - Search Git history
  - Search Intent Graph
  - Filters: type, date, author
```

### Connection Management

Remote runtime connection:

```
Settings → Runtime → Remote:
  Server hostname: _________
  Port: 9000
  [SSL/TLS enabled]
  
  Authentication:
    Token: ________________
    [Save token securely]
    
  Connection status: [Connected] / [Disconnected]
  Last heartbeat: 2 minutes ago
  
  [Test Connection] [Clear Cache] [Disconnect]
```

## Data Model (Conceptual)

```
DesktopApp {
  runtime_client: RuntimeClient
  runtime_mode: String              # "local" or "remote"
  
  project_manager: ProjectManager
  current_project: Project?
  
  view_mode: String                 # "ide", "obsidian", "chat", etc.
  ui_state: UiState
  
  settings: AppSettings
}

AppSettings {
  view_mode: String
  theme: String                     # "light", "dark", "auto"
  font_family: String
  font_size: Int
  
  runtime: RuntimeConfig {
    mode: String
    local_auto_start: Boolean
    remote_host: String?
    remote_port: Int?
  }
  
  shortcuts: Map<String, String>    # Custom keybindings
  recent_projects: List<ProjectId>
}

UiState {
  active_panels: List<String>       # Visible panels
  panel_sizes: Map<String, Float>   # Sizes/ratios
  
  selected_project: ProjectId?
  selected_artifact: ArtifactId?
  
  search_query: String?
  search_results: List<SearchResult>
}
```

## Security

Desktop security:

1. **Runtime connection**: TLS encryption for remote runtime.
2. **Authentication**: Token-based auth for remote connections.
3. **Secrets**: No secrets in config files; use system keychain (future).
4. **Audit**: Local operations logged to project storage.
5. **Isolation**: Local runtime runs as separate process.
6. **Permissions**: Request OS permissions for file access.

## MVP Scope

MVP includes:

1. **Compose Multiplatform**: Cross-platform desktop app (macOS, Windows, Linux).
2. **Local runtime**: Embedded or spawned as child process.
3. **Multiple view modes**: IDE, Obsidian, Chat modes (at least 2-3).
4. **Keyboard shortcuts**: Essential navigation and commands.
5. **Project management**: Create, open, import, export projects.
6. **Remote runtime**: Connect to external Aidos server.
7. **Settings**: Basic configuration.
8. **Git integration**: Status and history viewing.

Not included:

- Collaborative editing (future).
- Code editing (future, read-only for MVP).
- Blame/annotation (future).
- Plugin architecture (future).
- Custom themes/branding (future).
- Integrated terminal (future).
- Debugger integration (future).

## Future Work

### Plugin Architecture

Extend desktop with plugins:

```
Plugin types:
  - View modes (custom dashboard)
  - Commands (custom actions)
  - File handlers (open with custom UI)
  - Tool integrations (GitHub, Linear, etc.)

Plugin API:
  - Access project data
  - Subscribe to events
  - Add UI components
  - Hook into menu/palette
```

### Collaborative Editing

Multi-user editing (future):

```
Collaboration:
  - Real-time shared Intent Graph editing
  - Multi-cursor in artifacts
  - Presence awareness
  - Inline comments/suggestions

Conflict resolution:
  - CRDT-based merging
  - Operational Transformation
  - Manual merge if needed
```

### Code Editing

Edit project files:

```
Features:
  - Full code editor with syntax highlighting
  - Real-time Git staging
  - Diff viewer
  - Merge conflict resolution
  - Code formatting
  - Linting integration
```

### Terminal Integration

Integrated terminal:

```
Features:
  - Command line access to runtime
  - Run shell commands
  - Git commands
  - Custom scripts
  - Tab-based (multiple shells)
```

### Extensibility

Advanced customization (future):

```
User scripts:
  - JavaScript/Python via embedded runtime
  - Automate workflows
  - Custom bindings
  - Custom UI components

Theme engine:
  - CSS-like styling
  - Variable system
  - Icon customization
```

## Open Questions

- Should desktop app have a plugin architecture in MVP?
- Should remote runtime support multiplexing (multiple clients)?
- How should desktop handle conflicts with mobile edits?
- Should desktop support SSH tunneling to remote runtime?
- Should there be offline-first desktop mode (local runtime only)?
- How should very large projects handle UI responsiveness?
- Should desktop support custom views/dashboards?
- Should there be a "dev mode" with enhanced debugging?
