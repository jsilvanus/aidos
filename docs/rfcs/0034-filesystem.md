# RFC-0034: Filesystem

Status: Accepted 2026-08-03

## Abstract

The Filesystem tool allows sessions to access project files through the Tool Broker (RFC-0030). File access is scoped to the project root and protected by capability-based permissions (RFC-0003). Operations include reading, writing, creating, deleting files and directories. The filesystem tool enforces path restrictions to prevent escape attacks, validates content before writing, and logs all access for auditing.

## Motivation

Sessions need to access project files: source code, documentation, configuration, data. Without a unified filesystem abstraction, each subsystem would manage its own file access, creating security gaps and inconsistencies.

The Filesystem tool provides:

1. **Unified interface**: Consistent API for all file operations.
2. **Permission control**: Fine-grained capabilities for read, write, delete.
3. **Path restriction**: Prevent access outside project root.
4. **Content validation**: Sanitize sensitive data before writing.
5. **Audit trail**: Log all filesystem operations.
6. **Atomicity**: Support transactional writes (future).

## Goals

1. **Define filesystem tool in Tool Broker**: What file operations are available?
2. **Establish path scoping**: How is access restricted to project root?
3. **Specify capability model**: What permissions govern file operations?
4. **Clarify read/write semantics**: How are files accessed?
5. **Explain virtual resources**: How are generated files handled?
6. **Define deletion and cleanup**: How are files removed?
7. **Clarify symbolic links**: How are they handled (if at all)?

## Non-goals

This RFC does not specify individual file format standards (those are tool-specific).

This RFC does not address remote filesystem access in the MVP (future work).

This RFC does not define file locking or concurrent access semantics beyond basic conflict detection.

## Design

### Filesystem Tool Interface

Sessions invoke filesystem operations through Tool Broker:

```
Tool: "filesystem"
Capabilities:
  - filesystem:read       (read files, list directories)
  - filesystem:write      (write/create files, create directories)
  - filesystem:delete     (delete files, remove directories)
  - filesystem:move       (rename, move files/directories)

Example:
  Session.invoke("filesystem:read", {
    path: "src/main.rs",
    format: "text"  # or "binary", "json", "yaml"
  })
  
  Result: {
    path: "src/main.rs",
    content: "fn main() { ... }",
    size_bytes: 2048,
    modified_at: Timestamp,
    checksum: "sha256:..."
  }
```

### Path Scoping

**Escape is prevented by construction, not by checking afterwards.**

The previous version specified resolve-then-check: build the absolute path, resolve symlinks,
test whether the result is still inside the root, deny if not. That is the pattern `RelPath`
exists to replace, and it is wrong in two ways. It is a **time-of-check/time-of-use** race — a
symlink can change between the check and the open — and it puts the security decision in every
call site, where one missing check is a full escape.

Instead, a path that could escape **cannot be constructed**:

```kotlin
RelPath.of(raw): Result<RelPath>     // runtime/kernel/, canonical

  rejects:  empty · leading / or \ · any segment equal to ".."
            NUL · a drive-letter prefix (C:)
```

A `RelPath` is a value that is relative and has no upward component, and there is no other way to
name a file. Tools take a `ResourceHandle` whose root was fixed at grant time (RFC-0018) and a
`RelPath` beneath it; neither the tool nor the model ever handles an absolute path.

**Symlinks are resolved at open time and re-checked against the handle's root**, because a
`RelPath` with no `..` can still point at a symlink out of the tree. That check is in one place —
the handle — rather than at every call site, which is the difference that matters.

```
  "src/main.rs"        → RelPath ✓, beneath handle root ✓      → allowed
  "docs/"              → RelPath ✓                             → allowed
  "../outside.txt"     → RelPath.of fails                      → never reaches the tool
  "/etc/passwd"        → RelPath.of fails                      → never reaches the tool
  "symlink_to_outside" → RelPath ✓, resolves outside root      → denied at open
```

M3's property test is the guarantee: no input to `RelPath.of` produces a path that escapes its
root, across `..` in any segment, absolute forms, drive letters, NUL, and encodings of those.

### Capability Model

File operations are gated by capabilities:

Authority is a **named capability the caller exercises**, not a permission string the broker
matches (RFC-0018). A call names the `capabilityId` it is using; one naming none is denied rather
than searched for a grant that would allow it.

Each operation declares its effect and recovery class, and the broker derives behaviour from them
(RFC-0030):

| Operation | Effect | Recovery | Preview |
|---|---|---|---|
| `read`, `list`, `stat`, `exists` | `Read` | `PURE` | n/a |
| `write` | `Mutate(IN_PROJECT)` | `IDEMPOTENT` — same bytes, same result | **`Preview.Diff`** |
| `mkdir` | `Mutate(IN_PROJECT)` | `IDEMPOTENT` | `Description` |
| `move` | `Mutate(IN_PROJECT)` | `CHECKABLE` — probe whether the destination exists | `Description` |
| `delete` | `Mutate(IN_PROJECT, reversible = false)` | `CHECKABLE` | `Preview.Diff` of what is lost |

