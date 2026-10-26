package dev.aidos.mcp

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.DirEntry
import dev.aidos.kernel.DirHandle
import dev.aidos.kernel.RelPath
import dev.aidos.kernel.ToolOutcome
import dev.aidos.mcp.core.McpCallResult
import dev.aidos.mcp.core.McpCancelOutcome
import dev.aidos.mcp.core.McpClient
import dev.aidos.mcp.core.McpContent
import dev.aidos.mcp.core.McpNotification
import dev.aidos.mcp.core.McpServerInfo
import dev.aidos.mcp.core.McpServerRegistration
import dev.aidos.mcp.core.McpTaskClient
import dev.aidos.mcp.core.McpTaskSnapshot
import dev.aidos.mcp.core.McpTaskStatus
import dev.aidos.mcp.core.McpToolExecution
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.mcp.core.McpTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [McpTool]'s task path against a scripted client; no process or socket involved. */
class McpToolTasksTest {
    private val handle = object : DirHandle {
        override val capabilityId = CapabilityId("test")
        override suspend fun read(relative: RelPath): Result<ByteArray> = Result.failure(UnsupportedOperationException())
        override suspend fun write(relative: RelPath, content: ByteArray): Result<Unit> = Result.failure(UnsupportedOperationException())
        override suspend fun list(relative: RelPath): Result<List<DirEntry>> = Result.failure(UnsupportedOperationException())
        override suspend fun exists(relative: RelPath): Boolean = false
    }
    private val registration = McpServerRegistration("srv", McpTransport.Stdio("x"), listOf(McpToolSpec("slow", "")))
    private val ok = McpCallResult(listOf(McpContent.Text("result")), false)

    private class Fake(
        val tasks: Boolean,
        val execution: McpToolExecution,
        val polls: ArrayDeque<McpTaskStatus>,
    ) : McpClient, McpTaskClient {
        var plainCalls = 0
        var cancelled = false
        override suspend fun initialize() = McpServerInfo("f", "1")
        override suspend fun listTools() = emptyList<McpToolSpec>()
        override suspend fun callTool(name: String, arguments: JsonObject): McpCallResult { plainCalls++; return McpCallResult(listOf(McpContent.Text("plain")), false) }
        override fun close() {}
        override val notifications: Flow<McpNotification> = emptyFlow()
        override suspend fun supportsTasks() = tasks
        override suspend fun callToolAsTask(name: String, arguments: JsonObject, ttlMillis: Long?) = execution
        override suspend fun getTask(taskId: String) = McpTaskSnapshot(taskId, if (polls.size > 1) polls.removeFirst() else polls.first(), pollIntervalMillis = 10)
        override suspend fun getTaskResult(taskId: String) = McpCallResult(listOf(McpContent.Text("result")), false)
        override suspend fun cancelTask(taskId: String): McpCancelOutcome { cancelled = true; return McpCancelOutcome(McpTaskSnapshot(taskId, McpTaskStatus.CANCELLED)) }
    }

    private val started = McpToolExecution.Started(McpTaskSnapshot("t1", McpTaskStatus.WORKING))

    private suspend fun run(fake: Fake, wait: Long = 5_000) = McpTool(registration, fake, taskWaitMillis = wait).execute(handle, "srv:slow", JsonObject(emptyMap()))

    @Test
    fun defaultStaysSynchronous() = runTest {
        val fake = Fake(true, started, ArrayDeque(listOf(McpTaskStatus.COMPLETED)))
        val r = run(fake, wait = 0)
        assertEquals(1, fake.plainCalls)
        assertEquals(listOf<ContentBlock>(ContentBlock.Text("plain")), r.content)
    }

    @Test
    fun completedTaskYieldsItsResult() = runTest {
        val r = run(Fake(true, started, ArrayDeque(listOf(McpTaskStatus.WORKING, McpTaskStatus.COMPLETED))))
        assertIs<ToolOutcome.Ok>(r.outcome)
        assertEquals(listOf<ContentBlock>(ContentBlock.Text("result")), r.content)
    }

    @Test
    fun immediateAnswerFromATaskCapableServerIsUsedAsIs() = runTest {
        val r = run(Fake(true, McpToolExecution.Immediate(ok), ArrayDeque(listOf(McpTaskStatus.WORKING))))
        assertIs<ToolOutcome.Ok>(r.outcome)
    }

    @Test
    fun failedAndCancelledTasksAreToolErrors() = runTest {
        for (s in listOf(McpTaskStatus.FAILED, McpTaskStatus.CANCELLED)) {
            assertIs<ToolOutcome.Failed>(run(Fake(true, started, ArrayDeque(listOf(s)))).outcome)
        }
    }

    @Test
    fun unfinishedTaskIsCancelledAndTheOutcomeIsReportedAsUnknownUnlessConfirmed() = runTest {
        val fake = Fake(true, started, ArrayDeque(listOf(McpTaskStatus.WORKING)))
        val r = run(fake, wait = 100)
        assertIs<ToolOutcome.Failed>(r.outcome)
        assertTrue(fake.cancelled)
        assertTrue((r.content.single() as ContentBlock.Text).text.contains("did not finish"))
    }

    @Test
    fun inputRequiredIsRefusedNotAnswered() = runTest {
        val fake = Fake(true, started, ArrayDeque(listOf(McpTaskStatus.INPUT_REQUIRED)))
        val r = run(fake, wait = 100)
        assertIs<ToolOutcome.Failed>(r.outcome)
        assertTrue((r.content.single() as ContentBlock.Text).text.contains("needs input"))
    }
}
