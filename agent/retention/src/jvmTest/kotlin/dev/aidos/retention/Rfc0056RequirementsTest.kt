package dev.aidos.retention

import dev.aidos.retention.RetentionFixture.Companion.NOW
import dev.aidos.retention.RetentionFixture.Companion.big
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC-0056 requirement-by-requirement tests against a real project database (the canonical
 * `schema/project.sql`). The mapping from requirement to test is the table in the RFC's header.
 */
class Rfc0056RequirementsTest {

    private val policy = RetentionPolicy()

    // ─── MVP 1: retention classes and the default policy table ──────────────────────────────

    @Test
    fun `R1 the default policy table classifies objects as the RFC says`() {
        assertEquals(RetentionClass.PERMANENT, RetainedObject.AUDIT_LOG.retentionClass)
        assertEquals(RetentionClass.PERMANENT, RetainedObject.CONTENT_NODE_RECORD.retentionClass)
        assertEquals(RetentionClass.PERMANENT, RetainedObject.PROVENANCE_EDGES.retentionClass)
        assertEquals(RetentionClass.PERMANENT, RetainedObject.CAPABILITY_HISTORY.retentionClass)
        assertEquals(RetentionClass.DURABLE, RetainedObject.KEPT_ARTIFACT_CONTENT.retentionClass)
        assertEquals(RetentionClass.DURABLE, RetainedObject.GIT_OBJECTS.retentionClass)
        assertEquals(RetentionClass.DERIVED, RetainedObject.KNOWLEDGE_INDEX.retentionClass)
        assertEquals(RetentionClass.EPHEMERAL, RetainedObject.BUDGET_RESERVATION.retentionClass)
        assertEquals(
            setOf("attempt_input_snapshot", "attempt_output_snapshot", "prompt_package", "event_payload"),
            RetainedObject.aged.map { it.key }.toSet(),
            "only AGED objects are ever compacted",
        )
    }

    @Test
    fun `R1 default windows are 30 days, 7 days over 64KB, 512MB soft limit and 250MB critical floor`() {
        assertEquals(30, policy.agedDays)
        assertEquals(7, policy.toolResultDays)
        assertEquals(64 * 1024, policy.toolResultThresholdBytes)
        assertEquals(512L * 1024 * 1024, policy.softLimitBytes)
        assertEquals(250L * 1024 * 1024, policy.criticalFreeBytes)
        assertEquals(7, policy.stage1AgeDays)
    }

    // ─── MVP 2 and 5: compaction with head/tail/hash, audited ───────────────────────────────

    @Test
    fun `R2 an old attempt snapshot, prompt package and event payload are compacted to head, tail and hash`() = runTest {
        val f = RetentionFixture()
        val (_, task) = f.run()
        val out = big(20_000, "out"); val inp = big(9_000, "in"); val prm = big(12_000, "prm")
        val att = f.attempt(task, daysOld = 45, output = out, input = inp, prompt = prm)
        val evt = f.event(daysOld = 45, payload = big(8_000, "evt"))

        val r = f.compactor().compact(f.projectId, policy, NOW)

        assertEquals(4, r.compacted)
        for ((col, original) in listOf("output_snapshot" to out, "input_snapshot" to inp, "prompt_package_json" to prm)) {
            val c = Json.parseToJsonElement(f.column("attempts", col, att)!!).jsonObject
            assertEquals(original.toByteArray().size.toLong(), c["original_bytes"]!!.jsonPrimitive.content.toLong())
            assertEquals(BlobStore.sha256Hex(original.toByteArray()), c["original_sha256"]!!.jsonPrimitive.content)
            assertEquals(original.take(2048), c["head"]!!.jsonPrimitive.content)
            assertEquals(original.takeLast(2048), c["tail"]!!.jsonPrimitive.content)
            assertTrue(c["summary"]!!.jsonPrimitive.content.contains("lines"))
            assertTrue(c.containsKey("compacted_at"))
        }
        assertTrue(f.column("events", "payload", evt)!!.startsWith(Compactor.COMPACTED_PREFIX))
        assertTrue(r.bytesFreed > 25_000)
    }

