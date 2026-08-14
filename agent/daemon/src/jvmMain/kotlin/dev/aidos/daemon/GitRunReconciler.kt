package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.executor.RunReconciler
import dev.aidos.git.Reconciliation
import dev.aidos.git.ReconciliationClassification
import dev.aidos.git.RepoFingerprint
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.kernel.StorageLocation
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import java.security.MessageDigest

/** The subset of a `content_nodes` row [GitRunReconciler] needs to reconcile it. */
private data class ContentNodeRow(
    val id: String, val storageLocationJson: String, val contentHash: String,
    val mutabilityPolicy: String, val kind: String, val name: String, val description: String?,
    val sensitivityLevel: String, val egressEligibility: String, val trustLevel: String,
    val contentType: String, val createdAt: String, val createdByKind: String, val createdById: String,
    val contentVersion: Int, val tags: String,
)

/**
 * The JVM composition of RFC-0053's reconciliation protocol (M13).
 *
 * Called by [dev.aidos.executor.SqliteExecutor.drive] immediately before a Run may start. Opens
 * the project's real repository with JGit, compares its fingerprint against what was last
 * recorded in `repo_fingerprints`, and — if they differ — performs the reconciliation RFC-0053's
 * table specifies: re-hashing (or `DANGLING`-marking) matching content nodes, terminating parked
 * Runs with `FAILED(repo.mutated)`, and recording the outcome in `reconciliations` before
 * returning which Runs must not proceed.
 *
 * **Scope (M13, matching RFC-0053's own MVP list):** fingerprinting before a Run starts,
 * content-node re-hashing/`DANGLING` marking, and terminating parked Runs. Deliberately not in
 * scope, matching the RFC's own "Not in MVP" line: filesystem watching of `.git` (Run-start
 * fingerprinting is the load-bearing detection point here, same as RFC-0053 says it is on
 * MOBILE), and the Intent Graph's three-way merge — `intent_conflicted` is always written as 0,
 * honestly, not computed, because RFC-0012's Intent Graph has no live writer to conflict with
 * yet (PIPELINE.md's own audit finding). "On project open" fingerprinting (the RFC's other MVP
 * detection point) is also not yet wired here — `RealRuntimeClient.projects.open()` lives in
 * `api`, which cannot depend on `git`/`executor` without a module cycle (the same constraint
 * `RunExecutor`'s own doc comment already names); flagged here rather than silently left out.
 */
