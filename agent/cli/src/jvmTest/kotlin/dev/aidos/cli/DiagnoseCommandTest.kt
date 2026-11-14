package dev.aidos.cli

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.api.MockRuntimeClient
import dev.aidos.identity.ProjectRegistry
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.ProjectId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Tests for DiagnoseCommand (RFC-0037) and CLI `"diagnose"` wiring.
 */
class DiagnoseCommandTest {

    private lateinit var tempDir: File
    private lateinit var homeDir: File
    private lateinit var diagnoseCommand: DiagnoseCommand
    private lateinit var mockRuntimeClient: MockRuntimeClient

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("diagnose-cmd-test").toFile()
        homeDir = File(tempDir, "home").apply { mkdirs() }
        mockRuntimeClient = MockRuntimeClient()
        diagnoseCommand = DiagnoseCommand(
            runtimeClient = mockRuntimeClient,
            homeDir = homeDir.absolutePath,
        )
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testDiagnoseCreatesBundleFile() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath

        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        assertTrue(File(bundlePath).exists(), "Bundle file should be created")
    }

    @Test
    fun testBundleContainsManifest() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath

        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        ZipFile(bundlePath).use { zip ->
            val entry = zip.getEntry("manifest.json")
            assertNotNull(entry, "Bundle should contain manifest.json")
        }
    }

    @Test
    fun testBundleContainsRedactionReport() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath

        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        ZipFile(bundlePath).use { zip ->
            val entry = zip.getEntry("redaction-report.txt")
            assertNotNull(entry, "Bundle should contain redaction-report.txt")

            val content = zip.getInputStream(entry).bufferedReader().readText()
            assertTrue(content.contains("Redaction Report"))
            assertTrue(content.contains("excluded by default"))
        }
    }

    @Test
    fun testBundleContainsMetrics() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath

        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        ZipFile(bundlePath).use { zip ->
            val entry = zip.getEntry("metrics.json")
            assertNotNull(entry, "Bundle should contain metrics.json")
        }
    }

    @Test
    fun testBundleContainsSchema() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath

        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        ZipFile(bundlePath).use { zip ->
            val entry = zip.getEntry("schema.json")
            assertNotNull(entry, "Bundle should contain schema.json")
        }
    }

    @Test
    fun testBundleStructure() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath

        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        ZipFile(bundlePath).use { zip ->
            val entries = zip.entries().toList().map { it.name }

            assertTrue(entries.contains("manifest.json"))
            assertTrue(entries.contains("metrics.json"))
            assertTrue(entries.contains("schema.json"))
            assertTrue(entries.contains("redaction-report.txt"))
        }
    }

    @Test
    fun testIncludeTraceFlag() {
        val logsDir = File(homeDir, ".aidos/logs").apply { mkdirs() }
        File(logsDir, "diagnostic-2026-11-14.log").writeText("""{"level":"INFO","key":"boot"}""" + "\n")
        File(logsDir, "diagnostic-trace.log").writeText("""{"level":"TRACE","key":"prompt","token":"trace-secret-tok"}""" + "\n")

        val noTraceZip = File(tempDir, "diagnostic-no-trace.zip").absolutePath
        runBlocking {
            diagnoseCommand.diagnose(noTraceZip, includeTrace = false)
        }
        ZipFile(noTraceZip).use { zip ->
            assertNotNull(zip.getEntry("logs/diagnostic-2026-11-14.log"))
            assertNull(zip.getEntry("logs/diagnostic-trace.log"))
        }

        val withTraceZip = File(tempDir, "diagnostic-trace.zip").absolutePath
        runBlocking {
            diagnoseCommand.diagnose(withTraceZip, includeTrace = true)
        }
        ZipFile(withTraceZip).use { zip ->
            val traceEntry = zip.getEntry("logs/diagnostic-trace.log")
            assertNotNull(traceEntry)
            val traceContent = zip.getInputStream(traceEntry).bufferedReader().readText()
            assertFalse(traceContent.contains("trace-secret-tok"), "Secrets must stay redacted even with includeTrace")
        }
    }

    @Test
    fun testSelectedRuns() {
        val bundlePath = File(tempDir, "diagnostic-runs.zip").absolutePath
        val selectedRuns = listOf("run-123", "run-456")

        runBlocking {
            diagnoseCommand.diagnose(bundlePath, selectedRuns = selectedRuns)
        }

        ZipFile(bundlePath).use { zip ->
            assertNotNull(zip.getEntry("execution/run-123.json"))
            assertNotNull(zip.getEntry("execution/run-456.json"))
        }
    }

    @Test
    fun testBundleIsValidZip() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath

        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        var isValidZip = false
        try {
            ZipFile(bundlePath).use { zip ->
                isValidZip = zip.size() > 0
            }
        } catch (e: Exception) {
            isValidZip = false
        }

        assertTrue(isValidZip, "Bundle should be a valid ZIP file")
    }

    @Test
    fun testManifestContentIsJson() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath

        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        ZipFile(bundlePath).use { zip ->
            val entry = zip.getEntry("manifest.json")
            assertNotNull(entry)
            val content = zip.getInputStream(entry).bufferedReader().readText()
            assertTrue(content.contains("timestamp"))
            assertTrue(content.contains("runtime_version") || content.contains("platform"))
        }
    }

    @Test
    fun testExtractsRealSqliteMetricsSchemaExecutionSkeletonsAndCrashRecords() {
        val nowIso = Clock.System.now().toString()
        val recentStartedIso = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 3_600_000L).toString()

        val projectDir = File(tempDir, "sample-project").apply { mkdirs() }
        val userDbPath = DesktopPaths.userDb(homeDir.path)
        File(userDbPath).parentFile.mkdirs()

        val userDb = AidosStorage.openUser(userDbPath, "0.2.0-test") { nowIso }
        try {
            ProjectRegistry(userDb.driver, UuidV7Generator()).register(
                projectId = ProjectId("proj-real"),
                path = projectDir.absolutePath,
                nowIso = nowIso,
            )
            userDb.driver.execute(
                identifier = null,
                sql = """
                    INSERT INTO crash_records (
                        id, occurred_at, runtime_version, platform_profile, error_code,
                        stack_hash, detail_path, in_flight_run_ids, reported
                    ) VALUES (?, ?, '0.2.0-test', 'DESKTOP', ?, ?, ?, '["run-failed-1"]', 0)
                """,
                parameters = 5,
            ) {
                bindString(0, "crash-db-1")
                bindString(1, nowIso)
                bindString(2, "Auth Bearer supersecret-db-token")
                bindString(3, "b".repeat(64))
                bindString(4, "${homeDir.absolutePath}/.aidos/crashes/crash-db-1.txt")
            }
        } finally {
            userDb.driver.close()
        }

        val stateDb = AidosStorage.openProject(DesktopPaths.stateDb(projectDir.path), "0.2.0-test") { nowIso }
        try {
            seedProjectDbData(stateDb.driver, "proj-real", recentStartedIso, nowIso)
        } finally {
            stateDb.driver.close()
        }

        val bundlePath = File(tempDir, "real-db.zip").absolutePath
        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        ZipFile(bundlePath).use { zip ->
            val metricsEntry = zip.getEntry("metrics.json")
            assertNotNull(metricsEntry)
            val metricsJson = Json.parseToJsonElement(zip.getInputStream(metricsEntry).bufferedReader().readText()).jsonObject
            assertEquals(1L, metricsJson["project_count"]?.jsonPrimitive?.long)
            val samples = metricsJson["samples"]?.jsonArray ?: emptyList()
            assertTrue(samples.any { it.jsonObject["name"]?.jsonPrimitive?.content == "run.outcomes" })
            val runOutcomes = metricsJson["run_outcomes"]?.jsonArray ?: emptyList()
            assertTrue(runOutcomes.any { it.jsonObject["error_code"]?.jsonPrimitive?.content == "runtime.timeout" })

            val schemaEntry = zip.getEntry("schema.json")
            assertNotNull(schemaEntry)
            val schemaJson = Json.parseToJsonElement(zip.getInputStream(schemaEntry).bufferedReader().readText()).jsonObject
            assertTrue((schemaJson["schema_version"]?.jsonPrimitive?.long ?: 0L) > 0L)
            assertNotNull(schemaJson["user_db"]?.jsonObject)
            assertTrue((schemaJson["project_dbs"]?.jsonArray?.size ?: 0) == 1)

            // Default selection picks recent FAILED run without explicitly passing --runs
            val execEntry = zip.getEntry("execution/run-failed-1.json")
            assertNotNull(execEntry, "Default run selection should export recent failed run from SQLite")
            val execText = zip.getInputStream(execEntry).bufferedReader().readText()
            val execJson = Json.parseToJsonElement(execText).jsonObject
            assertEquals("FAILED", execJson["state"]?.jsonPrimitive?.content)
            assertEquals("runtime.timeout", execJson["error_code"]?.jsonPrimitive?.content)
            assertEquals(1, execJson["tasks"]?.jsonArray?.size)
            assertEquals(1, execJson["attempts"]?.jsonArray?.size)
            assertEquals(1, execJson["tool_calls"]?.jsonArray?.size)
            assertFalse(execText.contains("SECRET_PROMPT_SHOULD_NOT_EXPORT"))
            assertFalse(execText.contains("SECRET_TOOL_ARG_SHOULD_NOT_EXPORT"))

            val crashesDbEntry = zip.getEntry("crashes/crash_records.json")
            assertNotNull(crashesDbEntry)
            val crashesDbText = zip.getInputStream(crashesDbEntry).bufferedReader().readText()
            assertTrue(crashesDbText.contains("crash-db-1"))
            assertFalse(crashesDbText.contains("supersecret-db-token"))
            assertFalse(crashesDbText.contains(homeDir.absolutePath))
        }
    }

    @Test
    fun testRedactsSecretsAndPiiInLogsAndCrashFilesAndHandles30DayLongCutoff() {
        val logsDir = File(homeDir, ".aidos/logs").apply { mkdirs() }
        val crashesDir = File(homeDir, ".aidos/crashes").apply { mkdirs() }

        val recentLog = File(logsDir, "diagnostic-recent.log")
        recentLog.writeText(
            """
            {"level":"ERROR","msg":"Bearer my-secret-bearer-99 sk-ant-secretkey1234567890abcd email=alice@example.com path=${homeDir.absolutePath}\\file.txt"}
            """.trimIndent() + "\n",
        )
        // 15 days ago (> Int.MAX_VALUE ms would overflow if multiplied in 32-bit Int!)
        val fifteenDaysAgo = System.currentTimeMillis() - (15L * 86_400_000L)
        recentLog.setLastModified(fifteenDaysAgo)

        val oldLog = File(logsDir, "diagnostic-old.log")
        oldLog.writeText("""{"level":"INFO","msg":"too old"}""" + "\n")
        oldLog.setLastModified(System.currentTimeMillis() - (45L * 86_400_000L))

        val crashFile = File(crashesDir, "crash-test.txt")
        crashFile.writeText(
            """
            Exception: token=secret-url-tok {"api_key":"secret-json-key"} password=hunter2pass
            Home: ${homeDir.absolutePath}/crash
            Contact: bob@example.org
            """.trimIndent(),
        )
        crashFile.setLastModified(fifteenDaysAgo)

        // Verify Long bounds check works above Int.MAX_VALUE without overflow
        assertTrue(DiagnoseCommand.isWithinSizeLimits(3_000_000_000L, 1_000_000_000L, 4_000_000_000L, 5_000_000_000L))
        assertFalse(DiagnoseCommand.isWithinSizeLimits(3_000_000_000L, 3_000_000_000L, 4_000_000_000L, 5_000_000_000L))

        val bundlePath = File(tempDir, "redacted-bundle.zip").absolutePath
        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        ZipFile(bundlePath).use { zip ->
            val logEntry = zip.getEntry("logs/diagnostic-recent.log")
            assertNotNull(logEntry, "15-day-old file must be included (no 32-bit Int overflow)")
            assertNull(zip.getEntry("logs/diagnostic-old.log"), "45-day-old file must be excluded")

            val logContent = zip.getInputStream(logEntry).bufferedReader().readText()
            assertFalse(logContent.contains("my-secret-bearer-99"))
            assertFalse(logContent.contains("sk-ant-secretkey1234567890abcd"))
            assertFalse(logContent.contains("alice@example.com"))
            assertFalse(logContent.contains(homeDir.absolutePath))

            val crashEntry = zip.getEntry("crashes/crash-test.txt")
            assertNotNull(crashEntry)
            val crashContent = zip.getInputStream(crashEntry).bufferedReader().readText()
            assertFalse(crashContent.contains("secret-url-tok"))
            assertFalse(crashContent.contains("secret-json-key"))
            assertFalse(crashContent.contains("hunter2pass"))
            assertFalse(crashContent.contains("bob@example.org"))
            assertFalse(crashContent.contains(homeDir.absolutePath))
        }
    }

    @Test
    fun testCliDiagnoseDispatchAndHelpWiring() {
        assertTrue(helpText().contains("diagnose"))

        val outZip = File(tempDir, "cli-dispatched.zip")
        val parsed = parseDiagnoseArgs(
            listOf("--output", outZip.absolutePath, "--include-trace", "--runs", "run-a, run-b", "--include-sensitive"),
        )
        assertEquals(outZip.absolutePath, parsed.outputPath)
        assertTrue(parsed.includeTrace)
        assertEquals(listOf("run-a", "run-b"), parsed.selectedRuns)
        assertTrue(parsed.includeSensitive)

        runBlocking {
            dispatch(
                cli = AidosCli(mockRuntimeClient),
                command = "diagnose",
                args = listOf("--output", outZip.absolutePath, "--runs", "run-cli-1"),
                client = mockRuntimeClient,
                homeDir = homeDir.absolutePath,
            )
        }

        assertTrue(outZip.exists(), "CLI dispatch for 'diagnose' should create zip bundle")
        ZipFile(outZip).use { zip ->
            assertNotNull(zip.getEntry("manifest.json"))
            assertNotNull(zip.getEntry("execution/run-cli-1.json"))
        }
    }

    private fun seedProjectDbData(
        driver: SqlDriver,
        projectId: String,
        startedIso: String,
        nowIso: String,
    ) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO projects (id, name, root_path, project_type, state, created_at, updated_at, state_updated_at)
                VALUES (?, 'Real Project', ?, 'coding', 'OPEN', ?, ?, ?)
            """,
            parameters = 5,
        ) {
            bindString(0, projectId)
            bindString(1, File(tempDir, "sample-project").absolutePath)
            bindString(2, nowIso)
            bindString(3, nowIso)
            bindString(4, nowIso)
        }
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at)
                VALUES ('sess-1', ?, 'Main', 'DRIVER', 'RUNNING', ?, ?, ?)
            """,
            parameters = 4,
        ) {
            bindString(0, projectId)
            bindString(1, nowIso)
            bindString(2, nowIso)
            bindString(3, nowIso)
        }
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, timestamp, source, payload)
                VALUES ('evt-1', ?, 1, 'UserTurn', 1, 'COMMAND', 'PUBLIC', ?, 'cli', '{}')
            """,
            parameters = 2,
        ) {
            bindString(0, projectId)
            bindString(1, nowIso)
        }
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO runs (
                    id, session_id, project_id, trigger_event_id, started_at, ended_at,
                    state, error_code, error_class, error_detail_json, retry_policy_json, platform_profile, device_id
                ) VALUES ('run-failed-1', 'sess-1', ?, 'evt-1', ?, ?, 'FAILED', 'runtime.timeout', 'TRANSIENT', 'token=leaked-run-secret', '{}', 'DESKTOP', 'dev-1')
            """,
            parameters = 3,
        ) {
            bindString(0, projectId)
            bindString(1, startedIso)
            bindString(2, nowIso)
        }
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO tasks (id, run_id, session_id, project_id, ordinal, kind, description, state, started_at, ended_at, retry_policy_json)
                VALUES ('task-1', 'run-failed-1', 'sess-1', ?, 1, 'MODEL_CALL', 'Run inference', 'FAILED', ?, ?, '{}')
            """,
            parameters = 3,
        ) {
            bindString(0, projectId)
            bindString(1, startedIso)
            bindString(2, nowIso)
        }
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO audit_log (id, project_id, sequence, occurred_at, kind, actor_kind, actor_id, device_id, detail_json)
                VALUES ('aud-1', ?, 1, ?, 'RunFailed', 'RUNTIME', 'daemon', 'dev-1', '{}')
            """,
            parameters = 2,
        ) {
            bindString(0, projectId)
            bindString(1, nowIso)
        }
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO attempts (
                    id, task_id, attempt_number, started_at, ended_at, state,
                    input_snapshot, model_provider, tokens_input, tokens_output, cost_units, audit_ref
                ) VALUES ('att-1', 'task-1', 1, ?, ?, 'FAILED', 'SECRET_PROMPT_SHOULD_NOT_EXPORT', 'anthropic', 10, 20, 3, 'aud-1')
            """,
            parameters = 2,
        ) {
            bindString(0, startedIso)
            bindString(1, nowIso)
        }
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO tool_calls (call_id, run_id, model_task_id, tool_name, arguments_json, schema_valid, outcome, step_index)
                VALUES ('call-1', 'run-failed-1', 'task-1', 'read_file', '{"secret":"SECRET_TOOL_ARG_SHOULD_NOT_EXPORT"}', 1, 'ERROR', 1)
            """,
            parameters = 0,
        )
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO metric_samples (id, project_id, name, value, unit, labels_json, bucket_start)
                VALUES ('met-1', ?, 'run.outcomes', 1.0, 'count', '{"state":"FAILED"}', ?)
            """,
            parameters = 2,
        ) {
            bindString(0, projectId)
            bindString(1, nowIso)
        }
    }
}