    @Test
    fun `R2 event headers survive compaction, only the payload changes`() = runTest {
        val f = RetentionFixture()
        val evt = f.event(daysOld = 60, payload = big(10_000))
        val before = f.query("SELECT type, sequence, timestamp, source, topic, category FROM events WHERE id = ?", evt) {
            (0..5).map { i -> it.getString(i) ?: it.getLong(i).toString() }
        }.single()

        f.compactor().compact(f.projectId, policy, NOW)

        val after = f.query("SELECT type, sequence, timestamp, source, topic, category FROM events WHERE id = ?", evt) {
            (0..5).map { i -> it.getString(i) ?: it.getLong(i).toString() }
        }.single()
        assertEquals(before, after)
    }

    @Test
    fun `R5 every compaction writes a compactions row with the original hash and sizes`() = runTest {
        val f = RetentionFixture()
        val (_, task) = f.run()
        val out = big(15_000)
        val att = f.attempt(task, daysOld = 40, output = out)

        f.compactor().compact(f.projectId, policy, NOW)

        val rows = f.query("SELECT object_class, object_id, original_bytes, retained_bytes, original_sha256, compacted_at FROM compactions") {
            listOf(it.getString(0)!!, it.getString(1)!!, it.getLong(2)!!, it.getLong(3)!!, it.getString(4)!!, it.getString(5)!!)
        }
        val row = rows.single { it[1] == att }
        assertEquals("attempt_output_snapshot", row[0])
        assertEquals(out.toByteArray().size.toLong(), row[2])
        assertTrue((row[3] as Long) < (row[2] as Long))
        assertEquals(BlobStore.sha256Hex(out.toByteArray()), row[4])
        assertEquals(NOW.toString(), row[5])
    }

    @Test
    fun `R2 compaction is idempotent`() = runTest {
        val f = RetentionFixture()
        val (_, task) = f.run()
        f.attempt(task, daysOld = 40, output = big(15_000))

        val first = f.compactor().compact(f.projectId, policy, NOW)
        val second = f.compactor().compact(f.projectId, policy, NOW)

        assertTrue(first.compacted > 0)
        assertEquals(0, second.compacted)
        assertEquals(first.compacted.toLong(), f.count("compactions"))
    }

    @Test
    fun `R2 a payload too small to shrink is left alone, and young payloads are not touched`() = runTest {
        val f = RetentionFixture()
        val (_, task) = f.run()
        val small = f.attempt(task, daysOld = 90, output = "{\"text\":\"short\"}")
        val (_, task2) = f.run(daysOld = 5)
        val young = f.attempt(task2, daysOld = 5, output = big(30_000))
        val twentyNineDays = f.attempt(f.run(daysOld = 29).second, daysOld = 29, input = big(30_000))

        val r = f.compactor().compact(f.projectId, policy, NOW)

        assertEquals("{\"text\":\"short\"}", f.column("attempts", "output_snapshot", small))
        assertFalse(f.column("attempts", "output_snapshot", young)!!.startsWith("{\"compacted_at"))
        assertFalse(f.column("attempts", "input_snapshot", twentyNineDays)!!.startsWith("{\"compacted_at"))
        assertEquals(0, r.compacted)
    }

    @Test
    fun `R2 a tool result over 64KB is compacted after 7 days, a smaller one waits 30`() = runTest {
        val f = RetentionFixture()
        val hugeTask = f.run(daysOld = 10).second
        val huge = f.attempt(hugeTask, daysOld = 10, output = big(80 * 1024))
        val mediumTask = f.run(daysOld = 10).second
        val medium = f.attempt(mediumTask, daysOld = 10, output = big(30 * 1024))

        f.compactor().compact(f.projectId, policy, NOW)

        assertTrue(f.column("attempts", "output_snapshot", huge)!!.startsWith("{\"compacted_at"))
        assertFalse(f.column("attempts", "output_snapshot", medium)!!.startsWith("{\"compacted_at"))
    }

    @Test
    fun `R2 head and tail never split a surrogate pair`() {
        val s = "a".repeat(2047) + "😀" + "b".repeat(5000) + "😀" + "c".repeat(2047)
        val json = Json.parseToJsonElement(Compactor.compactedJson(s, s.length.toLong(), "h", NOW)).jsonObject
        val head = json["head"]!!.jsonPrimitive.content
        val tail = json["tail"]!!.jsonPrimitive.content
        assertFalse(head.last().isHighSurrogate())
        assertFalse(tail.first().isLowSurrogate())
    }

