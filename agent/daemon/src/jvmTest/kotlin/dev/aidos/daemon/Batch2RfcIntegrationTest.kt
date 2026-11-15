package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.JvmProjectLocker
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import dev.aidos.api.RealRuntimeClient
import dev.aidos.identity.ProjectRegistry
import dev.aidos.identity.UuidV7Generator
import dev.aidos.intent.IntentSnapshotCodec
import dev.aidos.kernel.ProjectId
import dev.aidos.lock.ProjectLock
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Batch2RfcIntegrationTest {

    @Test
    fun `ensureAidosGitignore adds dot-aidos once and preserves existing entries (RFC-0054)`() {
        val dir = Files.createTempDirectory("gitignore-test").toFile()
        try {
            File(dir, ".gitignore").writeText("build/\n*.log")
            RuntimeClientFactory.ensureAidosGitignore(dir.absolutePath)
            RuntimeClientFactory.ensureAidosGitignore(dir.absolutePath)

            val lines = File(dir, ".gitignore").readLines().map { it.trim() }
            assertEquals(listOf("build/", "*.log", ".aidos/"), lines)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `reconcileOnOpen detects external commit and marks divergent intent snapshot CONFLICTED (RFC-0053)`() = runTest {
        val dir = Files.createTempDirectory("reconcile-open-test").toFile()
        try {
            Git.init().setDirectory(dir).call().use { git ->
                git.repository.config.apply {
                    setString("user", null, "name", "Test")
                    setString("user", null, "email", "test@test.com")
                    setBoolean("commit", null, "gpgsign", false)
                    save()
                }
                File(dir, "README.md").writeText("# Init\n")
                git.add().addFilepattern("README.md").call()
                git.commit().setMessage("init").call()
            }

            val projectId = "01234567-89ab-7def-8abc-000000000553"
            val now = "2026-11-14T10:00:00Z"
            val driver = AidosStorage.openProject(DesktopPaths.stateDb(dir.absolutePath), "0.1.0", { now }).driver
            driver.use { d ->
                d.execute(
                    null,
                    "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                        "VALUES (?, 'test', ?, 'generic', ?, ?, ?)",
                    5,
                ) {
                    bindString(0, projectId)
                    bindString(1, dir.absolutePath)
                    bindString(2, now)
                    bindString(3, now)
                    bindString(4, now)
                }

                // Seed a local intent node at row_version = 1
                d.execute(
                    null,
                    "INSERT INTO intent_nodes (id, project_id, parent_id, type, title, description, priority, " +
                        "asserted_status, lifecycle, created_at, modified_at, created_by_kind, created_by_id, modified_by_kind, modified_by_id, row_version) " +
                        "VALUES ('node-1', ?, NULL, 'GOAL', 'Local Title', NULL, 0, 'NOT_STARTED', 'ACTIVE', ?, ?, 'USER', 'u1', 'USER', 'u1', 1)",
                    3,
                ) {
                    bindString(0, projectId)
                    bindString(1, now)
                    bindString(2, now)
                }

                val reconciler = GitRunReconciler(nowIso = { now })
                // First open establishes baseline fingerprint
                reconciler.reconcileOnOpen(d, ProjectId(projectId))

                // External git commit brings a divergent aidos-intent.json at row_version = 1
                val divergentSnapshot = IntentSnapshotCodec(d, { "2026-11-14T10:01:00Z" })
                    .exportJson(projectId)
                    .replace("Local Title", "Remote Divergent Title")
                File(dir, "aidos-intent.json").writeText(divergentSnapshot)
                Git.open(dir).use { git ->
                    git.add().addFilepattern("aidos-intent.json").call()
                    git.commit().setMessage("external intent change").call()
                }

                reconciler.reconcileOnOpen(d, ProjectId(projectId))

                val conflictedCount = d.executeQuery(
                    null,
                    "SELECT intent_conflicted FROM reconciliations WHERE project_id = ? ORDER BY performed_at DESC LIMIT 1",
                    { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) },
                    1,
                ) { bindString(0, projectId) }.value
                assertEquals(1L, conflictedCount)

                val nodeStatus = d.executeQuery(
                    null,
                    "SELECT asserted_status FROM intent_nodes WHERE id = 'node-1'",
                    { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
                    0,
                ).value
                assertEquals("CONFLICTED", nodeStatus)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `JvmProjectLocker updates heartbeat and releaseAll frees locks on shutdown (RFC-0055)`() {
        val dir = Files.createTempDirectory("locker-heartbeat-test").toFile()
        var tick = 0
        val base = Instant.parse("2026-11-14T10:00:00Z")
        val locker = JvmProjectLocker(
            heartbeatIntervalMillis = 40L,
            nowIso = { base.plusSeconds((tick++).toLong()).toString() },
        )
        try {
            val outcome = locker.tryAcquire("proj-hb", dir.absolutePath, "inst-hb")
            assertIs<dev.aidos.api.ProjectLockOutcome.Acquired>(outcome)

            val initialProbe = ProjectLock.tryAcquire(dir, "inst-probe")
            val initialHb = assertIs<dev.aidos.lock.AcquireResult.AlreadyHeld>(initialProbe).heartbeatAt
            Thread.sleep(160L)
            val updatedProbe = ProjectLock.tryAcquire(dir, "inst-probe")
            val updatedHb = assertIs<dev.aidos.lock.AcquireResult.AlreadyHeld>(updatedProbe).heartbeatAt
            assertNotEquals(initialHb, updatedHb, "heartbeat_at must advance while lock is held")

            locker.releaseAll()
            // After releaseAll(), another instance can acquire cleanly
            val reacquired = ProjectLock.tryAcquire(dir, "inst-other", nowIso = { base.plusSeconds(10).toString() })
            assertIs<dev.aidos.lock.AcquireResult.Acquired>(reacquired)
            reacquired.lock.release()
        } finally {
            locker.releaseAll()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `RealRuntimeClient writes lock_breaks and audit_log when breaking a stale lock on open (RFC-0055)`() = runTest {
        val home = Files.createTempDirectory("lock-break-home").toFile()
        val projDir = Files.createTempDirectory("lock-break-proj").toFile()
        try {
            val now = "2026-11-14T10:00:00Z"
            val userDb = AidosStorage.openUser(DesktopPaths.userDb(home.absolutePath), "0.1.0", { now })
            val locker = JvmProjectLocker(heartbeatIntervalMillis = 0L)
            val client = RealRuntimeClient().apply {
                userDriver = userDb.driver
                projectDbFactory = { root -> AidosStorage.openProject(DesktopPaths.stateDb(root), "0.1.0", { now }).driver }
                projectLocker = locker
                runtimeManagedProjectsRoot = File(home, ".aidos/projects").absolutePath
            }

            val created = client.projects.create(CreateProjectRequest("LockTest", "", ProjectLocation.LocalPath(projDir.absolutePath)))
            val proj = assertIs<ProjectResult.Success>(created).project
            client.shutdown()

            // Simulate a stale lock left by a crashed instance
            val lockFile = File(projDir, ProjectLock.LOCK_FILE_REL)
            lockFile.parentFile?.mkdirs()
            val staleAt = Instant.now().minusSeconds(ProjectLock.STALE_THRESHOLD_SECONDS + 120).toString()
            lockFile.writeText(
                "instance_id  = \"crashed-inst\"\n" +
                    "pid          = 99999\n" +
                    "acquired_at  = \"$staleAt\"\n" +
                    "heartbeat_at = \"$staleAt\"\n"
            )

            // New runtime client opens the project, breaking the stale lock
            val client2 = RealRuntimeClient().apply {
                userDriver = userDb.driver
                projectDbFactory = { root -> AidosStorage.openProject(DesktopPaths.stateDb(root), "0.1.0", { now }).driver }
                projectLocker = JvmProjectLocker(heartbeatIntervalMillis = 0L)
                runtimeManagedProjectsRoot = File(home, ".aidos/projects").absolutePath
            }
            val opened = client2.projects.open(proj.id)
            assertIs<ProjectResult.Success>(opened)

            val projectDriver = AidosStorage.openProject(DesktopPaths.stateDb(projDir.absolutePath), "0.1.0", { now }).driver
            val breakCount = projectDriver.executeQuery(
                null,
                "SELECT COUNT(*) FROM lock_breaks WHERE project_id = ? AND previous_instance_id = 'crashed-inst'",
                { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) },
                1,
            ) { bindString(0, proj.id) }.value
            assertEquals(1L, breakCount)

            val auditCount = projectDriver.executeQuery(
                null,
                "SELECT COUNT(*) FROM audit_log WHERE project_id = ? AND kind = 'LockBroken'",
                { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) },
                1,
            ) { bindString(0, proj.id) }.value
            assertEquals(1L, auditCount)

            projectDriver.close()
            client2.shutdown()
            userDb.driver.close()
        } finally {
            home.deleteRecursively()
            projDir.deleteRecursively()
        }
    }
}
