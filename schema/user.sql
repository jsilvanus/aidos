-- Aidos user-scope schema
-- Location: ~/.aidos/user.db   (RFC-0054)
--
-- Holds what is about the person and the device rather than any project:
-- workspaces, the project registry, user and workspace settings, the model
-- catalogue, installed extensions, and device identity.
--
-- Secrets are NOT here — they are in vault.sql (RFC-0035).

PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;

CREATE TABLE schema_versions (
    id              INTEGER PRIMARY KEY CHECK (id = 1),
    version         INTEGER NOT NULL,
    applied_at      TEXT    NOT NULL,
    runtime_version TEXT    NOT NULL
);

-- RFC-0040: "each database versions independently" -- user.db migrates on its own schedule from
-- project.db, so it needs its own migration history rather than sharing project.sql's.
CREATE TABLE migration_history (
    version         INTEGER PRIMARY KEY,
    applied_at      TEXT    NOT NULL,
    runtime_version TEXT    NOT NULL,
    duration_ms     INTEGER NOT NULL
);

-- Device identity: local, self-assigned, never transmitted (RFC-0046).
CREATE TABLE device_identity (
    id               INTEGER PRIMARY KEY CHECK (id = 1),
    device_id        TEXT NOT NULL,
    display_name     TEXT NOT NULL,
    platform_profile TEXT NOT NULL,                       -- MOBILE|DESKTOP|HEADLESS_SERVER
    created_at       TEXT NOT NULL
);

CREATE TABLE workspaces (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    created_at TEXT NOT NULL
);

-- A cache. A project directory is self-describing; a moved project
-- re-registers by ID on next open (RFC-0054).
CREATE TABLE project_registry (
    project_id     TEXT PRIMARY KEY,
    path           TEXT NOT NULL,
    workspace_id   TEXT,
    last_opened_at TEXT,
    FOREIGN KEY (workspace_id) REFERENCES workspaces(id)
);

-- User and workspace scope only. Project/session settings are in project.sql.
-- SECURITY and SPEND settings may only exist here (RFC-0036).
CREATE TABLE settings (
    scope       TEXT NOT NULL,                            -- user|workspace
    scope_id    TEXT,                                     -- NULL for user
    key         TEXT NOT NULL,
    value_json  TEXT NOT NULL,
    set_at      TEXT NOT NULL,
    set_by_kind TEXT NOT NULL,                            -- USER|RUNTIME
    PRIMARY KEY (scope, scope_id, key)
);

-- Model catalogue and installed weights. Device-global: multi-gigabyte, and
-- one loaded instance can saturate a phone (RFC-0020, RFC-0054).
-- ---------------------------------------------------------------------------
-- Instruction recognition (RFC-0016)
--
-- "Have I read this text before?" — nothing more. Adoption itself is project
-- scope (instruction_adoptions in project.sql): what the user *knows* is user
-- scope, what the user *permitted* is project scope. A hash present here still
-- requires an adoption row in the project before any text reaches a prompt.
-- ---------------------------------------------------------------------------

CREATE TABLE known_instruction_sets (
    set_hash          TEXT PRIMARY KEY,
    first_seen_at     TEXT NOT NULL,
    last_accepted_at  TEXT NOT NULL,
    accepted_count    INTEGER NOT NULL DEFAULT 1,
    first_accepted_in TEXT                                 -- project name, display only
) WITHOUT ROWID;

CREATE TABLE model_catalog (
    id                TEXT PRIMARY KEY,
    name              TEXT NOT NULL,
    model_kind        TEXT NOT NULL,                      -- LLM|EMBEDDING|STT|TTS|VISION|OCR|RERANKER|TRANSLATION
    provider          TEXT NOT NULL,
    remote_url        TEXT,
    properties_json   TEXT NOT NULL DEFAULT '{}',
    discovered_at     TEXT NOT NULL
);

CREATE TABLE installed_models (
    model_id         TEXT PRIMARY KEY,
    digest           TEXT NOT NULL,                       -- content-addressed
    path             TEXT NOT NULL,
    size_bytes       INTEGER NOT NULL,
    quantization     TEXT,
    installed_at     TEXT NOT NULL,
    last_loaded_at   TEXT,
    FOREIGN KEY (model_id) REFERENCES model_catalog(id)
);

-- MCP servers are registered here and merely *requested* by projects (RFC-0031).
CREATE TABLE mcp_servers (
    name             TEXT PRIMARY KEY,
    transport        TEXT NOT NULL CHECK (transport IN ('stdio','http')),
    command          TEXT,                                -- stdio only
    args_json        TEXT NOT NULL DEFAULT '[]',          -- stdio only
    endpoint_url     TEXT,                                -- http only; https required (RFC-0031)
    profiles_json    TEXT NOT NULL,                       -- where it is available (RFC-0049)
    secret_refs_json TEXT NOT NULL DEFAULT '{}',          -- stdio: env var -> secret id.
                                                          -- http:  header  -> secret id.
                                                          -- never values (RFC-0035).
    -- No trust column. A server's results are UNTRUSTED permanently (RFC-0027) and its
    -- authority is the capability rows it holds in a project (D30). The removed
    -- UNVERIFIED/TRUSTED promotion put the word "trusted" on a permanently untrusted process.
    auto_restart     INTEGER NOT NULL DEFAULT 1,
    registered_at    TEXT NOT NULL,

    -- Each transport needs exactly its own half. An http row with a `command`
    -- would be a spawnable registration reachable over the network (D17 amended).
    CHECK (transport <> 'stdio' OR (command      IS NOT NULL AND endpoint_url IS NULL)),
    CHECK (transport <> 'http'  OR (endpoint_url IS NOT NULL AND command      IS NULL))
);

-- Reserved. No plugin host in v1 (RFC-0043).
CREATE TABLE installed_plugins (
    id                       TEXT PRIMARY KEY,
    version                  TEXT NOT NULL,
    manifest_json            TEXT NOT NULL,
    wasm_module_hash         TEXT NOT NULL,
    publisher_key_fingerprint TEXT,
    signature                TEXT,
    installed_at             TEXT NOT NULL,
    enabled_projects_json    TEXT NOT NULL DEFAULT '[]'
);

-- User-scope diagnostics (RFC-0037). Never transmitted.
CREATE TABLE crash_records (
    id                TEXT PRIMARY KEY,
    occurred_at       TEXT NOT NULL,
    runtime_version   TEXT NOT NULL,
    platform_profile  TEXT NOT NULL,
    error_code        TEXT,
    stack_hash        TEXT NOT NULL,
    detail_path       TEXT NOT NULL,
    in_flight_run_ids TEXT NOT NULL DEFAULT '[]',
    reported          INTEGER NOT NULL DEFAULT 0
);

-- Device-wide resource limits (RFC-0045).
CREATE TABLE resource_budgets (
    scope    TEXT NOT NULL,                               -- user|project
    scope_id TEXT,
    key      TEXT NOT NULL,                               -- memory_mb|battery_floor_pct|...
    value    INTEGER NOT NULL,
    PRIMARY KEY (scope, scope_id, key)
);
