package dev.aidos.daemon

import dev.aidos.api.CapabilityResult
import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.CreateSessionRequest
import dev.aidos.api.GrantCapabilityRequest
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import dev.aidos.api.RealRuntimeClient
import dev.aidos.api.SessionResult
import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.ShellHandle
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** RFC-0033: a Shell grant made through the API (the CLI's `grant`) is a real capability the tool will honour. */
class ShellGrantApiTest {
    private val now = "2026-11-15T00:00:00Z"

    private class Env(val client: RealRuntimeClient, val sessionId: String, val manager: SqliteCapabilityManager)

    private fun env(): Env = runBlocking {
        val home = Files.createTempDirectory("shell-grant-home").toFile()
        val projDir = Files.createTempDirectory("shell-grant-proj").toFile()
        val userDb = AidosStorage.openUser(DesktopPaths.userDb(home.absolutePath), "t") { now }
        val client = RealRuntimeClient().apply {
            userDriver = userDb.driver
            projectDbFactory = { root -> AidosStorage.openProject(DesktopPaths.stateDb(root), "t") { now }.driver }
            runtimeManagedProjectsRoot = File(home, ".aidos/projects").absolutePath
            capabilityManagerFor = { d -> SqliteCapabilityManager(d, UuidV7Generator()) { now } }
        }
        val project = assertIs<ProjectResult.Success>(client.projects.create(CreateProjectRequest("p", "", ProjectLocation.LocalPath(projDir.absolutePath)))).project
        val session = assertIs<SessionResult.Success>(client.sessions.create(CreateSessionRequest(project.id, "s"))).session
        // A second manager on the same database is how a tool call would see the grant.
        val driver = AidosStorage.openProject(DesktopPaths.stateDb(projDir.absolutePath), "t") { now }.driver
        Env(client, session.id, SqliteCapabilityManager(driver, UuidV7Generator()) { now })
    }

    @Test
    fun `a shell grant is stored with its directory and command list, listed, and opens a ShellHandle`(): Unit = runBlocking {
        val e = env()
        val granted = e.client.capabilities.grant(GrantCapabilityRequest(e.sessionId, "SHELL_EXEC", "shell:sub|java,git"))
        val id = assertIs<CapabilityResult.Success>(granted).capabilityId

        val listed = e.client.capabilities.list(e.sessionId).single { it.id == id }
        assertEquals("SHELL_EXEC", listed.permission)
        assertEquals("shell:sub|java,git", listed.scope)

        val stored = e.manager.loadForSubject(e.sessionId).single { it.id.value == id }
        val scope = assertIs<CapabilityScope.Shell>(stored.scope)
        assertEquals("sub", scope.workingDirectory)
        assertEquals(listOf("java", "git"), scope.allowedCommands)

        val handle = e.manager.openHandle(e.sessionId, CapabilityId(id)).getOrThrow()
        assertIs<ShellHandle>(handle)
        assertEquals(listOf("java", "git"), handle.allowedCommands)
    }

    @Test
    fun `a grant with no command list allows any program and defaults to the project root`(): Unit = runBlocking {
        val e = env()
        val id = assertIs<CapabilityResult.Success>(e.client.capabilities.grant(GrantCapabilityRequest(e.sessionId, "SHELL_EXEC", "shell:."))).capabilityId
        val scope = assertIs<CapabilityScope.Shell>(e.manager.loadForSubject(e.sessionId).single { it.id.value == id }.scope)
        assertEquals(".", scope.workingDirectory)
        assertEquals(null, scope.allowedCommands)
    }

    @Test
    fun `revoking removes the capability from the database as well as the list`(): Unit = runBlocking {
        val e = env()
        val id = assertIs<CapabilityResult.Success>(e.client.capabilities.grant(GrantCapabilityRequest(e.sessionId, "SHELL_EXEC", "shell:."))).capabilityId
        e.client.capabilities.revoke(id)
        assertTrue(e.client.capabilities.list(e.sessionId).none { it.id == id })
        assertTrue(e.manager.loadForSubject(e.sessionId).none { it.id.value == id && it.revokedAt == null })
    }
}
