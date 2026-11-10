package dev.aidos.retention

import app.cash.sqldelight.db.SqlDriver
import java.io.File
import java.time.Instant

/**
 * RFC-0056 storage-pressure stages. Stage 2 (evict DERIVED data: knowledge index, then LRU model
 * weights) is outside the RFC's own MVP, and its model-weights half conflicts with Aidos Engine's
 * manual-only weight policy (RFC-0103); it is not implemented, so it has no value here.
 */
enum class PressureStage {
    /** Within limits. */
    NORMAL,

    /** Project footprint above the soft limit: compact AGED content older than 7 days. */
    STAGE_1,

    /** Device free space below the critical floor: stop starting new Runs. */
    STAGE_3,
}

/**
 * Measures the project's own footprint and the device's free space (RFC-0056 "Storage pressure on
 * mobile"). The footprint is the live part of `state.db` (pages in use, so space compaction freed
 * counts even before SQLite returns it to the filesystem) plus every stored blob.
 */
class StoragePressureMonitor(
    private val driver: SqlDriver,
    private val blobs: BlobStore?,
    /** Free bytes on the volume holding the project. */
    private val freeBytes: () -> Long,
) {
    fun footprintBytes(): Long {
        val pageSize = driver.queryLong("PRAGMA page_size")
        val pages = driver.queryLong("PRAGMA page_count") - driver.queryLong("PRAGMA freelist_count")
        return pageSize * pages + (blobs?.totalBytes() ?: 0L)
    }

    fun freeBytes(): Long = freeBytes.invoke()

    /** Stage 3 outranks stage 1: a full device is the more urgent condition. */
    fun assess(policy: RetentionPolicy): PressureStage = when {
        freeBytes() < policy.criticalFreeBytes -> PressureStage.STAGE_3
        footprintBytes() > policy.softLimitBytes -> PressureStage.STAGE_1
        else -> PressureStage.NORMAL
    }

    companion object {
        fun freeBytesOf(path: String): () -> Long = { File(path).usableSpace }
    }
}

/** What one maintenance pass did. */
data class MaintenanceReport(
    val stageBefore: PressureStage,
    val stageAfter: PressureStage,
    val compacted: Int,
    val bytesFreed: Long,
    val sweptFiles: Int,
    val needsAnotherPass: Boolean,
    val footprintBytes: Long,
)

/**
 * One maintenance pass for one project: EPHEMERAL cleanup (orphan blob files, interrupted
 * writes), compaction to the configured windows, then Stage 1 compaction if the footprint is
 * still above the soft limit. Stage 3 is not acted on here; it is an admission decision made when
 * a Run is about to start ([StoragePressureMonitor.assess] via the daemon's run admission).
 */
class RetentionService(
    private val driver: SqlDriver,
    private val blobs: BlobStore,
    private val resolver: RetentionPolicyResolver,
    private val monitor: StoragePressureMonitor,
    idGen: () -> String,
    private val clock: () -> Instant = { Instant.now() },
) {
    private val compactor = Compactor(driver, blobs, idGen)

    suspend fun maintain(
        projectId: String,
        snapshot: dev.aidos.settings.ProjectSettingsSnapshot? = null,
    ): MaintenanceReport {
        val policy = resolver.resolve(projectId, snapshot)
        val swept = blobs.sweep()
        val before = monitor.assess(policy)
        val now = clock()

        var compacted = 0
        var freed = 0L
        var more = false
        val regular = compactor.compact(projectId, policy, now)
        compacted += regular.compacted; freed += regular.bytesFreed; more = more || regular.needsAnotherPass

        if (monitor.footprintBytes() > policy.softLimitBytes) {
            val stage1 = compactor.compact(projectId, policy, now, maxAgeDays = policy.stage1AgeDays)
            compacted += stage1.compacted; freed += stage1.bytesFreed; more = more || stage1.needsAnotherPass
        }
        return MaintenanceReport(before, monitor.assess(policy), compacted, freed, swept, more, monitor.footprintBytes())
    }
}
