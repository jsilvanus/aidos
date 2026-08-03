# RFC-0040: Storage

Status: Draft — body not audited against settled decisions (see docs/decisions.md)

## Abstract

Storage is the persistence layer for Aidos projects. Projects need to store mutable data: project metadata, session state, logs, indexes, caches, and intermediate results. SQLite is the initial backend providing ACID guarantees, full-text search, and portability. Future backends (PostgreSQL, alternative databases) are possible through a provider abstraction. Storage enforces data isolation per project and supports efficient queries for Knowledge Engine (RFC-0015) and Instruction Engine (RFC-0016).

## Motivation

Aidos projects accumulate data over time:

1. **Project metadata**: Name, description, created_at, configuration.
2. **Session state**: Intent Graph (RFC-0012), session logs, operation history.
3. **Indexes**: Knowledge Engine (RFC-0015) indexes, embeddings, full-text search.
4. **Caches**: Compiled instructions, model outputs, analysis results.
5. **Artifacts**: Intermediate files, diagnostic data.
6. **Logs**: Tool operations (RFC-0030), model queries, events.

Without a unified storage abstraction, subsystems would use ad-hoc approaches: flat files, multiple databases, inconsistent schemas. Storage provides:

1. **ACID guarantees**: Transactions prevent corruption.
2. **Structured queries**: Efficient retrieval of indexed data.
3. **Isolation**: Projects don't interfere with each other.
4. **Portability**: Projects are exportable (RFC-0041).
5. **Performance**: Indexes and caching for fast access.
6. **Debugging**: Audit trail and diagnostics.

## Goals

1. **Define storage architecture**: What data is persisted and where?
2. **Specify SQLite backend**: How is data organized in SQLite?
3. **Establish provider abstraction**: How could alternative backends be added?
4. **Clarify data isolation**: How are projects isolated?
5. **Define schema versioning**: How do schemas evolve?
6. **Explain query patterns**: What queries does Aidos perform?
7. **Define performance requirements**: What latency is acceptable?

## Non-goals

This RFC does not specify exact SQL schema (that is implementation detail).

This RFC does not mandate specific optimization strategies (e.g., partitioning).

This RFC does not address distributed storage or replication in the MVP.

This RFC does not specify cloud storage backends in the MVP.

## Design

### Storage Architecture

Each project has a dedicated storage backend:

```
Project: "myapp"
  Storage path: ~/.aidos/projects/myapp/storage.db
  
Project: "research"
  Storage path: ~/.aidos/projects/research/storage.db

Storage isolation:
  - Separate database per project
  - Sessions cannot access other project data
  - Encryption per project (future)
```

### Storage Provider Interface

Storage is abstracted through a provider interface:

```
StorageProvider {
  /// Initialize storage for a project
  init(project_id: UUID, config: StorageConfig) -> Storage
  
  /// Execute a query
  query(sql: String, params: List<Any>) -> Result<Rows>
  
  /// Begin transaction
  begin_transaction() -> Transaction
  
  /// Commit transaction
  commit(tx: Transaction) -> Result<void>
  
  /// Rollback transaction
  rollback(tx: Transaction) -> Result<void>
  
  /// Create index
  create_index(name: String, table: String, columns: List<String>) -> Result<void>
  
  /// Export data
  export(path: Path) -> Result<void>
  
  /// Import data
  import(path: Path) -> Result<void>
}
```

### SQLite Backend (MVP)

SQLite is the initial backend:

```
Provider: SQLite
Advantages:
  - Zero-config, serverless
  - ACID transactions
  - Full-text search (FTS5)
  - JSON support
  - Small footprint
  - Portable (single file)
  - Wide tooling support

Configuration:
  database_path: "/path/to/storage.db"
  journal_mode: "WAL"  # Write-Ahead Logging
  synchronous: "NORMAL"
  busy_timeout: "5000ms"
  cache_size: "-64000"  # 64MB in-memory cache
```

### Data Organization

Core tables in SQLite:

