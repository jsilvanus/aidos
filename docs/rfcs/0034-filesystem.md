# RFC-0034: Filesystem

Status: Draft — body not audited against settled decisions (see docs/decisions.md)

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

All filesystem operations are relative to project root:

```
Project root: /projects/myapp

Session requests: filesystem:read { path: "../../../etc/passwd" }
Tool Broker:
  1. Resolve absolute path: /projects/myapp/../../../etc/passwd
  2. Resolve symlinks (if needed)
  3. Check if result is within project root
  4. If outside: Deny with ScopeError
  5. If inside: Proceed with operation

Allowed paths:
  - "src/main.rs" → /projects/myapp/src/main.rs ✓
  - ".git/config" → /projects/myapp/.git/config ✓
  - "docs/" → /projects/myapp/docs/ ✓

Denied paths:
  - "../outside.txt" → /projects/outside.txt ✗
  - "/etc/passwd" → /etc/passwd ✗
  - "symlink_to_outside" → /other/location ✗
```

### Capability Model

File operations are gated by capabilities:

```
Read access: "filesystem:read"
  - Read file contents
  - List directory contents
  - Get file metadata
  - Check file existence

Write access: "filesystem:write"
  - Create/modify files
  - Create directories
  - Requires "filesystem:read" to check conflicts

Delete access: "filesystem:delete"
  - Delete files
  - Remove directories (recursive)
  - Requires "filesystem:read" to list contents

Move access: "filesystem:move"
  - Rename files/directories
  - Move between directories
  - Requires "filesystem:read" and "filesystem:write"
```

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

### Virtual Resources

Some files are generated at runtime (not persisted):

```
Virtual file: ".aidos/manifest.json"
  - Generated from project state
  - Read-only (write fails)
  - Fresh on every access
  
Virtual directory: ".aidos/diagnostics"
  - Contains AI diagnostics, logs
  - Auto-generated from runtime state
  
Sessions can:
  - Read virtual files
  - List virtual directories
  - Cannot write/delete virtual resources
```

### Conflict Detection

When writing, detect conflicts:

```
Session A writes: src/module.rs
Session B simultaneously writes: src/module.rs

Tool Broker detects conflict:
  1. Both sessions see the same modification time initially
  2. Session A's write succeeds (creates version A)
  3. Session B's write fails with ConflictError
  4. Session B can:
     - Read latest version
     - Merge manually
     - Retry with overwrite
```

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

1. **Path restriction**: All paths validated against project root.
2. **Capability checks**: Operations gated by capabilities.
3. **Symlink handling**: Symlinks resolved; targets must stay in project.
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

## Open Questions

- Should symbolic links be supported? (Security risk vs. convenience)
- Should file permissions be mutable? (Or always 0644?)
- How should very large files be handled (streaming vs. full)?
- Should there be a trash/recycle bin before permanent deletion?
- Should file content be indexed for search (RFC-0015)?
- How should concurrent writes to the same file be resolved?
- Should there be a "watch" capability for reactive file changes?
- Should the filesystem tool support file compression?