    // ─── Never-delete rules ─────────────────────────────────────────────────────────────────

    @Test
    fun `R3 compaction never touches PERMANENT records, only payload columns`() = runTest {
        val f = RetentionFixture()
        val (run, task) = f.run()
        f.attempt(task, daysOld = 90, output = big(30_000))
        f.exec("INSERT INTO content_nodes (id, project_id, kind, name, mutability_policy, sensitivity_level, egress_eligibility, storage_location_json, content_hash, content_type, size_bytes, created_at, created_by_kind, created_by_id, updated_at) " +
            "VALUES ('cn1', ?, 'FILE', 'a.txt', 'MUTABLE_LATEST', 'INTERNAL', 'ELIGIBLE', '{}', 'h', 'text/plain', 5, ?, 'USER', 'u', ?)", f.projectId, f.daysAgo(400), f.daysAgo(400))
        val auditBefore = f.query("SELECT id, kind, detail_json FROM audit_log ORDER BY id") { listOf(it.getString(0), it.getString(1), it.getString(2)) }
        val runBefore = f.query("SELECT id, state, started_at, ended_at FROM runs WHERE id = ?", run) { listOf(it.getString(0), it.getString(1), it.getString(2), it.getString(3)) }

        f.compactor().compact(f.projectId, policy, NOW)

        assertEquals(auditBefore, f.query("SELECT id, kind, detail_json FROM audit_log ORDER BY id") { listOf(it.getString(0), it.getString(1), it.getString(2)) })
        assertEquals(runBefore, f.query("SELECT id, state, started_at, ended_at FROM runs WHERE id = ?", run) { listOf(it.getString(0), it.getString(1), it.getString(2), it.getString(3)) })
        assertEquals("ACTIVE", f.column("content_nodes", "state", "cn1"), "content node records are never soft-deleted (the old engine did)")
        assertEquals(1, f.count("tasks"))
    }

    @Test
    fun `R3 attempts of a Run that is still live, and its trigger event, are never compacted`() = runTest {
        val f = RetentionFixture()
        val liveTrigger = f.event(daysOld = 80, payload = big(20_000))
        val (_, liveTask) = f.run(state = "YIELDED", triggerEventId = liveTrigger)
        val liveAttempt = f.attempt(liveTask, daysOld = 80, output = big(20_000))
        val runningAttemptTask = f.run(state = "COMPLETED").second
        val running = f.attempt(runningAttemptTask, daysOld = 80, output = big(20_000), state = "RUNNING")

        val r = f.compactor().compact(f.projectId, policy, NOW)

        assertFalse(f.column("attempts", "output_snapshot", liveAttempt)!!.startsWith("{\"compacted_at"))
        assertFalse(f.column("events", "payload", liveTrigger)!!.startsWith("{\"compacted_at"))
        assertFalse(f.column("attempts", "output_snapshot", running)!!.startsWith("{\"compacted_at"))
        assertEquals(0, r.compacted)
    }

    @Test
    fun `R3 never_compact for the project, or a null per-object window, turns compaction off`() = runTest {
        val f = RetentionFixture()
        val (_, task) = f.run()
        val att = f.attempt(task, daysOld = 90, output = big(30_000), input = big(30_000))

        assertEquals(0, f.compactor().compact(f.projectId, policy.copy(neverCompact = true), NOW).compacted)
        val onlyOutput = f.compactor().compact(
            f.projectId, policy.copy(perObjectDays = mapOf("attempt_input_snapshot" to null)), NOW,
        )

        assertEquals(1, onlyOutput.compacted, "the input snapshot is exempt, the output is not")
        assertFalse(f.column("attempts", "input_snapshot", att)!!.startsWith("{\"compacted_at"))
        assertTrue(f.column("attempts", "output_snapshot", att)!!.startsWith("{\"compacted_at"))
    }

    // ─── Bounded, interruptible ─────────────────────────────────────────────────────────────