**`delete` is irreversible and typed as such.** A file the user has not committed exists nowhere
else, so deleting it is not undoable by Git — which puts it in D26's readback tier rather than the
benign one, and keeps it off the single-word voice approval path (RFC-0053, RFC-0057).

`write` returning `IDEMPOTENT` is worth stating: writing identical bytes twice leaves the same
state, so a crashed write is safely re-run. `move` is `CHECKABLE` because after a crash the
runtime can look at whether the destination is there.

### Read Operations

Reading files:

```
Operation: filesystem:read
Parameters:
  path: String              # Relative to project root
  format: String?           # "text" (default), "binary", "json", "yaml", "csv"
  encoding: String?         # "utf-8" (default), "utf-16", etc.
  range: [start, end]?      # Byte range for large files (future)

Result:
  path: String              # Canonical path
  content: String|Bytes     # Based on format
  size_bytes: Int
  modified_at: Timestamp
  created_at: Timestamp
  is_directory: Boolean
  
  # For directories:
  entries: List<FileEntry> {
    name: String
    path: String
    is_directory: Boolean
    size_bytes: Int
    modified_at: Timestamp
  }
```

### Write Operations

Writing files atomically:

```
Operation: filesystem:write
Parameters:
  path: String
  content: String|Bytes
  format: String?           # "text" (default), "binary", "json"
  encoding: String?
  create_parents: Boolean?  # Create missing directories
  overwrite: Boolean?       # Default: false (fail if exists)
  append: Boolean?          # Append instead of replace

Result:
  path: String
  size_bytes: Int
  created: Boolean          # True if new file
  modified_at: Timestamp
  checksum: String          # For verification
```

Writes are atomic:
- Write to temporary file
- Validate content (if formatter specified)
- Rename into place
- On failure, remove temporary file

### Delete Operations

Deleting files and directories:

```
Operation: filesystem:delete
Parameters:
  path: String
  recursive: Boolean?       # For directories
  force: Boolean?           # Don't fail if not exists

Result:
  path: String
  deleted: Boolean
  files_removed: Int        # For directories
```

Directories can only be deleted if:
- Empty, or
- `recursive: true`

### Move Operations

Renaming and moving files:

```
Operation: filesystem:move
Parameters:
  from: String
  to: String
  overwrite: Boolean?       # Default: false

Result:
  from: String
  to: String
  moved: Boolean
```

### `.git/` is readable and not writable

**No `Mutate` effect may target a path inside `.git/`.** Reads are allowed; every write, move,
and delete is refused with a named error that says why, so the model is told the boundary rather
than meeting a mysterious failure.

This is an escalation boundary, not tidiness. An agent that can write inside `.git/` escapes
every other control by writing a file and waiting:

| Path | What it buys an attacker |
|---|---|
| `.git/hooks/pre-commit` | arbitrary code execution the next time **the user** runs `git`. RFC-0053's "Aidos never runs hooks" protects Aidos and does nothing here |
| `.git/config` | `core.fsmonitor` and `core.pager` execute commands; so do aliases. Changing a remote `url` silently redirects the next push |

Legitimate changes to Git's own state go through the **Git tool** (RFC-0032), which offers typed
operations with previews and audit records. That is more work than a raw write and it is the
right trade: a config change should be a reviewable operation, not a blind file write.

`.gitignore` is unaffected — it lives in the working tree, not in `.git/`.

### No virtual filesystem

The previous version described `.aidos/manifest.json` and `.aidos/diagnostics` as *virtual* —
generated on read, never persisted, writes refused. That is removed.

Nothing in the kernel or the schema implements a virtual layer, and building one would create a
second way to ask the runtime questions, with different semantics, reachable by a model. Project
state is a `RuntimeClient` call (RFC-0052); a diagnostic bundle is something the user exports
(RFC-0037). `.aidos/` is real runtime state on disk, and the filesystem tool does not serve it.

### There is no write-conflict protocol

The previous version described two sessions writing one file simultaneously, the second failing
with `ConflictError`. That race cannot arise: at most one *effectful* Task is `RUNNING` per Run
(D14), and across Runs the worktree is the lock (D15). Two sessions do not reach the same file at
the same moment.

What genuinely happens is **the user editing a file outside Aidos** — in an editor, or by
switching branches. That is external mutation, detected by repository fingerprint and handled by
reconciliation (RFC-0053), not by a conflict protocol inside the filesystem tool.

Building conflict detection here would mean maintaining a mechanism for a race the concurrency
model prevents, while the real case went to a different subsystem.

### Metadata Operations

Files have metadata:

```
Metadata:
  created_at: Timestamp
  modified_at: Timestamp
  size_bytes: Int
  permissions: Int          # Octal (0644, etc.)
  owner: String             # Session ID
  checksum: String          # SHA-256 of content
  
Sessions can query: size, modified_at, checksum
Sessions cannot change: permissions, owner (immutable)
```

## Data Model (Conceptual)