```
Table: projects
  id: UUID (PRIMARY KEY)
  name: String
  description: String
  created_at: Timestamp
  updated_at: Timestamp
  version: Int

Table: sessions
  id: UUID (PRIMARY KEY)
  project_id: UUID (FOREIGN KEY)
  role: String  # "driver", "worker"
  state: String  # "active", "paused", "completed"
  started_at: Timestamp
  last_activity: Timestamp
  config: JSON

Table: intent_graph
  id: UUID (PRIMARY KEY)
  project_id: UUID (FOREIGN KEY)
  version: Int
  graph_data: JSON  # Serialized graph
  created_at: Timestamp
  created_by: SessionId

Table: resources
  id: UUID (PRIMARY KEY)
  project_id: UUID (FOREIGN KEY)
  name: String
  resource_type: String  # "architecture", "standards", etc.
  content: TEXT
  source: String  # Where it came from
  updated_at: Timestamp
  version: Int

Table: artifacts
  id: UUID (PRIMARY KEY)
  project_id: UUID (FOREIGN KEY)
  name: String
  content: BLOB
  checksum: String  # SHA-256
  artifact_type: String
  created_at: Timestamp
  created_by: SessionId
  provenance: JSON  # Full provenance tree
  version: Int

Table: tool_logs
  id: UUID (PRIMARY KEY)
  project_id: UUID (FOREIGN KEY)
  session_id: UUID
  tool_id: String
  capability: String
  operation: String
  success: Boolean
  error: String?
  duration_ms: Int
  timestamp: Timestamp
  
Index: (project_id, timestamp)

Table: model_queries
  id: UUID (PRIMARY KEY)
  project_id: UUID (FOREIGN KEY)
  session_id: UUID
  provider: String
  model: String
  task: String
  input_tokens: Int
  output_tokens: Int
  cost: Currency
  timestamp: Timestamp

Table: indexes
  id: UUID (PRIMARY KEY)
  project_id: UUID (FOREIGN KEY)
  name: String  # e.g., "file_search", "code_embeddings"
  index_type: String  # "fts", "embedding", "custom"
  state: String  # "building", "ready", "stale"
  last_updated: Timestamp

Table: embeddings
  id: UUID (PRIMARY KEY)
  project_id: UUID (FOREIGN KEY)
  document_id: UUID  # Reference to artifact/resource
  model: String  # Embedding model used
  vector: BLOB  # Serialized vector
  metadata: JSON
```

### Query Patterns

Common queries Aidos performs:

```
1. Knowledge Engine (RFC-0015):
   SELECT * FROM resources WHERE project_id = ? AND resource_type = ?
   SELECT * FROM artifacts WHERE project_id = ? ORDER BY created_at DESC
   
2. Instruction Engine (RFC-0016):
   SELECT * FROM resources WHERE name IN (...)
   ORDER BY priority DESC

3. Tool Broker (RFC-0030):
   INSERT INTO tool_logs (...)
   SELECT * FROM tool_logs WHERE project_id = ? AND session_id = ?
   
4. Artifact retrieval (RFC-0014):
   SELECT * FROM artifacts WHERE id = ?
   SELECT * FROM artifacts WHERE project_id = ? AND artifact_type = ?

5. Intent Graph (RFC-0012):
   SELECT graph_data FROM intent_graph WHERE project_id = ? ORDER BY version DESC LIMIT 1

6. Full-text search (future):
   SELECT * FROM resources WHERE content MATCH ?
```

### Transactions and Consistency

Storage enforces ACID:

```
Scenario: Session A and B both write Intent Graph

Session A:
  BEGIN TRANSACTION
  INSERT intent_graph (version=5, data=...)
  COMMIT

Session B (simultaneously):
  BEGIN TRANSACTION
  INSERT intent_graph (version=5, data=...)
  COMMIT → CONFLICT (same version)
  
Session B retry:
  Fetch latest version (5)
  Increment to version=6
  INSERT intent_graph (version=6, data=...)
  COMMIT → SUCCESS

Result: No data loss, version is consistent
```

### Schema Versioning

Storage supports schema evolution:

```
Table: schema_version
  version: Int (PRIMARY KEY)
  applied_at: Timestamp
  migrations: JSON  # Descriptions of changes

MVP v1 schema version: 1
When v2 adds new tables: version increments to 2
Migration script: v1 → v2

On storage init:
  1. Read current schema_version from database
  2. If < current app version: Run migrations
  3. Apply all pending migrations in order
  4. Increment version
```

### Performance Optimization

Indexes for fast queries:

```
Indexes:
  - (project_id, created_at) on artifacts
  - (project_id, resource_type) on resources
  - (project_id, timestamp) on tool_logs
  - FTS index on resource content (full-text search)
  - (project_id, session_id) on tool_logs
  
Query optimization:
  - EXPLAIN QUERY PLAN for slow queries
  - Lazy-load large BLOBs (provenance, vectors)
  - Cache frequently accessed metadata in memory
```

### Data Export and Import

Projects are portable (RFC-0041):

