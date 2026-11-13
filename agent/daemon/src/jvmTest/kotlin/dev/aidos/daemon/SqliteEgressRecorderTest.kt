package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.http.AddressClass
import dev.aidos.http.EgressOutcome
import dev.aidos.http.EgressRecord
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteEgressRecorderTest {
    private val nowIso = "2026-11-13T00:00:00Z"

    private fun openProjectDriver(): SqlDriver {
        val root = Files.createTempDirectory("egress-recorder-test").toFile()
        return AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
    }

    private fun seedProject(driver: SqlDriver, projectId: String) {
        driver.execute(
            null,
            "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', ?, ?, ?)",
            4,
        ) { bindString(0, projectId); bindString(1, nowIso); bindString(2, nowIso); bindString(3, nowIso) }
    }

    @Test
    fun `recorder writes egress row`() {
        val driver = openProjectDriver()
        seedProject(driver, "proj-egress")
        val recorder = SqliteEgressRecorder(driver)
        recorder(
            EgressRecord(
                id = "eg-1",
                projectId = "proj-egress",
                attemptId = null,
                capabilityId = "cap-1",
                destinationHost = "api.example.com",
                resolvedAddressClass = AddressClass.PUBLIC,
                method = "POST",
                requestBytes = 42,
                responseBytes = 128,
                payloadHash = "abc123",
                contentSensitivity = "REQUIRES_APPROVAL",
                runTaint = "UNTRUSTED",
                durationMs = 12,
                outcome = EgressOutcome.SUCCESS,
                costUnits = 7,
                occurredAt = nowIso,
            )
        )

        val count = driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM egress_records WHERE id = 'eg-1' AND destination_host = 'api.example.com'",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) },
            parameters = 0,
        ) {}.value
        assertEquals(1L, count)
    }
}
