package dev.aidos.api

import app.cash.sqldelight.db.SqlDriver

/**
 * Seam for project archive (`CLOSED`) and FK-ordered delete (RFC-0002, RFC-0010).
 * Implemented in `daemon` over `executor`'s `ProjectLifecycle` so `api` does not depend on `executor`.
 */
interface ProjectLifecyclePort {
    suspend fun archiveProject(projectDriver: SqlDriver, projectId: String): Result<Unit>
    suspend fun deleteProject(projectDriver: SqlDriver, projectId: String): Result<Unit>
}
