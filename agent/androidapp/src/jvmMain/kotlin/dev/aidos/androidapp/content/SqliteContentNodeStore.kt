package dev.aidos.androidapp.content

/**
 * Canonical [SqliteContentNodeStore] lives in `dev.aidos.storage` (RFC-0024) so it can be wired
 * into runtime composition (`agent/daemon`, `agent/prompt`, `agent/executor`) as well as Android.
 */
typealias SqliteContentNodeStore = dev.aidos.storage.SqliteContentNodeStore