```
Export flow:
  1. Dump entire SQLite database
  2. Optionally encrypt with user key
  3. Optionally sign with user signature
  4. Package as .aidos-project file

Import flow:
  1. Receive .aidos-project file
  2. Verify signature (if present)
  3. Decrypt with user key (if encrypted)
  4. Create new SQLite database
  5. Restore data
  6. Validate schema version and migrate if needed
```

## Data Model (Conceptual)

```
StorageBackend {
  project_id: UUID
  provider: StorageProvider
  
  connection: DatabaseConnection
  transaction_log: List<Transaction>
  
  schema_version: Int
  indexes: List<IndexDescriptor>
}

StorageProvider {
  name: String                      # "sqlite", "postgres"
  config: Map<String, Any>
  
  init: (project_id, config) -> Storage
  query: (sql, params) -> Result<Rows>
  transaction: () -> Transaction
}

Transaction {
  id: UUID
  started_at: Timestamp
  operations: List<Operation>
  
  commit: () -> Result<void>
  rollback: () -> Result<void>
}

StorageLog {
  timestamp: Timestamp
  query: String  # Redacted if sensitive
  duration_ms: Int
  rows_affected: Int
  success: Boolean
}
```

## Security

Storage security considerations:

1. **Data isolation**: Projects cannot access each other's data (separate databases).
2. **Transaction integrity**: ACID properties prevent corruption.
3. **Secrets protection**: Sensitive data encrypted at-rest (future).
4. **Query logging**: SQL queries logged for audit (with redaction).
5. **Access control**: Only the project's sessions access its storage.
6. **Backup integrity**: Backups encrypted and signed (future).

## MVP Scope

MVP includes:

1. **SQLite backend**: Single database per project, WAL mode.
2. **Core tables**: Projects, sessions, intent graph, resources, artifacts, tool logs.
3. **Transactions**: ACID guarantees for multi-step operations.
4. **Schema versioning**: Support migration scripts.
5. **Indexing**: Create indexes for common query patterns.
6. **Export/import**: Dump and restore entire database.
7. **Logging**: Log all storage operations.

Not included:

- Alternative backends (future).
- Encryption at-rest (future).
- Replication or clustering (future).
- Query caching (future).
- Sharding (future).
- Backup automation (future).

## Future Work

### Alternative Backends

Support multiple storage providers:

```
PostgreSQL backend:
  - For shared deployments
  - Supports concurrent users
  - Network-accessible

RocksDB backend:
  - Embedded key-value store
  - Better for large embedded projects

DuckDB backend:
  - Optimized for analytics
  - OLAP-style queries
```

### Encryption at Rest

Encrypt data in SQLite:

```
Configuration:
  encryption: {
    enabled: true
    key_derivation: "argon2"
    cipher: "ChaCha20-Poly1305"
  }

On storage init:
  1. Derive key from user password
  2. Enable SQLite encryption
  3. All data encrypted transparently
```

### Query Caching

Cache frequently accessed data:

```
Cache layer:
  - LRU cache for recent queries
  - Invalidate on writes
  - Optional persistent cache file

Example:
  Query: SELECT * FROM resources WHERE type = "architecture"
  First: Hits database, stores in cache
  Second: Returns from cache (< 1ms)
  Write: Invalidates architecture cache
  Third: Rebuilds cache from database
```

### Incremental Backup

Support incremental backups:

```
Backup types:
  - Full: Export entire database
  - Incremental: Export only changes since last backup
  - Differential: Export changes since baseline
  
Backup scheduling:
  - Manual on-demand
  - Automatic (daily/weekly)
  - On project modifications
```

### Query Optimization Tools

Tools to diagnose storage performance:

```
Storage diagnostics:
  - EXPLAIN QUERY PLAN for slow queries
  - Index suggestions
  - Query profiling
  - Cache hit/miss rates
  
Example usage:
  aidos storage analyze myapp
  → Detects slow queries
  → Suggests indexes
  → Reports fragmentation
```

### Distributed Storage

Support shared storage for teams (future):

```
Distributed architecture:
  - Central PostgreSQL server
  - Multiple clients read/write
  - Conflict resolution via version vectors
  - Peer-to-peer replication option
```

## Open Questions

- Should storage support sharding per project? (Or one DB per project?)
- How should large BLOBs (embeddings, large artifacts) be stored efficiently?
- Should there be a WAL pruning strategy to limit disk usage?
- How should backup encryption keys be managed?
- Should storage support streaming for large exports?
- How long should old tool logs be retained?
- Should there be automatic vacuum/optimization jobs?
- Should storage metrics (query count, latency) be exposed?
