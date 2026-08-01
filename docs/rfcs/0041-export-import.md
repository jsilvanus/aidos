# RFC-0041: Export & Import

Status: Accepted

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

## Open Questions

- Should exports be cryptographically timestamped (with external service)?
- How should very large projects (100GB+) handle export?
- Should partial exports be supported? (e.g., exclude Git history)
- Should there be a "minimal export" format for lightweight sharing?
- How should P2P sync use export/import? (Or is that separate?)
- Should exports be versioned for backward compatibility?
- Should there be automatic deduplication across export versions?
- Should export files be timestamped to prevent modification?
