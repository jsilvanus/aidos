# RFC-0060: Plugin SDK

Status: Draft

## Abstract

The Plugin SDK enables developers to extend Aidos with custom functionality: Knowledge sources, AI models, Tools, Instructions, media handlers, importers, and exporters. Plugins are isolated processes or libraries that integrate via well-defined interfaces. All plugins operate under capability-based security (RFC-0003) and are sandboxed to prevent malicious behavior.

## Motivation

Aidos is extensible from day one:

1. **Knowledge sources**: Custom data sources (databases, APIs, internal wikis).
2. **Models**: Integrate specialized AI models or providers.
3. **Tools**: Custom tools beyond Git, filesystem, shell.
4. **Instructions**: Domain-specific instruction sets.
5. **Media**: Custom media types and viewers.
6. **Importers**: Import from external formats or systems.
7. **Exporters**: Export to specialized formats.

Plugin SDK provides a stable API for these extensions without requiring Aidos core modifications.

## Goals

1. **Define plugin architecture**: How are plugins discovered and loaded?
2. **Specify plugin types**: What can plugins implement?
3. **Establish capability model**: How are permissions enforced?
4. **Clarify API stability**: How are breaking changes handled?
5. **Explain lifecycle**: Installation, activation, deactivation, removal.
6. **Define interoperability**: How do plugins work together?
7. **Specify testing and distribution**: How are plugins published?

## Non-goals

This RFC does not mandate specific implementation languages (Rust, Python, WebAssembly all possible).

This RFC does not create a plugin marketplace in the MVP (future).

This RFC does not specify UI plugin extensions (focus on backend).

This RFC does not address plugin dependency management beyond basic versioning.

## Design

### Plugin System Architecture

```
┌─────────────────────────────────────┐
│      Aidos Core Runtime             │
│  (Projects, Sessions, Tool Broker)  │
└────────────────┬────────────────────┘
                 │
        Plugin Broker (Manager)
                 │
     ┌───────────┼───────────┐
     ↓           ↓           ↓
  [Plugin A]  [Plugin B]  [Plugin C]
   (isolated)  (isolated)  (isolated)
```

Plugins are loaded by Plugin Broker, which:
- Discovers plugins (filesystem, registry)
- Validates and sandboxes them
- Enforces permissions
- Provides plugin API
- Handles lifecycle

### Plugin Types

**Knowledge Plugin:**

Extend Knowledge Engine (RFC-0015) with new sources.

```
Interface:
  query(index_type: String, query: String) -> List<Result>
  index(project_id: UUID, batch: List<Document>)
  
Example: SQL database knowledge source
  query("sql", "SELECT * FROM users WHERE...")
  index("sql", [row1, row2, ...])
```

**Model Plugin:**

Provide custom AI models.

```
Interface:
  list_models() -> List<ModelInfo>
  invoke(model_id: String, request: ModelRequest) -> ModelResponse
  
Example: Specialized code generation model
  invoke("specialized-codegen", {
    prompt: "Generate Rust function for...",
    context: { language: "rust" }
  })
```

**Tool Plugin:**

Extend Tool Broker (RFC-0030) with new tools.

```
Interface:
  list_capabilities() -> List<Capability>
  invoke(capability_id: String, request: ToolRequest) -> ToolResult
  
Example: Slack integration tool
  invoke("slack:send", {
    channel: "#alerts",
    message: "Analysis complete"
  })
```

**Instruction Plugin:**

Provide domain-specific instructions.

```
Interface:
  list_instructions() -> List<Instruction>
  get_instruction(id: String) -> Instruction
  
Example: Python best practices
  get_instruction("python-style")
  → Returns PEP 8 + project-specific rules
```

**Media Plugin:**

Handle custom file types.

```
Interface:
  handles_type(mime_type: String) -> Boolean
  render(file: File) -> RenderedOutput
  extract_metadata(file: File) -> Metadata
  
Example: Scientific paper handler (.pdf, .arxiv)
  render(paper.pdf) → Highlights, annotations, summary
```

**Importer Plugin:**

Import projects from external formats.

```
Interface:
  supports_format(file_extension: String) -> Boolean
  import(file: File, config: ImportConfig) -> Project
  
Example: GitHub projects importer
  import("myrepo.zip") → Create Aidos project from GitHub export
```

**Exporter Plugin:**

Export projects to external formats.

```
Interface:
  supports_format(format: String) -> Boolean
  export(project: Project, config: ExportConfig) -> File
  
Example: Markdown documentation exporter
  export(project) → Generate markdown docs from artifacts
```

### Plugin Discovery

Plugins are discovered from multiple sources:

