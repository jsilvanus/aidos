package fi.italeino.aidos.engine.agent

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tripwire for the frozen agent API v1 (RFC-0120, `sdk/CONTRACT.md` "Agent API v1").
 *
 * Pins the wire names an app built against v1 relies on. It is additive-friendly: a new field is
 * allowed (it only has to be optional), a removed or renamed pinned field fails the build. Fields
 * outside these lists (worker, guardian and routing-config fields) are not part of the freeze.
 */
class AgentContractFreezeTest {
    private fun names(d: SerialDescriptor): Set<String> = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()

    private fun assertPinned(label: String, d: SerialDescriptor, pinned: Set<String>) {
        val missing = pinned - names(d)
        assertTrue(missing.isEmpty(), "$label lost frozen fields $missing (additive changes only within agent API v1)")
    }

    @Test
    fun requestAndResponseFieldNamesArePinned() {
        assertPinned(
            "AgentTurnRequest", AgentTurnRequest.serializer().descriptor,
            setOf("model", "message", "systemPrompt", "history", "tools", "maxSteps", "contextWindow", "async", "locality", "useEngineTools"),
        )
        assertPinned("ClientToolSpec", ClientToolSpec.serializer().descriptor, setOf("name", "description", "inputSchema"))
        assertPinned("PendingToolCall", PendingToolCall.serializer().descriptor, setOf("callId", "name", "arguments"))
        assertPinned("ToolResultsRequest", ToolResultsRequest.serializer().descriptor, setOf("results", "async"))
        assertPinned("ClientToolResult", ClientToolResult.serializer().descriptor, setOf("callId", "output", "isError"))
        assertPinned("ExecutedToolCallDto", ExecutedToolCallDto.serializer().descriptor, setOf("toolName", "argumentsJson", "resultText"))
        assertPinned(
            "AgentTurnResponse", AgentTurnResponse.serializer().descriptor,
            setOf("status", "runId", "reply", "toolCalls", "executedToolCalls", "steps", "error", "seq"),
        )
        assertPinned(
            "AgentInfo", AgentInfo.serializer().descriptor,
            setOf("apiVersion", "maxConcurrentRuns", "maxSteps", "toolWaitSeconds", "capabilities"),
        )
    }

    @Test
    fun statusValuesArePinned() {
        assertEquals("running", AgentTurnResponse.RUNNING)
        assertEquals("completed", AgentTurnResponse.COMPLETED)
        assertEquals("tool_calls", AgentTurnResponse.TOOL_CALLS)
        assertEquals("failed", AgentTurnResponse.FAILED)
        assertEquals("cancelled", AgentTurnResponse.CANCELLED)
    }

    @Test
    fun infoReportsApiVersionOne() {
        assertEquals(1, AgentInfo(maxConcurrentRuns = 1, maxSteps = 1, toolWaitSeconds = 1).apiVersion)
        assertTrue("tasks" in AgentInfo(maxConcurrentRuns = 1, maxSteps = 1, toolWaitSeconds = 1).capabilities)
    }
}
