package fi.italeino.aidos.engine.agent

import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Duration

/**
 * What the `/v1/agent` routes need from whatever runs the loop. Implemented by the local
 * [AgentRuntime], by [RemoteAgentClient] (the loop runs on a trusted peer, RFC-0107) and by
 * [RoutedAgentRuntime], which picks between them per turn. Every operation is scoped to [owner]:
 * another owner's run is reported as not found.
 */
interface AgentRunApi {
    val events: SharedFlow<Pair<String, AgentEvent>>

    fun info(): AgentInfo

    suspend fun start(owner: String, request: AgentTurnRequest): AgentTurnResponse

    suspend fun submit(owner: String, runId: String, results: List<ClientToolResult>, async: Boolean = false): AgentTurnResponse

    suspend fun get(owner: String, runId: String, since: Int? = null, waitFor: Duration = Duration.ZERO): AgentTurnResponse

    fun list(owner: String): List<AgentTurnResponse>

    fun cancel(owner: String, runId: String): Boolean
}
