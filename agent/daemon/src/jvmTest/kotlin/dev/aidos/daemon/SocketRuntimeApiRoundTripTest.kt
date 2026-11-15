package dev.aidos.daemon

import dev.aidos.api.ArtifactFilter
import dev.aidos.api.CapabilityResult
import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.CreateSessionRequest
import dev.aidos.api.DiffRange
import dev.aidos.api.KnowledgeQuery
import dev.aidos.api.MockRuntimeClient
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import dev.aidos.api.SessionResult
import dev.aidos.api.socket.SocketPaths
import dev.aidos.cli.SocketRuntimeClient
import dev.aidos.intent.IntentNodeType
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SocketRuntimeApiRoundTripTest {

    private val tempDir = Files.createTempDirectory("aidos-socket-rt-test")

    @AfterTest
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `full RuntimeClient surface and version negotiation round-trip over Unix socket`() = runBlocking {
        val mock = MockRuntimeClient()
        val socketPath = tempDir.resolve("runtime.sock")
        val tokenPath = SocketPaths.defaultTokenPath(socketPath)
        val server = RuntimeSocketServer(mock, socketPath, tokenPath)
        server.start()
        try {
            val client = SocketRuntimeClient(socketPath, tokenPath, interactive = true)
            val version = client.runtime.version()
            assertEquals(1, version.apiVersion)
            assertEquals(1, version.minApiVersion)

            val createdProject = client.projects.create(
                CreateProjectRequest(
                    name = "Demo",
                    description = "Socket round-trip",
                    location = ProjectLocation.RuntimeManaged("demo"),
                )
            )
            val projectId = (createdProject as ProjectResult.Success).project.id
            assertNotNull(client.projects.get(projectId))
            assertTrue(client.projects.open(projectId) is ProjectResult.Success)

            val createdSession = client.sessions.create(
                CreateSessionRequest(projectId = projectId, name = "Chat")
            )
            val sessionId = (createdSession as SessionResult.Success).session.id
            assertNotNull(client.sessions.get(sessionId))
            client.sessions.cancel(sessionId, "run-1")
            client.sessions.archive(sessionId)
            client.sessions.delete(sessionId)

            assertTrue(client.capabilities.list(projectId).isEmpty())
            val approval = client.capabilities.approveEffect("run-1", "task-1", "voice", "Approve")
            assertTrue(approval is CapabilityResult.Success)
            client.capabilities.denyEffect("run-1", "task-2", "nope")

            val kq = client.knowledge.search(projectId, KnowledgeQuery(text = "hello"))
            assertTrue(kq.items.isEmpty())
            assertEquals(0, client.knowledge.indexStatus(projectId).nodeCount)

            val diffSummary = client.diff.changes(projectId, DiffRange.WorkingTree)
            assertEquals(0, diffSummary.filesChanged)

            val artifacts = client.artifacts.list(projectId, ArtifactFilter())
            assertTrue(artifacts.isEmpty())

            val node = client.intents.createNode(
                CreateIntentNodeRequest(
                    projectId = projectId,
                    title = "Socket intent",
                    type = IntentNodeType.ATOMIC_TASK,
                )
            )
            assertEquals("Socket intent", node.title)
            assertEquals(node.id, client.intents.getNodeStatus(projectId, node.id)?.node?.id)
            assertTrue(client.intents.listNodes(projectId).any { it.id == node.id })
        } finally {
            server.stop()
        }
    }
}