```
Discovery paths:
  1. Built-in plugins: /usr/lib/aidos/plugins/
  2. User plugins: ~/.aidos/plugins/
  3. Project plugins: .aidos/plugins/
  4. Plugin registry: aidos.registry.io (future)

Plugin manifest (plugin.toml):
  [plugin]
  name = "slack-integration"
  version = "1.0.0"
  author = "Aidos Team"
  description = "Send notifications to Slack"
  
  [plugin.types]
  tool = true
  
  [plugin.capabilities]
  permissions = ["network:https", "secrets:read"]
  
  [plugin.dependencies]
  aidos = "^0.1"
  
  [plugin.entrypoint]
  # Language-specific
  script = "slack.py"           # Python
  binary = "slack-plugin"       # Executable
  wasm = "slack-plugin.wasm"    # WebAssembly
```

### Plugin Loading

Loading process:

```
On plugin discovery:
  1. Read plugin.toml
  2. Validate format version
  3. Check dependencies (Aidos version, etc.)
  4. Verify signature (if signed)
  5. Create sandbox/process
  6. Load plugin code
  7. Call plugin init() hook
  8. Register capabilities
  9. Add to active plugins list

On plugin request:
  1. Route request to plugin
  2. Enforce capability checks
  3. Call plugin method
  4. Receive result
  5. Log invocation
  6. Return to caller
```

### Capability Model

Plugins declare required capabilities:

```
Plugin capabilities:
  - filesystem:read      (read project files)
  - filesystem:write     (write project files)
  - network:https        (make HTTPS requests)
  - secrets:read         (access stored credentials)
  - models:invoke        (call AI models)
  - git:read             (read Git history)
  - git:write            (create commits)

User approval:
  On first use, prompt:
    "Plugin 'slack' wants to:
     - Make HTTPS requests
     - Read credentials
     
     [Allow] [Deny] [Always allow]"

Runtime enforcement:
  If plugin tries filesystem:write without capability:
    → PermissionError (operation blocked)
```

### Plugin Lifecycle

Installation to removal:

```
Install:
  1. Download plugin package
  2. Verify signature
  3. Validate dependencies
  4. Extract to plugin directory
  5. Initialize on next restart

Activate:
  1. Load plugin code
  2. Call plugin.on_activate()
  3. Register capabilities
  4. Subscribe to events if needed
  5. Report activation status

Deactivate:
  1. Call plugin.on_deactivate()
  2. Unregister capabilities
  3. Close connections
  4. Unload code

Remove:
  1. Deactivate plugin
  2. Delete plugin directory
  3. Remove from plugin registry
```

### Plugin API

All plugins expose common interface:

```
Plugin Interface:
  
  on_init() → Result
    Called when plugin loads
    
  on_activate() → Result
    Called when explicitly activated
    
  on_deactivate() → Result
    Called when deactivating
    
  on_destroy() → Result
    Called before unload
    
  get_metadata() → PluginMetadata
    Return plugin info
    
  get_capabilities() → List<Capability>
    List what plugin can do
    
  handle_request(
    capability_id: String,
    request: Request
  ) -> Result
    Route requests to plugin
```

### Configuration

Plugins can be configured:

```
Project config: .aidos/config.toml

[[plugins]]
name = "slack-integration"
enabled = true
config = {
  workspace = "my-workspace",
  token_secret = "slack-token",
  channels = ["#alerts", "#updates"]
}

[[plugins]]
name = "sql-knowledge"
enabled = true
config = {
  connection_string = "secret:db-connection"
}
```

### Communication Between Plugins

Plugins can communicate via events:

```
Plugin A publishes event:
  EventBus.publish("my-event", {
    data: "something",
    timestamp: now()
  })

Plugin B subscribes:
  EventBus.subscribe("my-event", |event| {
    handle_event(event)
  })

Constraints:
  - Events scoped to project
  - Subscriptions need capability
  - Unsubscribe on deactivate
```

### Testing and Distribution

Plugins must be tested:

```
Testing:
  1. Unit tests (standard practice)
  2. Integration tests with mock Aidos API
  3. Sandbox validation (permission checks)
  4. Manual testing in dev environment

Distribution:
  1. Source: GitHub (self-hosting)
  2. Package: .aidos-plugin zip archive
  3. Registry: Submit to aidos.registry.io (future)
  4. Signing: Optional GPG signature
  5. Verification: Users verify before install

Example plugin package:
  slack-plugin.aidos-plugin
  ├── plugin.toml
  ├── slack.py (or slack-binary)
  ├── README.md
  ├── LICENSE
  └── tests/
```

### Versioning and Stability

Plugin API versioning:

