package fi.italeino.aidos.engine.desktop

import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.CreateSessionRequest
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import dev.aidos.api.SessionResult
import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.ShellHandle
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Shell grants tab's presenter against a real database-backed client (RFC-0033). */
class DesktopShellGrantsModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val home = Files.createTempDirectory("shell-grants-home").toFile()
    private val proj = Files.createTempDirectory("shell-grants-proj").toFile()

    @AfterTest
    fun tearDown() { scope.cancel() }

    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for: $what")
    }

    @Test
    fun `picking a project and session, granting and revoking go through to the capabilities table`(): Unit = runBlocking {
        val client = DesktopRuntimeClient.create(home.path)
        val project = assertIs<ProjectResult.Success>(client.projects.create(CreateProjectRequest("demo", "", ProjectLocation.LocalPath(proj.path)))).project
        val session = assertIs<SessionResult.Success>(client.sessions.create(CreateSessionRequest(project.id, "main"))).session

        val model = DesktopShellGrantsModel(scope) { client }
        model.refresh()
        eventually("projects listed") { model.state.value.projects.any { it.id == project.id } }
        model.selectProject(project.id)
        eventually("sessions listed") { model.state.value.sessions.any { it.id == session.id } }
        model.selectSession(session.id)
        eventually("session selected") { model.state.value.selectedSessionId == session.id }
        assertTrue(model.state.value.grants.isEmpty(), "no shell grant by default")

        model.grant("src", listOf("git", "gradle"), 2)
        eventually("grant listed") { model.state.value.grants.size == 1 }
        val grant = model.state.value.grants.single()
        assertEquals("src", grant.workingDirectory)
        assertEquals(listOf("git", "gradle"), grant.commands)
        assertTrue(grant.expiresAt != null)

        // What a tool call would see: the same database, read by a separate capability manager.
        val driver = AidosStorage.openProject(DesktopPaths.stateDb(proj.path), "t") { "2026-11-15T00:00:00Z" }.driver
        val manager = SqliteCapabilityManager(driver, UuidV7Generator()) { "2026-11-15T00:00:00Z" }
        val stored = manager.loadForSubject(session.id).single { it.id.value == grant.id }
        assertEquals(listOf("git", "gradle"), assertIs<CapabilityScope.Shell>(stored.scope).allowedCommands)
        assertIs<ShellHandle>(manager.openHandle(session.id, CapabilityId(grant.id)).getOrThrow())

        model.revoke(grant.id)
        eventually("grant revoked") { model.state.value.grants.isEmpty() }
        assertTrue(manager.loadForSubject(session.id).none { it.id.value == grant.id && it.revokedAt == null })
        assertNull(model.state.value.error)
    }

    @Test
    fun `a grant with no program list is stored as any program`(): Unit = runBlocking {
        val client = DesktopRuntimeClient.create(home.path)
        val project = assertIs<ProjectResult.Success>(client.projects.create(CreateProjectRequest("demo2", "", ProjectLocation.LocalPath(proj.path)))).project
        val session = assertIs<SessionResult.Success>(client.sessions.create(CreateSessionRequest(project.id, "main"))).session
        val model = DesktopShellGrantsModel(scope) { client }
        model.selectProject(project.id)
        eventually("sessions") { model.state.value.sessions.isNotEmpty() }
        model.selectSession(session.id)
        eventually("selected") { model.state.value.selectedSessionId == session.id }
        model.grant(".", null, null)
        eventually("listed") { model.state.value.grants.size == 1 }
        assertNull(model.state.value.grants.single().commands)
        assertNull(model.state.value.grants.single().expiresAt)
    }
}
