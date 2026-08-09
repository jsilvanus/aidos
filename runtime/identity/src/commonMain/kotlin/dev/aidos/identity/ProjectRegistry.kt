package dev.aidos.identity

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.AidosError
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.ErrorCodes
import dev.aidos.kernel.IdGenerator
import dev.aidos.kernel.ProjectId
import dev.aidos.settings.EgressPolicy
import dev.aidos.settings.SettingSetByKind
import dev.aidos.settings.Settings
import dev.aidos.settings.SettingsWriter
import kotlinx.serialization.json.JsonPrimitive

/**
 * Project types and their default settings (RFC-0047, M2 done-when).
 *
 * Types set **defaults**, never constraints. A user may always override a type default at
 * user scope.
 */
enum class ProjectType(val value: String) {
    PERSONAL("personal"),
    CODING("coding"),
    RESEARCH("research"),
    WRITING("writing"),
    PLANNING("planning"),
    GENERIC("generic"),
    ;

    companion object {
        fun fromValue(v: String): ProjectType =
            entries.firstOrNull { it.value == v } ?: GENERIC
    }
}

/**
 * Error: the registered path for a project no longer exists at that location (RFC-0010).
 *
 * Not a refusal — the project still exists; it moved. Re-registering with the new path
 * resolves it. The runtime never silently adopts a different directory.
 */
data class ProjectMovedError(
    val projectId: ProjectId,
    val registeredPath: String,
) {
    fun toAidosError(): AidosError = AidosError(
        code = "project.moved",
        errorClass = ErrorClass.CONFLICT,
        message = "Project '$projectId' was expected at '$registeredPath' but that directory does not exist. " +
            "Re-register the project with its new path.",
        detail = mapOf("project_id" to projectId.value, "registered_path" to registeredPath),
    )
}

/**
 * The user-scope project registry: maps project IDs to filesystem paths (RFC-0054, RFC-0010).
 *
 * This is a cache — the project directory is self-describing, and `root_path` in the
 * `projects` table is the authoritative location on the current device.
 */
class ProjectRegistry(
    /** Driver for user.db — holds the project_registry table. */
    private val userDriver: SqlDriver,
    private val ids: IdGenerator,
    private val fsExists: (String) -> Boolean = { java.io.File(it).exists() },
) {

    /**
     * Register a project at the given path. If a project is already registered at this path,
     * returns the existing registration. If a different project's path has changed to this
     * location, the registry is updated.
     */
    fun register(projectId: ProjectId, path: String, workspaceId: String? = null, nowIso: String) {
        userDriver.execute(
            identifier = null,
            sql = "INSERT INTO project_registry (project_id, path, workspace_id, last_opened_at) " +
                "VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(project_id) DO UPDATE SET path = excluded.path, last_opened_at = excluded.last_opened_at",
            parameters = 4,
        ) {
            bindString(0, projectId.value)
            bindString(1, path)
            bindString(2, workspaceId)
            bindString(3, nowIso)
        }
    }

    /**
     * Resolve a project by ID. Returns the registered path, or a [ProjectMovedError] if
     * the registered path no longer exists on disk.
     */
    fun resolveById(projectId: ProjectId): Result<String> {
        val path = userDriver.executeQuery(
            identifier = null,
            sql = "SELECT path FROM project_registry WHERE project_id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 1,
        ) { bindString(0, projectId.value) }.value ?: return Result.failure(
            AidosError(
                code = "project.not_found",
                errorClass = ErrorClass.DENIED,
                message = "No project registered with id '${projectId.value}'.",
            ).toException()
        )

        return if (fsExists(path)) Result.success(path)
        else Result.failure(ProjectMovedError(projectId, path).toAidosError().toException())
    }

    /**
     * Resolve a project by filesystem path.
     */
    fun resolveByPath(path: String): Result<ProjectId> {
        val id = userDriver.executeQuery(
            identifier = null,
            sql = "SELECT project_id FROM project_registry WHERE path = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 1,
        ) { bindString(0, path) }.value

        return if (id != null) Result.success(ProjectId(id))
        else Result.failure(
            AidosError(
                code = "project.not_found",
                errorClass = ErrorClass.DENIED,
                message = "No project registered at path '$path'.",
            ).toException()
        )
    }

    fun listAll(): List<Pair<ProjectId, String>> {
        val results = mutableListOf<Pair<ProjectId, String>>()
        val driver = userDriver
        driver.executeQuery(
            identifier = null,
            sql = "SELECT project_id, path FROM project_registry ORDER BY last_opened_at DESC",
            mapper = { cursor ->
                while (cursor.next().value) {
                    results.add(ProjectId(cursor.getString(0)!!) to cursor.getString(1)!!)
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return results
    }
}

/**
 * Apply project-type defaults to the settings store (RFC-0047, M2 done-when).
 *
 * Types set defaults, never constraints. A user override at user scope takes precedence.
 * This is called once at project creation and re-applied if the type changes.
 */
fun applyTypeDefaults(
    type: ProjectType,
    projectId: String,
    writer: SettingsWriter,
    nowIso: String,
) {
    when (type) {
        ProjectType.PERSONAL -> {
            // personal → routing.remote_egress = never (privacy-first, RFC-0047 MVP §1, M2
            // done-when). routing.remote_egress is SECURITY-classed (Settings.kt) and
            // SettingsWriter.writeProject() rejects SECURITY/SPEND keys outright (RFC-0036) — a
            // project-scope write here always failed and the default never took effect. Writing
            // to user scope is what RFC-0047's own MVP section describes ("the type default goes
            // to user scope at creation time, not to project scope").
            writer.writeUser(
                Settings.routingRemoteEgress,
                JsonPrimitive(EgressPolicy.NEVER.name),
                SettingSetByKind.RUNTIME,
                nowIso,
            )
            // Applying this at user scope means it affects every project for this user, not just
            // the personal one being created, and this call re-applies (clobbering any explicit
            // choice) every time a personal project is created or its type changes to PERSONAL —
            // see PIPELINE.md's outstanding-work notes for the unresolved "one-time suggestion,
            // not a standing override" question this still leaves.
        }
        ProjectType.CODING -> {
            // coding → trust.untrusted_paths has a useful default in Settings; no override needed.
            // A coding project opts in to knowing about test fixtures (already in the setting default).
        }
        else -> { /* no type-specific overrides for other types in MVP */ }
    }
}

private fun AidosError.toException(): Exception = RuntimeException(message)
