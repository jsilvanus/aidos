-- Aidos project-scope schema
-- Location: <project-root>/.aidos/state.db   (Git-ignored — RFC-0054)
--
-- This file is the canonical schema. Where an RFC shows DDL, this file governs;
-- RFC fragments are illustrative and often incremental (ALTER TABLE) for readability.
--
-- Conventions:
--   * IDs are UUIDv7 text, globally unique (RFC-0054 D-scope model).
--   * Timestamps are ISO-8601 UTC text.
--   * Booleans are INTEGER 0/1.
--   * JSON payloads are TEXT with a _json suffix.
--   * row_version is the optimistic-concurrency token (RFC-0017).
--
-- Run with:  PRAGMA foreign_keys = ON;

PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;

-- ---------------------------------------------------------------------------
-- Schema versioning (RFC-0017, RFC-0039)
-- ---------------------------------------------------------------------------

CREATE TABLE schema_versions (
    id              INTEGER PRIMARY KEY CHECK (id = 1),   -- singleton
    version         INTEGER NOT NULL,
    applied_at      TEXT    NOT NULL,
    runtime_version TEXT    NOT NULL
);

CREATE TABLE migration_history (
    version         INTEGER PRIMARY KEY,
    applied_at      TEXT    NOT NULL,
    runtime_version TEXT    NOT NULL,
    duration_ms     INTEGER NOT NULL
);

-- ---------------------------------------------------------------------------
-- Project, sessions, audit  (RFC-0010, RFC-0011, RFC-0003)
-- Not previously in any RFC's DDL despite being referenced by ~20 foreign keys.
-- ---------------------------------------------------------------------------