class GitRunReconciler(
    private val idGen: () -> String = { UuidV7Generator().next() },
    private val nowIso: () -> String,
) : RunReconciler {

    private val json = Json { encodeDefaults = true }

    override suspend fun reconcileBeforeRun(driver: SqlDriver, projectId: ProjectId, runId: RunId): Set<RunId> {
        val rootPath = projectRootPath(driver, projectId.value) ?: return emptySet()
        val gitDir = File(rootPath, ".git")
        if (!gitDir.exists()) return emptySet() // not yet a real repository -- nothing to fingerprint

        val git = Git.open(File(rootPath))
        return git.use { g ->
            val now = nowIso()
            val current = Reconciliation.computeFingerprint(g, now)
            val stored = readFingerprint(driver, projectId.value)

            if (stored == null) {
                // First observation for this project -- establish the baseline, nothing to compare against.
                writeFingerprint(driver, projectId.value, current)
                return@use emptySet()
            }

            val classification = Reconciliation.classify(stored, current, g.repository)
            if (classification == null) {
                emptySet()
            } else {
                val (invalidated, dangling) = reconcileContentNodes(driver, projectId.value, g, rootPath, classification, now)
                val terminated = terminateParkedRuns(driver, projectId.value, now)

                val auditId = idGen()
                AuditLog(driver).write(
                    id = auditId, projectId = projectId.value, kind = "RepoMutatedExternally",
                    actorKind = "RUNTIME", actorId = "reconciler",
                    detailJson = "{\"classification\":\"${classification.name}\",\"fromCommit\":\"${stored.headCommit}\",\"toCommit\":\"${current.headCommit}\"}",
                    nowIso = now,
                )
                writeReconciliationContentNodeRow(
                    driver = driver, id = idGen(), projectId = projectId.value,
                    classification = classification, fromCommit = stored.headCommit, toCommit = current.headCommit,
                    nodesInvalidated = invalidated, nodesDangling = dangling,
                    runsTerminated = terminated.size, performedAt = now, auditRef = auditId,
                )
                writeFingerprint(driver, projectId.value, current)

                terminated
            }
        }
    }

    // ── repo_fingerprints ──────────────────────────────────────────────────────

    private fun projectRootPath(driver: SqlDriver, projectId: String): String? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT root_path FROM projects WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 1,
        ) { bindString(0, projectId) }.value

    private fun readFingerprint(driver: SqlDriver, projectId: String): RepoFingerprint? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT head_ref, head_commit, index_checksum, dirty_path_count, observed_at " +
                "FROM repo_fingerprints WHERE project_id = ?",
            mapper = { c ->
                QueryResult.Value(
                    if (c.next().value) RepoFingerprint(
                        headRef = c.getString(0)!!,
                        headCommit = c.getString(1)!!,
                        indexChecksum = c.getString(2)!!,
                        dirtyPathCount = c.getLong(3)!!.toInt(),
                        observedAt = c.getString(4)!!,
                    ) else null
                )
            },
            parameters = 1,
        ) { bindString(0, projectId) }.value

    private fun writeFingerprint(driver: SqlDriver, projectId: String, fp: RepoFingerprint) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO repo_fingerprints (project_id, head_ref, head_commit, index_checksum, dirty_path_count, observed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(project_id) DO UPDATE SET head_ref = excluded.head_ref, head_commit = excluded.head_commit, " +
                "index_checksum = excluded.index_checksum, dirty_path_count = excluded.dirty_path_count, observed_at = excluded.observed_at",
            parameters = 6,
        ) {
            bindString(0, projectId)
            bindString(1, fp.headRef)
            bindString(2, fp.headCommit)
            bindString(3, fp.indexChecksum)
            bindLong(4, fp.dirtyPathCount.toLong())
            bindString(5, fp.observedAt)
        }
    }

    // ── content_nodes (RFC-0053's per-object-class reconciliation table) ───────

    /** Returns (nodesInvalidated, nodesDangling). */
    private fun reconcileContentNodes(
        driver: SqlDriver, projectId: String, git: Git, rootPath: String,
        classification: ReconciliationClassification, now: String,
    ): Pair<Int, Int> {
        val rows = driver.executeQuery(
            identifier = null,
            sql = "SELECT id, storage_location_json, content_hash, mutability_policy, kind, name, description, " +
                "sensitivity_level, egress_eligibility, trust_level, content_type, created_at, created_by_kind, " +
                "created_by_id, content_version, tags FROM content_nodes WHERE project_id = ? AND state = 'ACTIVE'",
            mapper = { c ->
                val out = mutableListOf<ContentNodeRow>()
                while (c.next().value) {
                    out.add(ContentNodeRow(
                        id = c.getString(0)!!, storageLocationJson = c.getString(1)!!, contentHash = c.getString(2)!!,
                        mutabilityPolicy = c.getString(3)!!, kind = c.getString(4)!!, name = c.getString(5)!!,
                        description = c.getString(6), sensitivityLevel = c.getString(7)!!, egressEligibility = c.getString(8)!!,
                        trustLevel = c.getString(9)!!, contentType = c.getString(10)!!, createdAt = c.getString(11)!!,
                        createdByKind = c.getString(12)!!, createdById = c.getString(13)!!,
                        contentVersion = c.getLong(14)!!.toInt(), tags = c.getString(15)!!,
                    ))
                }
                QueryResult.Value(out)
            },
            parameters = 1,
        ) { bindString(0, projectId) }.value

        var invalidated = 0
        var dangling = 0

        for (row in rows) {
            val location = runCatching { json.decodeFromString<StorageLocation>(row.storageLocationJson) }.getOrNull() ?: continue
            when (location) {
                is StorageLocation.FilesystemPath -> {
                    if (!location.gitTracked) continue
                    val file = File(rootPath, location.relativePath)
                    val bytes = if (file.exists()) file.readBytes() else null
                    val currentHash = bytes?.let { sha256(it) }
                    if (currentHash == row.contentHash) continue // unchanged
                    invalidated++
                    if (currentHash == null || row.mutabilityPolicy == "IMMUTABLE") {
                        markDangling(driver, row.id, now)
                        dangling++
                    } else if (row.mutabilityPolicy == "VERSIONED") {
                        supersedeWithNewVersion(driver, projectId, row, currentHash, bytes.size.toLong(), now)
                    }
                    // APPEND_ONLY/MUTABLE_LATEST: content_hash is authoritative from Git (RFC-0017);
                    // update in place rather than versioning or dangling.
                    else {
                        driver.execute(
                            identifier = null,
                            sql = "UPDATE content_nodes SET content_hash = ?, size_bytes = ?, updated_at = ? WHERE id = ?",
                            parameters = 4,
                        ) { bindString(0, currentHash); bindLong(1, bytes.size.toLong()); bindString(2, now); bindString(3, row.id) }
                    }
                }
                is StorageLocation.GitObject -> {
                    if (classification != ReconciliationClassification.HISTORY_REWRITTEN &&
                        classification != ReconciliationClassification.BRANCH_SWITCHED
                    ) continue
                    if (!isReachable(git, location.commitHash)) {
                        invalidated++
                        markDangling(driver, row.id, now)
                        dangling++
                    }
                }
                is StorageLocation.SqliteBlob -> Unit // not Git-backed; nothing to reconcile here
            }
        }
        return invalidated to dangling
    }

    private fun markDangling(driver: SqlDriver, contentNodeId: String, now: String) {
        driver.execute(
            identifier = null,
            sql = "UPDATE content_nodes SET state = 'DANGLING', updated_at = ? WHERE id = ?",
            parameters = 2,
        ) { bindString(0, now); bindString(1, contentNodeId) }
    }

    /**
     * VERSIONED reconciliation (RFC-0053's content-node table): "create a new version" — the old
     * row becomes `SUPERSEDED` (`ContentNodeState`'s own doc comment: reachable only for
     * VERSIONED mutability), and a new row carries the re-hashed content forward.
     */
    private fun supersedeWithNewVersion(
        driver: SqlDriver, projectId: String, row: ContentNodeRow, newHash: String, newSizeBytes: Long, now: String,
    ) {
        driver.execute(
            identifier = null,
            sql = "UPDATE content_nodes SET state = 'SUPERSEDED', updated_at = ? WHERE id = ?",
            parameters = 2,
        ) { bindString(0, now); bindString(1, row.id) }

        driver.execute(
            identifier = null,
            sql = "INSERT INTO content_nodes " +
                "(id, project_id, kind, name, description, mutability_policy, sensitivity_level, " +
                "egress_eligibility, trust_level, storage_location_json, content_hash, content_type, " +
                "size_bytes, created_at, created_by_kind, created_by_id, updated_at, updated_by_kind, " +
                "updated_by_id, content_version, state, tags) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
            parameters = 21,
        ) {
            bindString(0, idGen())
            bindString(1, projectId)
            bindString(2, row.kind)
            bindString(3, row.name)
            bindString(4, row.description)
            bindString(5, row.mutabilityPolicy)
            bindString(6, row.sensitivityLevel)
            bindString(7, row.egressEligibility)
            bindString(8, row.trustLevel)
            bindString(9, row.storageLocationJson)
            bindString(10, newHash)
            bindString(11, row.contentType)
            bindLong(12, newSizeBytes)
            bindString(13, row.createdAt)
            bindString(14, row.createdByKind)
            bindString(15, row.createdById)
            bindString(16, now)
            bindString(17, "RUNTIME")
            bindString(18, "reconciler")
            bindLong(19, (row.contentVersion + 1).toLong())
            bindString(20, row.tags)
        }
    }

    private fun isReachable(git: Git, commitHash: String): Boolean {
        val repository = git.repository
        val head = repository.resolve("HEAD") ?: return false
        val target = repository.resolve(commitHash) ?: return false
        return RevWalk(repository).use { walk ->
            val headCommit = walk.parseCommit(head)
            val targetCommit = walk.parseCommit(target)
            targetCommit == headCommit || walk.isMergedInto(targetCommit, headCommit)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ── runs (parked-run termination) ───────────────────────────────────────────

    private fun terminateParkedRuns(driver: SqlDriver, projectId: String, now: String): Set<RunId> {
        val parked = driver.executeQuery(
            identifier = null,
            sql = "SELECT id FROM runs WHERE project_id = ? AND state IN ('INTERRUPTED', 'YIELDED')",
            mapper = { c ->
                val out = mutableListOf<String>()
                while (c.next().value) out.add(c.getString(0)!!)
                QueryResult.Value(out)
            },
            parameters = 1,
        ) { bindString(0, projectId) }.value

        for (id in parked) {
            driver.execute(
                identifier = null,
                sql = "UPDATE runs SET state = 'FAILED', ended_at = ?, error_code = ?, error_class = ?, " +
                    "error_detail_json = ? WHERE id = ?",
                parameters = 5,
            ) {
                bindString(0, now)
                bindString(1, "run.repo_mutated")
                bindString(2, "CONFLICT")
                bindString(3, "{\"message\":\"repository changed underneath this parked Run (RFC-0053)\"}")
                bindString(4, id)
            }
        }
        return parked.map { RunId(it) }.toSet()
    }

    // ── reconciliations ──────────────────────────────────────────────────────────

    private fun writeReconciliationContentNodeRow(
        driver: SqlDriver, id: String, projectId: String, classification: ReconciliationClassification,
        fromCommit: String, toCommit: String, nodesInvalidated: Int, nodesDangling: Int,
        runsTerminated: Int, performedAt: String, auditRef: String,
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO reconciliations " +
                "(id, project_id, classification, from_commit, to_commit, nodes_invalidated, nodes_dangling, " +
                "runs_terminated, intent_conflicted, performed_at, audit_ref) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            parameters = 11,
        ) {
            bindString(0, id)
            bindString(1, projectId)
            bindString(2, classification.name)
            bindString(3, fromCommit.ifEmpty { null })
            bindString(4, toCommit.ifEmpty { null })
            bindLong(5, nodesInvalidated.toLong())
            bindLong(6, nodesDangling.toLong())
            bindLong(7, runsTerminated.toLong())
            // Always 0 -- RFC-0012's Intent Graph has no live writer yet (see class doc comment).
            bindLong(8, 0L)
            bindString(9, performedAt)
            bindString(10, auditRef)
        }
    }
}
