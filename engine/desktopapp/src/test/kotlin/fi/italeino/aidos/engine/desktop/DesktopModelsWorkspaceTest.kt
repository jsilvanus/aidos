package fi.italeino.aidos.engine.desktop

import dev.aidos.api.MockRuntimeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The desktop window's models take the Agent runtime they were given and create nothing in it on their own
 * (RFC-0124 UI-11). The old demo seed made a "Universal Workspace" with two fake nodes on every start.
 */
class DesktopModelsWorkspaceTest {
    private val root = Files.createTempDirectory("desktop-models").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val host = DesktopEngineHost(
        modelsDir = root.resolve("models").apply { mkdirs() },
        stateDir = root,
        serverPort = { 0 },
        discoveryFile = root.resolve("engine.json"),
    )

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        runBlocking { host.close() }
        root.deleteRecursively()
    }

    @Test
    fun `constructing the window models creates no project in the runtime they use`() = runBlocking {
        val client = MockRuntimeClient()
        val models = DesktopModels(host, DesktopPreferences(root.resolve("preferences.json")), scope, AgentConnection.InProcess(client))

        assertSame(client, models.runtimeClient)
        assertTrue(client.projects.list().isEmpty(), "no demo project is seeded on construction")
    }
}