CREATE TABLE projects (
    id                TEXT PRIMARY KEY,
    name              TEXT NOT NULL,
    description       TEXT,
    root_path         TEXT NOT NULL,
    project_type      TEXT NOT NULL DEFAULT 'generic',    -- RFC-0047
    template_id       TEXT,
    template_version  TEXT,
    state             TEXT NOT NULL DEFAULT 'OPEN',       -- CREATING|OPEN|CLOSING|CLOSED
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    state_updated_at  TEXT NOT NULL,
    row_version       INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE sessions (
    id                TEXT PRIMARY KEY,
    project_id        TEXT NOT NULL,
    name              TEXT NOT NULL,
    role              TEXT NOT NULL,                      -- DRIVER | WORKER
    description       TEXT,
    state             TEXT NOT NULL,                      -- CREATED|SLEEPING|RUNNING|ARCHIVED (RFC-0017)
    parent_session_id TEXT,                               -- set for workers
    worker_ref        TEXT,                               -- refs/aidos/workers/<id> (RFC-0049)
    consecutive_failures INTEGER NOT NULL DEFAULT 0,      -- failure budget (RFC-0011)
    created_at        TEXT NOT NULL,
    last_active_at    TEXT NOT NULL,
    archived_at       TEXT,
    state_updated_at  TEXT NOT NULL,
    row_version       INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (project_id)        REFERENCES projects(id),
    FOREIGN KEY (parent_session_id) REFERENCES sessions(id)
);

CREATE INDEX idx_sessions_project ON sessions(project_id, state);
CREATE INDEX idx_sessions_parent  ON sessions(parent_session_id);

-- Append-only. PERMANENT retention (RFC-0056). Never compacted.
CREATE TABLE audit_log (
    id            TEXT PRIMARY KEY,
    project_id    TEXT NOT NULL,
    sequence      INTEGER NOT NULL,
    occurred_at   TEXT NOT NULL,
    kind          TEXT NOT NULL,                          -- CapabilityGranted, ToolInvoked, ...
    actor_kind    TEXT NOT NULL,                          -- USER|SESSION|WORKER|MCP_SERVER|PLUGIN|RUNTIME (RFC-0046)
    actor_id      TEXT NOT NULL,
    device_id     TEXT NOT NULL,
    subject_ref   TEXT,                                   -- what was acted upon
    capability_id TEXT,
    detail_json   TEXT NOT NULL DEFAULT '{}',             -- redacted (RFC-0035)
    signature     TEXT,                                   -- reserved, unwritten in v1 (RFC-0046)
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE UNIQUE INDEX idx_audit_sequence ON audit_log(project_id, sequence);
CREATE INDEX idx_audit_actor ON audit_log(actor_kind, actor_id, occurred_at);
CREATE INDEX idx_audit_kind  ON audit_log(project_id, kind, occurred_at);

-- ---------------------------------------------------------------------------
-- Event bus (RFC-0004, RFC-0028)
-- ---------------------------------------------------------------------------

CREATE TABLE events (
    id             TEXT PRIMARY KEY,
    project_id     TEXT NOT NULL,
    sequence       INTEGER NOT NULL,                      -- ordering key (NOT timestamp)
    type           TEXT NOT NULL,
    schema_version INTEGER NOT NULL,                      -- payload version, day one (RFC-0039)
    category       TEXT NOT NULL,                         -- FACT | COMMAND | SIGNAL
    visibility     TEXT NOT NULL,                         -- PUBLIC | SESSION | PRIVILEGED
    timestamp      TEXT NOT NULL,                         -- when it occurred; not an ordering key
    source         TEXT NOT NULL,
    topic          TEXT,
    payload        TEXT NOT NULL,                         -- JSON; references, not bulk content
    causality      TEXT,
    causal_depth   INTEGER NOT NULL DEFAULT 0,            -- wake amplification bound (RFC-0028)
    metadata       TEXT,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE UNIQUE INDEX idx_events_sequence ON events(project_id, sequence);
CREATE INDEX idx_events_topic ON events(project_id, topic);
CREATE INDEX idx_events_type  ON events(project_id, type, sequence);

-- ---------------------------------------------------------------------------
-- Scheduler: session subscriptions (RFC-0005 MVP item 1, RFC-0004 "Subscription Model")
-- ---------------------------------------------------------------------------

-- A session wakes from SLEEPING when a published event matches one of its topic patterns and
-- (if given) its event type filter. self_wake opts in to being woken by an event the session
-- itself sourced; the default is refused (RFC-0005 "Cycles and amplification" — a session woken
-- by its own output is a loop, not a feature, unless a subscription deliberately asks for it).
CREATE TABLE session_subscriptions (
    id             TEXT PRIMARY KEY,
    session_id     TEXT NOT NULL,
    topic_patterns TEXT NOT NULL,                         -- JSON array of RFC-0004 topic patterns
    event_types    TEXT,                                  -- JSON array of type strings; NULL = all types
    self_wake      INTEGER NOT NULL DEFAULT 0,             -- 0|1
    created_at     TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(id)
);

CREATE INDEX idx_session_subscriptions_session ON session_subscriptions(session_id);

-- ---------------------------------------------------------------------------
-- Capabilities (RFC-0018)
-- ---------------------------------------------------------------------------

CREATE TABLE capabilities (
    id                   TEXT PRIMARY KEY,
    project_id           TEXT NOT NULL,
    permission           TEXT NOT NULL,
    subject_id           TEXT NOT NULL,
    subject_kind         TEXT NOT NULL,                   -- SESSION|WORKER|PLUGIN|MCP_SERVER|FRONTEND
    scope_json           TEXT NOT NULL,
    constraints_json     TEXT NOT NULL,                   -- includes budget (RFC-0028)
    issued_at            TEXT NOT NULL,
    -- Two columns, never one polymorphic identifier (RFC-0046). A grant may be
    -- issued by the user or delegated by a holder, so an untyped `issued_by`
    -- was ambiguous exactly where attribution matters.
    issued_by_kind       TEXT NOT NULL,                   -- USER|SESSION|WORKER|RUNTIME
    issued_by_id         TEXT NOT NULL,
    parent_capability_id TEXT,
    allows_delegation    INTEGER NOT NULL DEFAULT 0,
    expires_at           TEXT,
    revoked_at           TEXT,
    revoked_by           TEXT,
    revocation_epoch     INTEGER NOT NULL,
    audit_ref            TEXT NOT NULL,
    FOREIGN KEY (project_id)           REFERENCES projects(id),
    FOREIGN KEY (parent_capability_id) REFERENCES capabilities(id),
    FOREIGN KEY (audit_ref)            REFERENCES audit_log(id)
);

CREATE INDEX idx_capabilities_subject ON capabilities(subject_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_capabilities_parent  ON capabilities(parent_capability_id);

-- Mutable counters live outside the immutable capability record.
CREATE TABLE capability_usage (
    capability_id        TEXT PRIMARY KEY,
    exercised_count      INTEGER NOT NULL DEFAULT 0,
    bytes_read           INTEGER NOT NULL DEFAULT 0,
    bytes_written        INTEGER NOT NULL DEFAULT 0,
    budget_consumed_json TEXT NOT NULL DEFAULT '{}',
    last_exercised_at    TEXT,
    FOREIGN KEY (capability_id) REFERENCES capabilities(id)
);

CREATE TABLE project_revocation_epoch (
    project_id TEXT PRIMARY KEY,
    epoch      INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

-- ---------------------------------------------------------------------------
-- Content graph (RFC-0024, RFC-0027)
-- ---------------------------------------------------------------------------

CREATE TABLE content_nodes (
    id                    TEXT PRIMARY KEY,
    project_id            TEXT NOT NULL,
    kind                  TEXT NOT NULL,
    name                  TEXT NOT NULL,
    description           TEXT,
    mutability_policy     TEXT NOT NULL,                  -- IMMUTABLE|APPEND_ONLY|VERSIONED|MUTABLE_LATEST
    sensitivity_level     TEXT NOT NULL,                  -- outbound (RFC-0024)
    egress_eligibility    TEXT NOT NULL,
    trust_level           TEXT NOT NULL DEFAULT 'UNTRUSTED',  -- inbound (RFC-0027); conservative default
    storage_location_json TEXT NOT NULL,
    content_hash          TEXT NOT NULL,
    content_type          TEXT NOT NULL,
    size_bytes            INTEGER NOT NULL,
    created_at            TEXT NOT NULL,
    created_by_kind       TEXT NOT NULL,                  -- SESSION|USER|RUNTIME
    created_by_id         TEXT NOT NULL,
    updated_at            TEXT NOT NULL,
    updated_by_kind       TEXT,
    updated_by_id         TEXT,
    content_version       INTEGER NOT NULL DEFAULT 1,     -- user-visible revision
    row_version           INTEGER NOT NULL DEFAULT 1,     -- optimistic concurrency
    state                 TEXT NOT NULL DEFAULT 'ACTIVE', -- CREATING|ACTIVE|SUPERSEDED|ARCHIVED|DELETED|DANGLING
    tags                  TEXT NOT NULL DEFAULT '[]',
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE INDEX idx_content_nodes_project ON content_nodes(project_id, state, kind);
CREATE INDEX idx_content_nodes_hash    ON content_nodes(content_hash);
CREATE INDEX idx_content_nodes_trust   ON content_nodes(project_id, trust_level);

-- Content -> content lineage. Immutable; acyclicity enforced on insert (RFC-0024).
CREATE TABLE provenance_edges (
    id                TEXT PRIMARY KEY,
    from_node_id      TEXT NOT NULL,
    to_node_id        TEXT NOT NULL,
    edge_kind         TEXT NOT NULL,                      -- DERIVED_FROM|EXTRACTED_FROM|VERSION_OF|REFERENCED_BY|MERGED_FROM
    created_at        TEXT NOT NULL,
    created_by_run_id TEXT,
    FOREIGN KEY (from_node_id) REFERENCES content_nodes(id),
    FOREIGN KEY (to_node_id)   REFERENCES content_nodes(id),
    UNIQUE (from_node_id, to_node_id, edge_kind)
);

CREATE INDEX idx_provenance_from ON provenance_edges(from_node_id);
CREATE INDEX idx_provenance_to   ON provenance_edges(to_node_id);

-- ---------------------------------------------------------------------------
-- Intent graph (RFC-0012)
-- Node/edge tables were never given DDL in the RFC.
-- Note: no `status` column. Status is derived (RFC-0012 D10).
-- ---------------------------------------------------------------------------

CREATE TABLE intent_nodes (
    id                   TEXT PRIMARY KEY,
    project_id           TEXT NOT NULL,
    type                 TEXT NOT NULL,                   -- GOAL|SUB_GOAL|CONSTRAINT|ACCEPTANCE_CRITERION
    title                TEXT NOT NULL,
    description          TEXT,
    priority             INTEGER NOT NULL DEFAULT 100,
    lifecycle            TEXT NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE|ARCHIVED (authorship, not progress)
    parent_id            TEXT,
    -- user override of derived status, stored as a timestamped claim
    asserted_status      TEXT,
    asserted_at          TEXT,
    asserted_by_user_id  TEXT,
    assertion_note       TEXT,
    -- acceptance criteria only
    check_kind           TEXT,
    check_spec           TEXT,
    verification_met     INTEGER,
    verified_by_kind     TEXT,                            -- USER | CHECK. never SESSION (RFC-0012)
    verified_by_id       TEXT,
    verified_at          TEXT,
    created_at           TEXT NOT NULL,
    created_by_kind      TEXT NOT NULL,
    created_by_id        TEXT NOT NULL,
    modified_at          TEXT NOT NULL,
    modified_by_kind     TEXT NOT NULL,
    modified_by_id       TEXT NOT NULL,
    tags                 TEXT NOT NULL DEFAULT '[]',
    row_version          INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (parent_id)  REFERENCES intent_nodes(id),
    CHECK (verified_by_kind IS NULL OR verified_by_kind IN ('USER', 'CHECK'))
);

CREATE INDEX idx_intent_project ON intent_nodes(project_id, lifecycle, priority);
CREATE INDEX idx_intent_parent  ON intent_nodes(parent_id);

-- Dependencies only. `dependents` is derived by reverse lookup (RFC-0012).
CREATE TABLE intent_edges (
    id           TEXT PRIMARY KEY,
    project_id   TEXT NOT NULL,
    from_node_id TEXT NOT NULL,
    to_node_id   TEXT NOT NULL,
    edge_kind    TEXT NOT NULL,                           -- DEPENDS_ON|CONSTRAINS|ACCEPTS
    created_at   TEXT NOT NULL,
    FOREIGN KEY (project_id)   REFERENCES projects(id),
    FOREIGN KEY (from_node_id) REFERENCES intent_nodes(id),
    FOREIGN KEY (to_node_id)   REFERENCES intent_nodes(id),
    UNIQUE (from_node_id, to_node_id, edge_kind)
);

CREATE INDEX idx_intent_edges_from ON intent_edges(from_node_id, edge_kind);
CREATE INDEX idx_intent_edges_to   ON intent_edges(to_node_id, edge_kind);

-- Sessions propose; only users resolve. No SESSION variant by construction (RFC-0012).
CREATE TABLE intent_proposals (
    id                  TEXT PRIMARY KEY,
    project_id          TEXT NOT NULL,
    operations_json     TEXT NOT NULL,                    -- atomic batch
    rationale           TEXT NOT NULL,
    proposed_by_run_id  TEXT NOT NULL,
    proposed_at         TEXT NOT NULL,
    run_taint           TEXT NOT NULL,                    -- RFC-0027
    state               TEXT NOT NULL,                    -- PENDING|ACCEPTED|ACCEPTED_WITH_EDITS|REJECTED|SUPERSEDED|EXPIRED
    resolved_by_user_id TEXT,
    resolved_at         TEXT,
    expires_at          TEXT NOT NULL,
    audit_ref           TEXT NOT NULL,
    FOREIGN KEY (project_id)         REFERENCES projects(id),
    FOREIGN KEY (proposed_by_run_id) REFERENCES runs(id),
    FOREIGN KEY (audit_ref)          REFERENCES audit_log(id)
);

CREATE INDEX idx_proposals_pending ON intent_proposals(project_id, expires_at) WHERE state = 'PENDING';

-- ---------------------------------------------------------------------------
-- Execution graph (RFC-0019, RFC-0009, RFC-0008)
-- ---------------------------------------------------------------------------

CREATE TABLE runs (
    id                   TEXT PRIMARY KEY,
    session_id           TEXT NOT NULL,
    project_id           TEXT NOT NULL,
    trigger_event_id     TEXT NOT NULL,
    started_at           TEXT NOT NULL,
    ended_at             TEXT,
    state                TEXT NOT NULL,                   -- PENDING|RUNNING|YIELDED|COMPLETED|FAILED|CANCELLED|INTERRUPTED
    error_code           TEXT,                            -- RFC-0029
    error_class          TEXT,
    error_detail_json    TEXT,
    user_message_summary TEXT,
    retry_policy_json    TEXT NOT NULL,
    step_index           INTEGER NOT NULL DEFAULT 0,      -- RFC-0009
    max_steps            INTEGER NOT NULL DEFAULT 24,     -- RFC-0008
    taint_level          TEXT NOT NULL DEFAULT 'TRUSTED', -- RFC-0027, monotonic
    taint_source_node_id TEXT,
    platform_profile     TEXT NOT NULL,                   -- RFC-0049
    device_id            TEXT NOT NULL,                   -- which machine ran this (RFC-0046)
    network_available    INTEGER NOT NULL DEFAULT 0,
    degraded_tools       TEXT NOT NULL DEFAULT '[]',
    instruction_set_hash TEXT,                            -- RFC-0016; NULL = none adopted
    row_version          INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (session_id) REFERENCES sessions(id),
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE INDEX idx_runs_session ON runs(session_id, started_at DESC);
CREATE INDEX idx_runs_state   ON runs(project_id, state);

-- Declared plans: proposed as a batch, approved before execution (RFC-0019).
CREATE TABLE execution_plans (
    id                   TEXT PRIMARY KEY,
    run_id               TEXT NOT NULL,
    proposed_by_task_id  TEXT NOT NULL,
    proposed_at          TEXT NOT NULL,
    state                TEXT NOT NULL,                   -- PROPOSED|APPROVED|REJECTED|SUPERSEDED
    resolved_by_user_id  TEXT,                            -- ONLY a user (RFC-0046)
    resolved_at          TEXT,
    task_count           INTEGER NOT NULL,
    estimated_steps      INTEGER,
    estimated_cost_units INTEGER,
    spawns_workers       INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (run_id) REFERENCES runs(id)
);

CREATE INDEX idx_plans_run ON execution_plans(run_id, proposed_at);

CREATE TABLE tasks (
    id                TEXT PRIMARY KEY,
    run_id            TEXT NOT NULL,
    plan_id           TEXT,                               -- NULL for emergent tasks
    session_id        TEXT NOT NULL,
    project_id        TEXT NOT NULL,
    ordinal           INTEGER NOT NULL,
    kind              TEXT NOT NULL,                      -- MODEL_CALL|TOOL_CALL|CAPABILITY_REQUEST|USER_PROMPT|COMPOSITE
    description       TEXT NOT NULL,
    tool_name         TEXT,
    model_capability  TEXT,
    state             TEXT NOT NULL,                      -- PENDING|RUNNING|AWAITING_APPROVAL|AWAITING_INPUT|COMPLETED|FAILED|CANCELLED|SKIPPED
    started_at        TEXT,
    ended_at          TEXT,
    awaiting_run_id   TEXT,                               -- parked on a child Run (RFC-0006)
    approval_channel  TEXT,                               -- tap|voice_tier1|voice_tier2 (RFC-0057, D26)
    approval_phrase   TEXT,                               -- tier 2 only: the recognised phrase
    retry_policy_json TEXT NOT NULL,
    row_version       INTEGER NOT NULL DEFAULT 1,
    FOREIGN KEY (run_id)          REFERENCES runs(id),
    FOREIGN KEY (plan_id)         REFERENCES execution_plans(id),
    FOREIGN KEY (session_id)      REFERENCES sessions(id),
    FOREIGN KEY (awaiting_run_id) REFERENCES runs(id),
    UNIQUE (run_id, ordinal)
);

CREATE INDEX idx_tasks_run      ON tasks(run_id, ordinal);
CREATE INDEX idx_tasks_runnable ON tasks(run_id, state, ordinal);

CREATE TABLE attempts (
    id                      TEXT PRIMARY KEY,
    task_id                 TEXT NOT NULL,
    attempt_number          INTEGER NOT NULL,
    started_at              TEXT NOT NULL,
    ended_at                TEXT,
    state                   TEXT NOT NULL,                -- RUNNING|COMPLETED|FAILED|CANCELLED
    error_code              TEXT,                         -- RFC-0029
    error_class             TEXT,
    error_detail_json       TEXT,
    input_snapshot          TEXT,                         -- AGED, compactable (RFC-0056)
    output_snapshot         TEXT,                         -- AGED
    prompt_package_json     TEXT,                         -- RFC-0025; AGED
    model_provider          TEXT,
    model_version           TEXT,
    provider_retention_json TEXT,                         -- RFC-0026
    tokens_input            INTEGER,
    tokens_output           INTEGER,
    cost_units              INTEGER,                      -- RFC-0028
    capability_id           TEXT,                         -- authority actually exercised
    idempotency_key         TEXT,                         -- RFC-0009
    recovery_class          TEXT NOT NULL DEFAULT 'PURE', -- PURE|IDEMPOTENT|CHECKABLE|UNSAFE
    audit_ref               TEXT NOT NULL,
    FOREIGN KEY (task_id)       REFERENCES tasks(id),
    FOREIGN KEY (capability_id) REFERENCES capabilities(id),
    FOREIGN KEY (audit_ref)     REFERENCES audit_log(id),
    UNIQUE (task_id, attempt_number)
);

CREATE INDEX idx_attempts_task    ON attempts(task_id, attempt_number);
CREATE INDEX idx_attempts_running ON attempts(state) WHERE state = 'RUNNING';
CREATE INDEX idx_attempts_error   ON attempts(error_class) WHERE error_class IS NOT NULL;

-- Execution -> content and execution -> intent facts only.
-- Containment is expressed with foreign keys, never here (RFC-0019).
-- Heterogeneous endpoints, so SQLite cannot enforce integrity: a consistency
-- check does (RFC-0038).
CREATE TABLE execution_edges (
    id                TEXT PRIMARY KEY,
    project_id        TEXT NOT NULL,
    from_node_id      TEXT NOT NULL,
    from_node_kind    TEXT NOT NULL,                      -- RUN|TASK|ATTEMPT
    to_node_id        TEXT NOT NULL,
    to_node_kind      TEXT NOT NULL,                      -- CONTENT_NODE|INTENT_NODE|TASK|ATTEMPT
    edge_kind         TEXT NOT NULL,                      -- PRODUCED|CONSUMED|TARGETED|IMPLEMENTS|RETRY_OF|PRODUCED_CALL|DEPENDS_ON
    confirmed         INTEGER NOT NULL DEFAULT 0,         -- IMPLEMENTS only
    confirmed_by_kind TEXT,                               -- USER | ACCEPTANCE_CRITERIA
    confirmed_by_id   TEXT,
    confirmed_at      TEXT,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    UNIQUE (from_node_id, to_node_id, edge_kind)
);

CREATE INDEX idx_edges_from ON execution_edges(from_node_id, edge_kind);
CREATE INDEX idx_edges_to   ON execution_edges(to_node_id, edge_kind);

-- One row per tool call issued by a model (RFC-0008).
CREATE TABLE tool_calls (
    call_id       TEXT PRIMARY KEY,
    run_id        TEXT NOT NULL,
    model_task_id TEXT NOT NULL,
    tool_task_id  TEXT,                                   -- NULL if rejected before execution
    tool_name     TEXT NOT NULL,
    arguments_json TEXT NOT NULL,
    schema_valid  INTEGER NOT NULL,
    outcome       TEXT NOT NULL,
    step_index    INTEGER NOT NULL,
    FOREIGN KEY (run_id)        REFERENCES runs(id),
    FOREIGN KEY (model_task_id) REFERENCES tasks(id),
    FOREIGN KEY (tool_task_id)  REFERENCES tasks(id)
);

CREATE INDEX idx_tool_calls_run ON tool_calls(run_id, step_index);

-- What a parked Run is waiting for. Not a resumable handle (RFC-0006).
CREATE TABLE continuations (
    run_id                TEXT PRIMARY KEY,
    task_id               TEXT NOT NULL,
    suspended_operation   TEXT NOT NULL,                  -- AI_CALL|TOOL_CALL|USER_PROMPT|CAPABILITY_APPROVAL|CHILD_RUN|FOREGROUND_REQUIRED
    operation_detail_json TEXT NOT NULL,
    correlation_id        TEXT,
    created_at            TEXT NOT NULL,
    FOREIGN KEY (run_id)  REFERENCES runs(id),
    FOREIGN KEY (task_id) REFERENCES tasks(id)
);

CREATE INDEX idx_continuations_correlation ON continuations(correlation_id);

-- ---------------------------------------------------------------------------
-- Prompt provenance (RFC-0025, RFC-0027)
-- ---------------------------------------------------------------------------

CREATE TABLE prompt_provenance (
    id              TEXT PRIMARY KEY,
    attempt_id      TEXT NOT NULL,
    content_node_id TEXT NOT NULL,
    included        INTEGER NOT NULL,
    role            TEXT NOT NULL,                        -- context|instruction|tool_result|history
    token_count     INTEGER NOT NULL,
    relevance_score REAL,
    trust_level     TEXT NOT NULL DEFAULT 'UNTRUSTED',
    FOREIGN KEY (attempt_id)      REFERENCES attempts(id),
    FOREIGN KEY (content_node_id) REFERENCES content_nodes(id)
);

CREATE INDEX idx_prompt_provenance_attempt ON prompt_provenance(attempt_id);

-- ---------------------------------------------------------------------------
-- Instruction adoption (RFC-0016)
--
-- An instruction set is identified by the hash of its ordered (filename, blob
-- hash) pairs. Unadopted sets are excluded from the prompt: an AGENTS.md in a
-- freshly cloned repository is attacker-controlled text aimed at the system
-- turn, and nobody reads a cloned repo's instruction file.
-- ---------------------------------------------------------------------------

CREATE TABLE instruction_adoptions (
    project_id      TEXT NOT NULL,
    set_hash        TEXT NOT NULL,
    adopted_at      TEXT NOT NULL,
    adopted_by      TEXT NOT NULL,                        -- user|authored_in_aidos
    source_manifest TEXT NOT NULL,                        -- JSON: ordered (filename, blob_hash)
    PRIMARY KEY (project_id, set_hash),
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
) WITHOUT ROWID;

-- MCP operation adoption (RFC-0031, D31)
--
-- A server's tool *description* is third-party prose that reaches the model in
-- every prompt, before any call has returned. Taint cannot govern it: descriptors
-- enter at step 0, so counting them would leave every Run permanently tainted and
-- approval never clears taint. Admission governs it instead — the same answer
-- RFC-0016 gives for instruction files.
--
-- Keyed per operation, not per catalog, so a server adding one tool does not
-- withdraw the rest. descriptor_hash covers (name, description, inputSchema): a
-- constant description over a widened parameter is a real attack.
--
-- No FK to mcp_servers — that table is user scope, a different database (RFC-0054).
--
-- The adopted descriptor is stored here in full, not just hashed. Three reasons:
--   1. An adoption *is* "the user read this exact prose and approved it", so the
--      prose belongs with the approval. A hash alone records that a decision was
--      made without recording what was decided.
--   2. It is what makes offering MCP tools possible without connecting. D30 says
--      nothing spawns or connects on project open, and a model is never shown a
--      tool the runtime did not offer — so a runtime that could only learn
--      descriptions by connecting could never offer them at all.
--   3. Offline. A registered server that is unreachable still has its adopted
--      operations describable; the call fails, the catalog does not vanish.
-- descriptor_hash stays as the integrity check against the live server: on
-- connect, an operation whose descriptor no longer hashes to the adopted value is
-- withdrawn, which is exactly the "constant description over a widened parameter"
-- case above.
CREATE TABLE mcp_operation_adoptions (
    project_id        TEXT NOT NULL,
    server_name       TEXT NOT NULL,
    operation_name    TEXT NOT NULL,
    descriptor_hash   TEXT NOT NULL,                      -- (name, description, inputSchema)
    -- The adopted descriptor itself. Third-party prose: fenced before it reaches
    -- a model (D31), never trusted, and never a source of resultGuidance (D23).
    description       TEXT NOT NULL,
    input_schema_json TEXT NOT NULL,
    adopted_at        TEXT NOT NULL,
    PRIMARY KEY (project_id, server_name, operation_name, descriptor_hash),
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
) WITHOUT ROWID;

-- ---------------------------------------------------------------------------
-- Session memory (RFC-0011, RFC-0026)
-- ---------------------------------------------------------------------------

CREATE TABLE memory_entries (
    id               TEXT PRIMARY KEY,
    session_id       TEXT NOT NULL,
    project_id       TEXT NOT NULL,
    -- No SUMMARY kind: no model-written compaction of a session (D32).
    kind             TEXT NOT NULL
                     CHECK (kind IN ('FACT','DECISION','TASK_STATE')),
    content          TEXT NOT NULL,
    source_refs_json TEXT NOT NULL,                       -- never '[]' (RFC-0026)
    -- Who wrote it, distinct from what justifies it (RFC-0046). `confidence`
    -- says how the claim was arrived at; this says which actor recorded it, so
    -- a USER_STATED fact and a session's inference are told apart by origin
    -- rather than by trusting the confidence field alone.
    created_by_kind  TEXT NOT NULL,                       -- USER|SESSION|WORKER|RUNTIME
    created_by_id    TEXT NOT NULL,
    confidence       TEXT NOT NULL,                       -- OBSERVED|INFERRED|USER_STATED
    trust_level      TEXT NOT NULL DEFAULT 'UNTRUSTED',
    -- Session-scoped by default; PROJECT is a promotion only a user can make (D33).
    -- session_id stays populated after promotion: it records which session learned it.
    scope               TEXT NOT NULL DEFAULT 'SESSION'
                        CHECK (scope IN ('SESSION','PROJECT')),
    promoted_by_user_id TEXT,
    promoted_at         TEXT,
    created_at       TEXT NOT NULL,
    expires_at       TEXT,
    superseded_by    TEXT,
    FOREIGN KEY (session_id)    REFERENCES sessions(id),
    FOREIGN KEY (project_id)    REFERENCES projects(id),
    FOREIGN KEY (superseded_by) REFERENCES memory_entries(id),

    -- A session must not be able to grant its own conclusions project-wide authority
    -- (D6: sessions propose, only users resolve).
    CHECK (scope <> 'PROJECT' OR promoted_by_user_id IS NOT NULL),

    -- Task state is one session's current work; project-wide is meaningless for it.
    CHECK (kind <> 'TASK_STATE' OR scope = 'SESSION'),

    -- A promoted entry taints every future Run in the project that reads it. Promoting
    -- untrusted content would let one hostile file, read once, permanently degrade every
    -- later session — an unbounded version of exactly what D7 bounds. A user who wants
    -- the fact remembered states it themselves, which makes it USER_STATED and TRUSTED.
    CHECK (scope <> 'PROJECT' OR trust_level <> 'UNTRUSTED')
);

-- Promoted entries are read by every session in the project, so they are queried by
-- project rather than by session (D33).
CREATE INDEX idx_memory_promoted ON memory_entries(project_id)
    WHERE scope = 'PROJECT' AND superseded_by IS NULL;

CREATE INDEX idx_memory_active ON memory_entries(session_id, kind) WHERE superseded_by IS NULL;

-- ---------------------------------------------------------------------------
-- Budget and cost (RFC-0028)
-- ---------------------------------------------------------------------------

CREATE TABLE budget_ledger (
    id               TEXT PRIMARY KEY,
    scope            TEXT NOT NULL,                       -- run|session|project|capability
    scope_id         TEXT NOT NULL,
    period_start     TEXT,
    model_calls      INTEGER NOT NULL DEFAULT 0,
    input_tokens     INTEGER NOT NULL DEFAULT 0,
    output_tokens    INTEGER NOT NULL DEFAULT 0,
    cost_units       INTEGER NOT NULL DEFAULT 0,          -- integer micro-currency
    steps            INTEGER NOT NULL DEFAULT 0,
    tool_invocations INTEGER NOT NULL DEFAULT 0,
    limit_json       TEXT,                                -- NULL = unlimited
    updated_at       TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_budget_scope ON budget_ledger(scope, scope_id, period_start);

CREATE TABLE budget_reservations (
    id            TEXT PRIMARY KEY,
    attempt_id    TEXT NOT NULL,
    reserved_json TEXT NOT NULL,
    created_at    TEXT NOT NULL,
    FOREIGN KEY (attempt_id) REFERENCES attempts(id)
);

-- ---------------------------------------------------------------------------
-- Egress (RFC-0042)  — PERMANENT retention; evidence for the privacy claim
-- ---------------------------------------------------------------------------

CREATE TABLE egress_records (
    id                     TEXT PRIMARY KEY,
    project_id             TEXT,                          -- NULL for user-scope (model downloads)
    attempt_id             TEXT,
    capability_id          TEXT NOT NULL,
    destination_host       TEXT NOT NULL,
    resolved_address_class TEXT NOT NULL,                 -- PUBLIC|PRIVATE|LOOPBACK
    method                 TEXT NOT NULL,
    request_bytes          INTEGER NOT NULL,
    response_bytes         INTEGER NOT NULL,
    payload_hash           TEXT NOT NULL,                 -- hash, never content
    content_sensitivity    TEXT NOT NULL,
    run_taint              TEXT NOT NULL,
    duration_ms            INTEGER NOT NULL,
    outcome                TEXT NOT NULL,
    cost_units             INTEGER,
    occurred_at            TEXT NOT NULL,
    FOREIGN KEY (attempt_id) REFERENCES attempts(id)
);

CREATE INDEX idx_egress_host    ON egress_records(destination_host, occurred_at);
CREATE INDEX idx_egress_project ON egress_records(project_id, occurred_at);

-- ---------------------------------------------------------------------------
-- Scheduling and notifications (RFC-0044)
-- ---------------------------------------------------------------------------

CREATE TABLE scheduled_jobs (
    id                   TEXT PRIMARY KEY,
    project_id           TEXT NOT NULL,
    session_id           TEXT,
    name                 TEXT NOT NULL,
    trigger_json         TEXT NOT NULL,
    guarantee_class      TEXT NOT NULL,                   -- PROMPT|EVENTUAL|OPPORTUNISTIC
    work_class           TEXT NOT NULL,                   -- INTERACTIVE|DEFERRED|SCHEDULED|OPPORTUNISTIC
    constraints_json     TEXT NOT NULL DEFAULT '{}',
    enabled              INTEGER NOT NULL DEFAULT 1,
    next_run_at          TEXT,
    last_run_at          TEXT,
    last_outcome         TEXT,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    missed_occurrences   INTEGER NOT NULL DEFAULT 0,      -- coalesced, never replayed
    created_at           TEXT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (session_id) REFERENCES sessions(id)
);

CREATE INDEX idx_jobs_due ON scheduled_jobs(next_run_at) WHERE enabled = 1;

CREATE TABLE notifications (
    id              TEXT PRIMARY KEY,
    project_id      TEXT NOT NULL,
    category        TEXT NOT NULL,                        -- APPROVAL|COMPLETION|INFORMATIONAL
    title           TEXT NOT NULL,
    body            TEXT NOT NULL,                        -- redacted (RFC-0035)
    coalesced_count INTEGER NOT NULL DEFAULT 1,
    delivered_at    TEXT,
    acted_on_at     TEXT,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

-- ---------------------------------------------------------------------------
-- Git reconciliation (RFC-0053)
-- ---------------------------------------------------------------------------

CREATE TABLE repo_fingerprints (
    project_id       TEXT PRIMARY KEY,
    head_ref         TEXT NOT NULL,
    head_commit      TEXT NOT NULL,
    index_checksum   TEXT NOT NULL,
    dirty_path_count INTEGER NOT NULL,
    observed_at      TEXT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE TABLE reconciliations (
    id                TEXT PRIMARY KEY,
    project_id        TEXT NOT NULL,
    classification    TEXT NOT NULL,                      -- HEAD_MOVED|BRANCH_SWITCHED|HISTORY_REWRITTEN|INDEX_CHANGED|WORKTREE_DIRTIED
    from_commit       TEXT,
    to_commit         TEXT,
    nodes_invalidated INTEGER NOT NULL,
    nodes_dangling    INTEGER NOT NULL,
    runs_terminated   INTEGER NOT NULL,
    intent_conflicted INTEGER NOT NULL,
    performed_at      TEXT NOT NULL,
    audit_ref         TEXT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (audit_ref)  REFERENCES audit_log(id)
);

-- ---------------------------------------------------------------------------
-- Retention and storage lifecycle (RFC-0056)
-- ---------------------------------------------------------------------------

CREATE TABLE retention_policy (
    scope          TEXT NOT NULL,                         -- user|project
    scope_id       TEXT,
    object_class   TEXT NOT NULL,
    retention_days INTEGER,                               -- NULL = never compact
    PRIMARY KEY (scope, scope_id, object_class)
);

CREATE TABLE compactions (
    id              TEXT PRIMARY KEY,
    object_class    TEXT NOT NULL,
    object_id       TEXT NOT NULL,
    original_bytes  INTEGER NOT NULL,
    retained_bytes  INTEGER NOT NULL,
    original_sha256 TEXT NOT NULL,
    compacted_at    TEXT NOT NULL
);

CREATE INDEX idx_compactions_object ON compactions(object_class, object_id);

CREATE TABLE blob_refs (
    content_hash     TEXT PRIMARY KEY,
    ref_count        INTEGER NOT NULL,
    size_bytes       INTEGER NOT NULL,
    last_accessed_at TEXT NOT NULL
);

-- ---------------------------------------------------------------------------
-- State model, settings, observability, runtime (RFC-0017, 0036, 0037, 0045, 0055)
-- ---------------------------------------------------------------------------

CREATE TABLE pending_operations (
    id          TEXT PRIMARY KEY,
    object_type TEXT NOT NULL,                            -- artifact|resource|intent_snapshot
    object_id   TEXT NOT NULL,
    operation   TEXT NOT NULL,                            -- git_commit|filesystem_write|schema_init
    started_at  TEXT NOT NULL,
    details     TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0
);

-- Project- and session-scope settings. User/workspace scope lives in user.sql.
CREATE TABLE settings (
    scope       TEXT NOT NULL,                            -- project|session
    scope_id    TEXT,
    key         TEXT NOT NULL,
    value_json  TEXT NOT NULL,
    set_at      TEXT NOT NULL,
    set_by_kind TEXT NOT NULL,                            -- USER|RUNTIME
    PRIMARY KEY (scope, scope_id, key)
);

CREATE TABLE metric_samples (
    id           TEXT PRIMARY KEY,
    project_id   TEXT,
    name         TEXT NOT NULL,
    value        REAL NOT NULL,
    unit         TEXT NOT NULL,
    labels_json  TEXT NOT NULL DEFAULT '{}',
    bucket_start TEXT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE INDEX idx_metrics_name ON metric_samples(name, bucket_start);

CREATE TABLE degradation_events (
    id         TEXT PRIMARY KEY,
    rung       INTEGER NOT NULL,
    trigger    TEXT NOT NULL,
    entered_at TEXT NOT NULL,
    exited_at  TEXT,
    project_id TEXT,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE TABLE lock_breaks (
    id                    TEXT PRIMARY KEY,
    project_id            TEXT NOT NULL,
    previous_instance_id  TEXT NOT NULL,
    previous_heartbeat_at TEXT,
    broken_by_instance_id TEXT NOT NULL,
    broken_at             TEXT NOT NULL,
    audit_ref             TEXT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (audit_ref)  REFERENCES audit_log(id)
);

-- Secret resolution audit. Values never appear here (RFC-0035).
CREATE TABLE secret_accesses (
    id            TEXT PRIMARY KEY,
    secret_id     TEXT NOT NULL,
    consumer_kind TEXT NOT NULL,                          -- PROVIDER|MCP_SERVER|GIT_REMOTE
    consumer_id   TEXT NOT NULL,
    attempt_id    TEXT,
    capability_id TEXT NOT NULL,
    occurred_at   TEXT NOT NULL,
    FOREIGN KEY (attempt_id)    REFERENCES attempts(id),
    FOREIGN KEY (capability_id) REFERENCES capabilities(id)
);

-- Records that something was found, never what.
CREATE TABLE redaction_events (
    id              TEXT PRIMARY KEY,
    project_id      TEXT,
    boundary        TEXT NOT NULL,                        -- prompt|event|log|export|memory
    detection_kind  TEXT NOT NULL,                        -- known_value|pattern:jwt|...
    content_node_id TEXT,
    occurred_at     TEXT NOT NULL,
    FOREIGN KEY (project_id)      REFERENCES projects(id),
    FOREIGN KEY (content_node_id) REFERENCES content_nodes(id)
);
