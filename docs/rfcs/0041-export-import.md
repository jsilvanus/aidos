# RFC-0041: Export & Import

Status: Implemented 2026-11-10 (MVP scope; was Draft). Requirement-to-code-and-test map: [STATUS.md](STATUS.md#rfc-0041-requirement-map).

## Abstract

Export and Import provide project portability. Users can export projects as self-contained archives for backup, sharing, version control, or migration. Exports are atomic, include all project data (Git history, storage, artifacts, metadata), and support encryption and signing for security. Imports restore projects with schema validation and integrity checking.

## Motivation

Projects must be portable:

1. **Backup**: Users want offline backups to prevent data loss.
2. **Transfer**: Move projects between devices or installations.
3. **Archival**: Long-term storage of completed projects.
4. **Sharing**: Share project snapshots with collaborators (with permission).
5. **Version control**: Track project versions, pin historical states.
6. **Disaster recovery**: Restore from backup if system fails.

Export/Import enables:

1. **Zero-cost migration**: No vendor lock-in.
2. **Cold storage**: Archive old projects indefinitely.
3. **Reproducibility**: Share exact project state with others.
4. **Auditability**: Prove project existed at a point in time.
5. **Distribution**: Share projects via USB, email, cloud storage.

## Goals

1. **Define export format**: What is included and how is it structured?
2. **Specify export types**: Full vs. partial, encrypted vs. plain.
3. **Establish integrity checking**: How to verify exports are uncorrupted?
4. **Define import process**: How are projects restored?
5. **Clarify encryption support**: How to protect sensitive data?
6. **Explain signing**: How to verify authenticity?
7. **Specify selective export**: Export subsets of project data.

## Non-goals

This RFC does not mandate specific encryption algorithms (those are configurable).

This RFC does not address streaming exports in the MVP (future).

This RFC does not specify cloud backup automation (future).

This RFC does not address merging imported projects with existing ones (future).

## Design

### Export Format

Projects are exported as ZIP archives (`.aidos-project`):

```
myapp-2025-08-01.aidos-project
├── metadata.json          # Project metadata, version
├── git/                   # Git repository (full history)
│   └── .git/              # Complete .git directory
├── storage.db             # SQLite database with state
├── artifacts/             # Artifact files
│   ├── artifact-1.bin
│   ├── artifact-2.bin
│   └── ...
├── resources/             # Resource files (if embedded)
│   └── architecture.md
└── manifest.json          # Manifest with checksums
```

### Export Types

**Full Export:**

```
Exports complete project state:
  - Full Git history
  - All storage data
  - All artifacts
  - All resources
  - Project metadata

Use cases:
  - Backup
  - Archival
  - Sharing for deep collaboration
  - Migration to different system
```

**Snapshot Export:**

```
Exports project at a specific point:
  - Git history up to a commit
  - Storage state at that commit
  - Artifacts created before that point
  
Use cases:
  - Version pinning
  - Milestone saving
  - "Before-and-after" comparison
```

**Selective Export:**

```
Exports specific subset:
  - Selected artifacts only
  - Specific Git branches
  - Certain resources
  
Use cases:
  - Share specific work product
  - Extract components
  - Subset for analysis
```

### Metadata and Integrity

Exports contain metadata for verification:

```
metadata.json:
{
  "format_version": "1.0",
  "aidos_version": "0.1.0",
  "project_id": "uuid",
  "project_name": "myapp",
  "exported_at": "2025-08-01T10:30:00Z",
  "exported_by": "user@example.com",
  "storage_schema_version": 5,
  "git_head": "abc123def456",
  
  "export_type": "full",
  "encrypted": false,
  "signed": true,
  
  "size_bytes": 1234567,
  "files": 42
}

manifest.json:
{
  "files": [
    {
      "path": "git/.git/HEAD",
      "size_bytes": 23,
      "checksum_sha256": "abc123..."
    },
    ...
  ],
  "total_checksum": "xyz789...",
  "export_signature": "sig_..."  # If signed
}
```

### Encryption

Projects can be encrypted for security:

```
Encryption flow:
  1. User provides password or key
  2. Key derivation: Argon2(password, salt)
  3. Encrypt entire archive: ChaCha20-Poly1305
  4. Store salt + nonce in metadata
  5. Encrypt metadata separately

Encrypted export:
  myapp-encrypted.aidos-project
    ├── metadata.json.enc    # Encrypted
    └── [all other files encrypted]

To decrypt:
  1. Read metadata.json.enc
  2. Derive key from password
  3. Decrypt archive
  4. Verify integrity tags
```

### Signing

Exports can be cryptographically signed:

```
Signing flow:
  1. Calculate SHA-256 of entire archive
  2. Sign hash with user's private key
  3. Include signature in manifest
  4. Include public key (or cert) in metadata

Signed export:
  metadata.json:
    "public_key": "-----BEGIN PUBLIC KEY-----..."
    "signature_algorithm": "ed25519"
    
  manifest.json:
    "archive_signature": "sig_..."

To verify:
  1. Recalculate archive SHA-256
  2. Verify signature with public key
  3. Confirm signer identity
```

### Export Process

Exporting a project:

```
Command: aidos export myapp [options]

Options:
  --output: Output file path (default: myapp-TIMESTAMP.aidos-project)
  --encrypt: Encrypt with password (interactive)
  --sign: Sign with private key (if configured)
  --type: "full", "snapshot", "selective"
  --since: For selective export, include changes since ref
  --artifacts: Comma-separated artifact IDs (for selective)
  --compress: Compression level 1-9 (default: 6)

Steps:
  1. Validate project
  2. Acquire read lock on project storage
  3. Dump Git repository
  4. Export storage database
  5. Collect artifact files
  6. Create metadata and manifest
  7. Generate checksums
  8. Optionally encrypt
  9. Optionally sign
  10. Create ZIP archive
  11. Write to output file
  12. Release lock

Result:
  ✓ Export created: myapp-2025-08-01.aidos-project (5.2 MB)
```

### Import Process

Importing an exported project:

```
Command: aidos import myapp-2025-08-01.aidos-project

Steps:
  1. Read metadata.json
  2. Verify format version compatibility
  3. If encrypted: Prompt for password, decrypt
  4. If signed: Verify signature
  5. Extract archive to temporary location
  6. Verify all checksums against manifest
  7. Check storage schema version
  8. If schema old: Run migration scripts
  9. Verify Git repository integrity
  10. Create new project directory
  11. Move extracted files into place
  12. Update project index
  13. Verify all data reads correctly
  14. Report status

Result:
  ✓ Project imported: myapp (5.2 MB, 2,547 files)
  ✓ Git history verified: 234 commits
  ✓ Storage migrated: schema v3 → v5
```

### Conflict Handling

When importing into existing project:

```
Scenario: Import myapp-v1.0 when myapp already exists

Options:
  1. --skip: Don't import, error
  2. --rename: Create myapp-imported
  3. --merge: Merge histories (future)
  4. --replace: Overwrite existing (dangerous)

Default: --skip (safe)

If merging (future):
  - Resolve Git history conflicts
  - Merge Intent Graph versions
  - Keep newer versions of artifacts
  - Union all resources
```

### Streaming Export (Future)

For very large projects, support streaming:

```
Command: aidos export myapp --stream --output s3://bucket/path

Stream flow:
  1. Start streaming ZIP archive
  2. Send metadata first
  3. Stream Git objects
  4. Stream storage dumps (chunked)
  5. Stream artifacts progressively
  6. Send manifest last
  
Network resumption:
  - On disconnect, record progress
  - Resume from last completed chunk
  - Avoid re-transmitting
```

## Data Model (Conceptual)

```
ExportConfig {
  project_id: UUID
  
  export_type: String           # "full", "snapshot", "selective"
  selected_artifacts: List<UUID>?
  
  output_path: Path
  compression: Int              # 1-9
  
  encryption: EncryptionConfig? {
    enabled: Boolean
    cipher: String              # "chacha20-poly1305"
    key_derivation: String      # "argon2id"
  }
  
  signing: SigningConfig? {
    enabled: Boolean
    private_key: PrivateKey
    algorithm: String           # "ed25519"
  }
}

ExportMetadata {
  format_version: String
  aidos_version: String
  project_id: UUID
  project_name: String
  
  exported_at: Timestamp
  exported_by: String
  
  export_type: String
  encrypted: Boolean
  signed: Boolean
  
  storage_schema_version: Int
  git_head: String
  
  size_bytes: Int
  files: Int
}

ImportResult {
  success: Boolean
  project_id: UUID
  project_path: Path
  
  git_commits: Int
  artifacts_imported: Int
  resources_imported: Int
  
  schema_migrations_applied: Int
  warnings: List<String>
}
```

## Security

Export/import security:

1. **Encryption**: AES-256 or ChaCha20-Poly1305 for data protection.
2. **Signing**: Ed25519 or RSA for authenticity.
3. **Integrity**: SHA-256 checksums verify uncorrupted transfer.
4. **Key management**: Keys stored securely (future).
5. **Audit trail**: Log all exports/imports.
6. **Access control**: Only project owner can export.
7. **Validation**: Verify all data on import.

## MVP Scope

MVP includes:

1. **Full export**: Complete project snapshots.
2. **ZIP format**: Standard, portable archive.
3. **Metadata and manifest**: Version, structure, checksums.
4. **Encryption**: Optional password-based encryption.
5. **Signing**: Optional signature verification.
6. **Integrity checking**: SHA-256 verification.
7. **Schema migration**: Auto-upgrade old projects.
8. **Conflict detection**: Refuse to overwrite existing.

Not included:

- Snapshot/selective export (future).
- Streaming export (future).
- Cloud backup integration (future).
- Project merging (future).
- Incremental export (future).
- Compression level tuning (future).

## Future Work

### Snapshot Exports

Export projects at specific Git refs:

```
Export at commit:
  aidos export myapp --snapshot abc123def456
  → Includes Git history up to that commit
  → Storage reflects state at that point
  → Allows version pinning
```

### Selective Export

Export subsets of projects:

```
Export specific artifacts:
  aidos export myapp --artifacts id1,id2,id3
  → Only these artifacts included
  → Dependencies included automatically
  
Export branch:
  aidos export myapp --branch feature/x
  → Only this Git branch
  → Associated data only
```

### Incremental Export

Export only changes since last export:

```
Track export history:
  Full export: 100 MB
  Incremental (diff): 5 MB
  Incremental (diff): 3 MB
  
Total transfer: 108 MB
vs. Re-exporting full: 100 MB
Benefits increase with project age.
```

### Cloud Backup Integration

Automatic backup to cloud:

```
Configuration:
  backup: {
    enabled: true
    provider: "s3" | "gcs" | "azure"
    schedule: "daily" | "weekly"
    retention: "30 days"
    encryption: true
  }

Schedule:
  Every day at 2 AM: Export → Encrypt → Upload
  Keep last 30 days of backups
  Automatic deletion of old backups
```

### Project Merging

Merge exported projects:

```
Merge two versions:
  aidos import old-project.aidos-project --merge
  
Resolution strategy:
  - Git: Merge branches
  - Intent Graph: Combine goals
  - Artifacts: Keep both (by ID)
  - Resources: Union
  - Storage: Merge states, resolve conflicts
```

## Implementation Amendments (2026-11-10)

The MVP is built (`agent/portability`, CLI `export`/`import`/`keygen`). Where it differs from the sketch above, the code is the contract:

1. **Layout follows RFC-0054.** Runtime state lives in `<project>/.aidos/`, so the archive mirrors the project directory under `project/` (`.git`, `aidos.toml`, content, `.aidos/state.db`, `.aidos/blobs/`) instead of separate `git/`, `storage.db`, `artifacts/` and `resources/` folders. Artifacts are content-addressed blobs. `.aidos/instance.lock`, WAL/SHM files and the derived `.aidos/index/` are excluded. Nothing outside the project directory is ever included: the vault, device identity, `user.db` and model weights stay on the device.
2. **No lock; a snapshot instead.** A running runtime holds the exclusive project lock (RFC-0055), so export must work beside it. `state.db` is copied with `VACUUM INTO`, a transactionally consistent snapshot. Git and blobs are append-only and hashed as written.
3. **Algorithms.** The RFC leaves them configurable. This build uses the JDK's PBKDF2-HMAC-SHA256 (600,000 iterations) and AES-256-GCM in 64 KiB chunks (nonce counter plus a final-chunk flag in the AAD, so reordering and truncation fail), and Ed25519. The names are recorded in the encrypted header, so Argon2id and ChaCha20-Poly1305 can be added later without breaking existing archives. The *whole* ZIP is encrypted, so `metadata.json` is hidden too (stronger than `metadata.json.enc`).
4. **Signature scope.** The signature covers the exact bytes of `manifest.json`, which lists every payload file's SHA-256 and the hash of `metadata.json`. (An archive cannot contain a signature over itself.) `signature.json` holds the public key; the signer is identified by the SHA-256 fingerprint of that key, and import can require a signature (`--require-signature`) from specific fingerprints (`--trust`).
5. **Import is foreign input.** After verification the importer revokes every capability grant, bumps the revocation epoch and clears instruction and MCP adoptions recorded in the archive's database (RFC-0003, RFC-0016, RFC-0031): authority is granted by this device's user, not carried in by a file. It rewrites `root_path`, writes a `ProjectImported` audit entry, and the exporting project gets a `ProjectExported` entry.
6. **Conflicts.** `--skip` is the default and is called REFUSE (error, nothing touched). `--rename` imports a copy with a new project id (every `project_id` row is rewritten), a new name and a free directory. `--replace` moves the existing directory aside to `<dir>.replaced-<time>`; it never deletes.
7. **Limits.** Import enforces zip-slip and absolute-path rejection, rejects entries outside `project/`, and caps expansion (`maxUncompressedBytes`, default 256 GiB). Git verification checks that HEAD resolves to the exported HEAD and that the commit exists loose or packs are present; it does not run `git fsck`.
8. **Schema migration** runs through `MigrationRunner`. Only schema v1 exists, so no forward migration is exercised yet; a database newer than the runtime is refused.

Open Questions resolved by this build: partial exports and incremental, snapshot, selective and streaming export stay Future Work (not built). Cryptographic timestamping, 100 GB+ projects, P2P sync and cross-version deduplication remain open.

## Open Questions

- Should exports be cryptographically timestamped (with external service)?
- How should very large projects (100GB+) handle export?
- Should partial exports be supported? (e.g., exclude Git history)
- Should there be a "minimal export" format for lightweight sharing?
- How should P2P sync use export/import? (Or is that separate?)
- Should exports be versioned for backward compatibility?
- Should there be automatic deduplication across export versions?
- Should export files be timestamped to prevent modification?