```
FilesystemTool extends Tool {
  project_root: Path
  
  virtual_resources: Map<Path, VirtualResource>
  conflict_detector: ConflictDetector
  
  access_log: List<FilesystemLog>
}

FileEntry {
  path: String              # Relative to project root
  is_directory: Boolean
  size_bytes: Int
  
  created_at: Timestamp
  modified_at: Timestamp
  
  created_by: SessionId
  checksum: String?         # For files only
}

VirtualResource {
  path: String
  generator: Callable       # Generates content
  cache_ttl: Duration?      # How long to cache
  
  read_only: Boolean
  permissions: Capability?  # Required to read
}

FilesystemLog {
  timestamp: Timestamp
  session_id: UUID
  
  operation: String         # "read", "write", "delete", "move"
  path: String
  
  success: Boolean
  error: String?
  
  size_bytes: Int?
  duration_ms: Int
}
```

## Security

Filesystem access is protected:

1. **Escape is structural**: a path that could leave the root cannot be constructed (`RelPath`),
   so there is no per-call-site check to forget. Symlinks resolve at open and are re-checked
   against the handle's root, in one place.
2. **Capability checks**: authority is a named capability exercised, not a permission string
   matched (RFC-0018).
3. **`.git/` is not writable**, on any profile, by any session — see above.
4. **Content validation**: Detect and reject invalid content (future).
5. **Audit logging**: All access logged.
6. **Atomic writes**: Prevent partial/corrupted writes.
7. **Permission immutability**: Cannot escalate permissions on files.

## MVP Scope

MVP includes:

1. **Basic read**: Read files, list directories.
2. **Basic write**: Create/overwrite files, create directories.
3. **Basic delete**: Delete files, recursive directory removal.
4. **Path scoping**: Enforce project root restrictions.
5. **Metadata access**: Get file size, modification time.
6. **Atomic writes**: Transaction-like write semantics.
7. **Logging**: Log all filesystem operations.
8. **Conflict detection**: Simple timestamp-based conflict detection.

Not included:

- Streaming/range reads (future).
- Permissions modification (future).
- Symbolic links (future or never).
- Change notifications (future, use Git for now).
- Remote filesystem (future).
- File compression (future).
- Encryption (future).

## Future Work

### Streaming Reads

Large files can be read in chunks:

```
Operation: filesystem:stream_read
Parameters:
  path: String
  chunk_size: Int           # Default 64KB

Callback: (chunk, offset, total_size)
  - Receives data incrementally
  - Can cancel mid-read
```

### Permission Management

Modify file permissions:

```
Operation: filesystem:chmod
Parameters:
  path: String
  permissions: Int          # Octal (0644)
  
Allows sessions to control file visibility within project.
```

### Change Notifications

Reactive filesystem updates:

```
Session subscribes: "watch:src/main.rs"
When file changes: Notification delivered
Uses Git hooks or inotify internally
```

### Remote Filesystem

Access files on remote systems:

```
Configuration:
  remote_filesystem: {
    type: "sftp" | "http"
    endpoint: "sftp://server.com/projects"
    auth: { ... }
  }

Session accesses: filesystem:read { path: "remote://project/file.txt" }
Tool Broker routes to remote filesystem provider
```

### Transactional Writes

Multi-file atomic operations:

```
Operation: filesystem:transaction
Actions:
  - write("file1.txt", ...)
  - write("file2.txt", ...)
  - delete("file3.txt")

Either all succeed or all fail (rollback).
```

### Content Validation

Validate content before accepting writes:

```
Schema registration:
  register("src/**/*.json", "json_schema", schema_url)
  register("docs/**/*.md", "markdown_schema")

Write validation:
  Session writes: src/config.json
  Tool checks: Is JSON valid against schema?
  If invalid: Reject with ValidationError
```

## Resolved

- **Symlinks** — followed for reads, re-checked against the handle root at open. Not created.
- **`.git/` writes** — refused (2026-08-03).
- **Virtual resources** — not built (2026-08-03).
- **Read caching across steps** — not in the MVP (2026-08-03). A cached read can serve content
  the user changed underneath it, which is the whole reason RFC-0053's reconciliation exists. If
  reads later prove expensive, key any cache on the **blob hash** rather than the path, so it
  invalidates for free.
- **Concurrent writes to one file** — cannot arise as described. At most one *effectful* Task is
  `RUNNING` per Run (D14) and the worktree is the lock across Runs (D15), so the two-sessions-
  race the previous version described is prevented rather than detected. What remains is the user
  editing a file outside Aidos, which is reconciliation (RFC-0053), not conflict detection.
- **Search indexing** — RFC-0015's concern, not this tool's.

## Open Questions

- Should file permissions be mutable, or always `0644`? Mutable permissions are an escalation
  surface for very little benefit.
- Very large files: streaming reads versus refusing above a threshold. RFC-0045's budgets should
  decide it with a number.
- Trash before permanent deletion. Attractive given `delete` is irreversible, and it is a second
  storage cost on the device with the least of it.
