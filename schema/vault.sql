-- Aidos secrets vault
-- Location: ~/.aidos/secrets/vault.db   (RFC-0035)
--
-- Separate database, separate file permissions. Values are ciphertext; the key
-- is held by the platform keystore and never written to disk by Aidos.
--
-- Nothing outside this file holds a secret value. Consumers hold a secret_ref.

PRAGMA foreign_keys = ON;

CREATE TABLE schema_versions (
    id              INTEGER PRIMARY KEY CHECK (id = 1),
    version         INTEGER NOT NULL,
    applied_at      TEXT    NOT NULL,
    runtime_version TEXT    NOT NULL
);

CREATE TABLE secrets (
    id                     TEXT PRIMARY KEY,
    name                   TEXT NOT NULL UNIQUE,
    kind                   TEXT NOT NULL,                 -- API_KEY|TOKEN|PASSWORD|SSH_KEY|GENERIC
    ciphertext             BLOB NOT NULL,
    nonce                  BLOB NOT NULL,
    allowed_consumers_json TEXT NOT NULL DEFAULT '[]',
    created_at             TEXT NOT NULL,
    expires_at             TEXT,
    last_used_at           TEXT,
    last_rotated_at        TEXT
);