    @Test
    fun `R4 a pass is bounded by batches and reports that it needs another pass, which finishes the job`() = runTest {
        val f = RetentionFixture()
        repeat(7) { f.attempt(f.run().second, daysOld = 90, output = big(6_000, "n$it")) }
        val small = policy.copy(batchSize = 2, maxBatchesPerPass = 2)

        val first = f.compactor().compact(f.projectId, small, NOW)
        assertEquals(4, first.compacted)
        assertTrue(first.needsAnotherPass)

        var passes = 1
        while (true) {
            val r = f.compactor().compact(f.projectId, small, NOW)
            passes++
            if (!r.needsAnotherPass) break
            assertTrue(passes < 10)
        }
        assertEquals(7L, f.count("compactions", "object_class = 'attempt_output_snapshot'"))
    }

    @Test
    fun `R4 cancelling mid-pass loses at most the in-flight batch and the next pass completes`() = runTest {
        val f = RetentionFixture()
        repeat(6) { f.attempt(f.run().second, daysOld = 90, output = big(6_000, "c$it")) }
        val small = policy.copy(batchSize = 3)

        // The test dispatcher is FIFO: the pass starts, reaches its first yield() (planning the
        // first batch), and the second coroutine's cancel lands before the pass resumes.
        val job = launch { f.compactor().compact(f.projectId, small, NOW) }
        launch { job.cancel(CancellationException("execution window closed")) }
        testScheduler.advanceUntilIdle()
        job.join()
        assertTrue(job.isCancelled)

        // Nothing half-applied: the batch was planned but never committed.
        assertEquals(0L, f.count("compactions"))
        assertEquals(0L, f.count("attempts", "output_snapshot LIKE '${Compactor.COMPACTED_PREFIX}%'"))

        val done = f.compactor().compact(f.projectId, small, NOW)
        assertEquals(6L, f.count("compactions", "object_class = 'attempt_output_snapshot'"))
        assertEquals(6, done.compacted)
    }

    // ─── MVP 3: content-addressed blob storage with reference counting ──────────────────────

