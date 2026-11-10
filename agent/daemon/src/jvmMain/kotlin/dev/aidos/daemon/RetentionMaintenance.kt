package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.retention.BlobStore
import dev.aidos.retention.MaintenanceReport
import dev.aidos.retention.PressureStage
import dev.aidos.retention.RetentionPolicy
import dev.aidos.retention.RetentionPolicyResolver
import dev.aidos.retention.RetentionService
import dev.aidos.retention.StoragePressureMonitor
import dev.aidos.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs RFC-0056 retention in the daemon: after a Run is driven, compaction and storage-pressure
 * maintenance run on a background dispatcher (RFC-0007's `background` class), never on the
 * caller's own call, so session work is not blocked.
 *
 * At most one pass per project at a time, and at most one per [minIntervalMs] per project (the
 * first pass after the daemon starts is immediate: that is also the startup cleanup of orphan
 * blob files and interrupted writes). A pass that fails is reported to stderr and never affects a
 * Run. [maintainNow] is the same work, awaited, for callers and tests that want the report.
 */
class RetentionMaintenance(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val minIntervalMs: Long = 60L * 60 * 1000,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val freeBytesOf: (String) -> (() -> Long) = StoragePressureMonitor::freeBytesOf,
    private val onReport: (projectId: String, MaintenanceReport) -> Unit = { _, _ -> },
) {
    private val lastStart = ConcurrentHashMap<String, Long>()
    private val active = ConcurrentHashMap<String, Job>()

    /** Starts a background pass unless one ran recently or is running. Returns its job, or null. */
    fun schedule(
        projectDriver: SqlDriver,
        projectId: String,
        rootPath: String,
        userDriver: SqlDriver?,
        idGen: () -> String,
        nowIso: () -> String,
    ): Job? {
        val now = clockMs()
        val last = lastStart[projectId]
        if (last != null && now - last < minIntervalMs) return null
        if (active[projectId]?.isActive == true) return null
        lastStart[projectId] = now
        val job = scope.launch {
            try {
                onReport(projectId, maintainNow(projectDriver, projectId, rootPath, userDriver, idGen, nowIso))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DaemonLog.err("aidos: retention pass for project $projectId failed: ${e.message}")
            }
        }
        active[projectId] = job
        return job
    }

    suspend fun maintainNow(
        projectDriver: SqlDriver,
        projectId: String,
        rootPath: String,
        userDriver: SqlDriver?,
        idGen: () -> String,
        nowIso: () -> String,
    ): MaintenanceReport {
        val blobs = BlobStore(projectDriver, File(rootPath, BLOBS_DIR), nowIso)
        // Without a user.db the project db stands in for it: it holds no `user`-scope rows, so
        // resolution falls through to project rows and declared defaults.
        val settings = SettingsStore(userDriver ?: projectDriver, projectDriver)
        return RetentionService(
            driver = projectDriver,
            blobs = blobs,
            resolver = RetentionPolicyResolver(settings, projectDriver),
            monitor = StoragePressureMonitor(projectDriver, blobs, freeBytesOf(rootPath)),
            idGen = idGen,
        ).maintain(projectId)
    }

    companion object {
        /** RFC-0056: blob storage is content-addressed under `.aidos/blobs/<hash>`. */
        const val BLOBS_DIR = ".aidos/blobs"
    }
}

/**
 * RFC-0056 Stage 3 admission: decides whether a new Run may start. "Stage 3 stops rather than
 * degrades because a Run that fails midway through writing is worse than a Run that never
 * started" — so below [criticalFreeBytes] of free space on the project's volume, the answer is a
 * refusal the user is told about, not a Run that starts and may die.
 */
fun interface RunAdmission {
    /** A user-facing refusal message, or null to admit the Run. */
    fun refusal(projectDriver: SqlDriver, projectId: String): String?
}

class StoragePressureAdmission(
    private val freeBytesOf: (String) -> (() -> Long) = StoragePressureMonitor::freeBytesOf,
    private val criticalFreeBytes: Long = RetentionPolicy().criticalFreeBytes,
) : RunAdmission {
    override fun refusal(projectDriver: SqlDriver, projectId: String): String? {
        val root = projectDriver.executeQuery(
            identifier = null,
            sql = "SELECT root_path FROM projects WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 1,
        ) { bindString(0, projectId) }.value ?: return null
        val free = freeBytesOf(root)()
        if (free >= criticalFreeBytes) return null
        val mb = RetentionPolicy.MB
        return "Storage is critically low: ${free / mb} MB free where this project lives, and Aidos does not " +
            "start new Runs below ${criticalFreeBytes / mb} MB (RFC-0056). Free some space, or export and " +
            "archive projects you are not using, then try again."
    }

    companion object {
        val STAGE = PressureStage.STAGE_3
    }
}