```
Semver versioning:
  MAJOR.MINOR.PATCH
  
  MAJOR: Breaking API change
    Plugins must be updated/recompiled
    
  MINOR: New features, backward compatible
    Existing plugins continue working
    
  PATCH: Bug fixes
    No compatibility issues

Aidos core declares API version:
  aidos_api_version = "1.0"
  
Plugin declares compatibility:
  aidos = "^1.0"  (any 1.x version)
  aidos = "~1.2"  (1.2.x only)

Migration path:
  1. Core released with deprecation notice
  2. Plugins have 1 major version to update
  3. Deprecated API removed in next major
```

## Data Model (Conceptual)

```
PluginBroker {
  plugins: Map<PluginId, Plugin>
  registry: PluginRegistry
  
  active_plugins: List<PluginId>
  plugin_processes: Map<PluginId, Process>
  
  event_bus: EventBus
}

Plugin {
  id: String
  name: String
  version: String
  
  manifest: PluginManifest
  config: Map<String, Any>
  
  capabilities: List<Capability>
  status: String  # "loaded", "active", "error"
  
  process: Process?
}

PluginManifest {
  format_version: String
  name: String
  version: String
  author: String
  description: String
  
  plugin_types: List<String>  # "tool", "knowledge", "model", etc.
  
  capabilities: List<String>  # Permissions needed
  dependencies: Map<String, String>
  
  entrypoint: EntryPoint
}

EntryPoint {
  script: String?      # Python/JavaScript path
  binary: String?      # Executable path
  wasm: String?        # WebAssembly module
}
```

## Security

Plugin security is critical:

1. **Sandboxing**: Plugins run in isolated processes (preferred) or namespaced environments.
2. **Capability enforcement**: All operations checked against declared permissions.
3. **Resource limits**: CPU, memory, disk usage quotas.
4. **Network isolation**: Plugins cannot access network except declared in manifest.
5. **Filesystem isolation**: Plugins cannot access filesystem except project directory.
6. **Signature verification**: Optional GPG signing for trusted sources.
7. **Audit logging**: All plugin operations logged.
8. **Review before install**: Users aware of permissions before activating.

## MVP Scope

MVP includes:

1. **Plugin loading**: Discover, load, unload plugins.
2. **Tool plugins**: Extend Tool Broker with new tools.
3. **Capability enforcement**: Permission checks on all operations.
4. **Basic plugin types**: Tool, Knowledge, Model (at least 1-2).
5. **Configuration**: Plugin config files.
6. **Lifecycle**: Install, activate, deactivate, remove.
7. **Event bus**: Plugins communicate via events.
8. **Documentation**: Plugin development guide.

Not included:

- Plugin marketplace (future).
- UI plugins (future).
- Plugin dependency resolution (future).
- Hot reload (future).
- Plugin auto-update (future).
- Cloud-hosted plugins (future).

## Future Work

### Plugin Marketplace

Curated registry of plugins:

```
Aidos Plugin Registry (aidos.registry.io):
  - Browse plugins by category
  - Search and discovery
  - Ratings and reviews
  - One-click install
  - Version management
  - Auto-update capabilities
```

### Hot Reload

Update plugins without restart:

```
Hot reload flow:
  1. User uploads new plugin version
  2. Broker deactivates old version
  3. Loads new code
  4. Activates new version
  5. Existing requests complete gracefully
```

### Plugin Dependencies

Manage plugin-to-plugin dependencies:

```
Plugin A depends on Plugin B:
  dependencies = { "plugin-b" = "^1.0" }

On install:
  1. Check if Plugin B installed
  2. If not, offer to install
  3. Verify version compatibility
  4. Both load in order
```

### UI Plugins

Extend desktop and mobile UIs:

```
UI plugin types:
  - Custom views (dashboards, inspectors)
  - Context menu items
  - Command palette commands
  - Keyboard shortcuts
  - Custom renderers for media types
```

### Performance Monitoring

Monitor plugin performance:

```
Metrics:
  - Execution time per capability
  - Memory usage
  - Network requests
  - Error rates
  
Analytics:
  aidos plugin stats --json
  → CPU, memory, latency, error count
  
Alerts:
  - Slow plugins (> 1s execution)
  - High memory (> 100MB)
  - Error spikes
```

## Open Questions

- Should plugins be required to run in separate processes? (vs. library/in-process)
- Should plugin marketplace be curated or open?
- How should we handle plugin conflicts (two providing same capability)?
- Should users be able to write plugins in any language? (Or only approved ones)
- How should plugin updates be handled (manual or automatic)?
- Should plugins have quota limits? (E.g., max 1 million API calls/month)
- Should there be a plugin review process (security/quality)?
- Should plugins be able to store data in project storage?