    @Test
    fun `R6 identical content is stored once and reference counted`() {
        val f = RetentionFixture()
        val a = f.blobs.put("the same file".toByteArray())
        val b = f.blobs.put("the same file".toByteArray())
        val c = f.blobs.put("another file".toByteArray())

        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(2, f.blobDir.listFiles()!!.size)
        assertEquals(2, f.blobs.refCount(a))
        assertEquals(1, f.blobs.refCount(c))
        assertEquals("the same file".length.toLong() + "another file".length, f.blobs.totalBytes())
        assertEquals("the same file", f.blobs.get(a)!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `R6 the file is deleted when the last reference is released, not before`() {
        val f = RetentionFixture()
        val h = f.blobs.put("payload".toByteArray()).also { f.blobs.put("payload".toByteArray()) }

        f.blobs.release(h)
        assertTrue(java.io.File(f.blobDir, h).isFile)
        assertEquals(1, f.blobs.refCount(h))

        f.blobs.release(h)
        assertFalse(java.io.File(f.blobDir, h).exists())
        assertEquals(0L, f.count("blob_refs"))
    }

    @Test
    fun `R6 a corrupted blob is never returned`() {
        val f = RetentionFixture()
        val h = f.blobs.put("original".toByteArray())
        java.io.File(f.blobDir, h).writeText("tampered")
        assertNull(f.blobs.get(h))
    }

    @Test
    fun `R6 startup sweep removes interrupted writes and uncounted files but keeps referenced blobs`() {
        val f = RetentionFixture()
        val kept = f.blobs.put("kept".toByteArray())
        f.blobDir.mkdirs()
        java.io.File(f.blobDir, "blob-123.tmp").writeText("partial")
        java.io.File(f.blobDir, "a".repeat(64)).writeText("orphan from a crash between file and row")

        assertEquals(2, f.blobs.sweep())
        assertEquals(listOf(kept), f.blobDir.listFiles()!!.map { it.name })
    }

    @Test
    fun `R6 large outputs are externalized, small ones stay inline, and resolve undoes it`() {
        val f = RetentionFixture()
        val store = BlobStore(f.driver, f.blobDir, { NOW.toString() }, inlineThresholdBytes = 1000)

        assertNull(store.externalize("small"))
        val big = big(5000)
        val ref = store.externalize(big)!!
        assertTrue(BlobStore.parseReference(ref) != null)
        assertEquals(big, store.resolve(ref))
        assertEquals("not a reference", store.resolve("not a reference"))
        assertEquals(1, store.refCount(BlobStore.parseReference(ref)!!.first))
        store.externalize(big)
        assertEquals(2, store.refCount(BlobStore.parseReference(ref)!!.first), "one count per stored reference")
    }

    @Test
    fun `R6 compacting an attempt whose output is a blob reference reads head and tail from the blob and frees it`() = runTest {
        val f = RetentionFixture()
        val content = big(40_000, "blobbed")
        val ref = BlobStore.reference(f.blobs.put(content.toByteArray()), content.toByteArray().size)
        val att = f.attempt(f.run().second, daysOld = 60, output = ref)
        val shared = f.attempt(f.run().second, daysOld = 1, output = ref.also { f.blobs.put(content.toByteArray()) })
        val hash = BlobStore.parseReference(ref)!!.first
        assertEquals(2, f.blobs.refCount(hash))

        f.compactor().compact(f.projectId, policy, NOW)

        val c = Json.parseToJsonElement(f.column("attempts", "output_snapshot", att)!!).jsonObject
        assertEquals(content.take(2048), c["head"]!!.jsonPrimitive.content)
        assertEquals(hash, c["original_sha256"]!!.jsonPrimitive.content)
        assertEquals(1, f.blobs.refCount(hash), "this attempt's reference was released; the young attempt still holds one")
        assertTrue(java.io.File(f.blobDir, hash).isFile)
        assertEquals(ref, f.column("attempts", "output_snapshot", shared))

        // Once the last holder compacts, the blob itself is reclaimed.
        f.compactor().compact(f.projectId, policy, NOW.plusSeconds(60L * 86400))
        assertEquals(0L, f.count("blob_refs"))
        assertFalse(java.io.File(f.blobDir, hash).exists())
    }

    // ─── Storage pressure: stages 1 and 3 ───────────────────────────────────────────────────

    @Test
    fun `R7 stage assessment orders critical free space above the soft limit`() {
        val f = RetentionFixture()
        val roomy = StoragePressureMonitor(f.driver, f.blobs) { 10L * 1024 * 1024 * 1024 }
        val tight = StoragePressureMonitor(f.driver, f.blobs) { 100L * 1024 * 1024 }

        assertEquals(PressureStage.NORMAL, roomy.assess(policy))
        assertEquals(PressureStage.STAGE_1, roomy.assess(policy.copy(softLimitBytes = 1000)))
        assertEquals(PressureStage.STAGE_3, tight.assess(policy))
        assertEquals(PressureStage.STAGE_3, tight.assess(policy.copy(softLimitBytes = 1000)), "stage 3 wins")
    }

    @Test
    fun `R7 stage 1 compacts content older than 7 days when the project is over its soft limit`() = runTest {
        val f = RetentionFixture()
        val ids = (1..300).map { i -> f.attempt(f.run(daysOld = 20).second, daysOld = 20, output = big(60_000, "s$i")) }
        // 20 days old and under 64KB: inside the 30-day window, so the regular pass keeps them.
        f.exec("INSERT INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) VALUES ('project', ?, 'retention.soft_limit_mb', '16', ?, 'USER')", f.projectId, NOW.toString())
        val service = service(f)

        val report = service.maintain(f.projectId)

        assertEquals(PressureStage.STAGE_1, report.stageBefore)
        assertEquals(300, report.compacted)
        assertTrue(ids.all { f.column("attempts", "output_snapshot", it)!!.startsWith("{\"compacted_at") })
        assertTrue(report.bytesFreed > 15_000_000)
        assertEquals(PressureStage.NORMAL, report.stageAfter, "freed pages stop counting toward the footprint")
    }

    @Test
    fun `R7 under the soft limit nothing inside the window is compacted`() = runTest {
        val f = RetentionFixture()
        val att = f.attempt(f.run(daysOld = 20).second, daysOld = 20, output = big(60_000))
        val report = service(f).maintain(f.projectId)
        assertEquals(PressureStage.NORMAL, report.stageBefore)
        assertEquals(0, report.compacted)
        assertFalse(f.column("attempts", "output_snapshot", att)!!.startsWith("{\"compacted_at"))
    }

    // ─── Settings consumption (RFC-0036) ────────────────────────────────────────────────────

    @Test
    fun `R8 retention reads its windows through the settings layer, nearest scope first`() {
        val f = RetentionFixture()
        val userDb = userDriver()
        val settings = dev.aidos.settings.SettingsStore(userDb, f.driver)
        val resolver = RetentionPolicyResolver(settings, f.driver)

        assertEquals(30, resolver.resolve(f.projectId).agedDays, "declared default")

        userDb.execute(null, "INSERT INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) VALUES ('user', NULL, 'retention.aged_days', '60', ?, 'USER')", 1) { bindString(0, NOW.toString()) }
        assertEquals(60, resolver.resolve(f.projectId).agedDays, "user scope")

        f.exec("INSERT INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) VALUES ('project', ?, 'retention.aged_days', '12', ?, 'USER')", f.projectId, NOW.toString())
        f.exec("INSERT INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) VALUES ('project', ?, 'retention.never_compact', 'true', ?, 'USER')", f.projectId, NOW.toString())
        f.exec("INSERT INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) VALUES ('project', ?, 'retention.tool_result_days', '3', ?, 'USER')", f.projectId, NOW.toString())
        val p = resolver.resolve(f.projectId)
        assertEquals(12, p.agedDays, "project scope beats user scope")
        assertEquals(3, p.toolResultDays)
        assertTrue(p.neverCompact)
        assertNull(p.daysFor(RetainedObject.EVENT_PAYLOAD))
    }

    @Test
    fun `R8 an invalid retention value falls back to the default instead of disabling anything`() {
        val f = RetentionFixture()
        f.exec("INSERT INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) VALUES ('project', ?, 'retention.aged_days', '0', ?, 'USER')", f.projectId, NOW.toString())
        val errors = mutableListOf<dev.aidos.settings.SettingError>()
        val p = RetentionPolicyResolver(dev.aidos.settings.SettingsStore(userDriver(), f.driver), f.driver).resolve(f.projectId, errors = errors)
        assertEquals(30, p.agedDays)
        assertEquals(1, errors.size)
    }

    @Test
    fun `R8 retention_policy rows override per object, project over user, and cannot weaken a PERMANENT class`() {
        val f = RetentionFixture()
        f.exec("INSERT INTO retention_policy (scope, scope_id, object_class, retention_days) VALUES ('user', NULL, 'event_payload', 90)")
        f.exec("INSERT INTO retention_policy (scope, scope_id, object_class, retention_days) VALUES ('project', ?, 'event_payload', 14)", f.projectId)
        f.exec("INSERT INTO retention_policy (scope, scope_id, object_class, retention_days) VALUES ('project', ?, 'prompt_package', NULL)", f.projectId)
        f.exec("INSERT INTO retention_policy (scope, scope_id, object_class, retention_days) VALUES ('project', ?, 'audit_log', 1)", f.projectId)
        val p = RetentionPolicyResolver(dev.aidos.settings.SettingsStore(userDriver(), f.driver), f.driver).resolve(f.projectId)

        assertEquals(14, p.daysFor(RetainedObject.EVENT_PAYLOAD))
        assertNull(p.daysFor(RetainedObject.PROMPT_PACKAGE), "NULL means never compact")
        assertEquals(30, p.daysFor(RetainedObject.ATTEMPT_INPUT_SNAPSHOT))
        assertFalse(p.perObjectDays.containsKey("audit_log"), "a stray row cannot make a PERMANENT class compactable")
    }

    // ─── helpers ────────────────────────────────────────────────────────────────────────────

    private fun service(f: RetentionFixture, free: Long = 10L * 1024 * 1024 * 1024) = RetentionService(
        driver = f.driver, blobs = f.blobs,
        resolver = RetentionPolicyResolver(dev.aidos.settings.SettingsStore(userDriver(), f.driver), f.driver),
        monitor = StoragePressureMonitor(f.driver, f.blobs) { free },
        idGen = { f.id("cmp") }, clock = { NOW },
    )

    private fun userDriver() = dev.aidos.storage.AidosStorage.openUser(
        dev.aidos.storage.DesktopPaths.userDb(java.nio.file.Files.createTempDirectory("retention-user").toFile().path), "test-1.0",
    ) { NOW.toString() }.driver as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

}
