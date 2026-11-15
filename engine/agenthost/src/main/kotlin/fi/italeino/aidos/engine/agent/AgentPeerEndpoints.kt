package fi.italeino.aidos.engine.agent

import dev.aidos.aidin.AGENT_ENDPOINT_PREFIX
import dev.aidos.aidin.LinkMessage
import dev.aidos.aidin.NodeId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

/** Wire bodies of the peer agent endpoints (RFC-0107). All are POSTs over the authenticated peer link. */
object AgentPeerWire {
    const val TURNS = AGENT_ENDPOINT_PREFIX + "turns"
    const val RUN_GET = AGENT_ENDPOINT_PREFIX + "runs/get"
    const val RUN_RESULTS = AGENT_ENDPOINT_PREFIX + "runs/results"
    const val RUN_CANCEL = AGENT_ENDPOINT_PREFIX + "runs/cancel"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** [app] is the calling app on the origin Engine; a run is owned by (peer node, app). */
    @Serializable data class TurnBody(val app: String, val request: AgentTurnRequest)
    @Serializable data class RunBody(val app: String, val runId: String, val since: Int? = null, val waitMs: Long? = null)
    @Serializable data class ResultsBody(val app: String, val runId: String, val results: List<ClientToolResult>, val async: Boolean = false)

    fun owner(peer: NodeId, app: String) = "peer:${peer.value}:$app"
}

/**
 * The agent host's side of RFC-0107: runs an agent loop for a trusted peer.
 *
 * Reaching this handler already required the peer link's `delegate` and `agent` scopes (checked by
 * the node before dispatch). Here:
 * - runs are owned by (peer node, calling app), so one peer or app cannot read or steer another's run;
 * - the loop's own model calls are held to `local_only`: the agent host serves from its own models and
 *   never delegates the loop or its model calls onward (hop limit 1);
 * - tools are not run here at all. They are the origin's tools, handed over as specs; a call to one
 *   comes back to the origin as a pending call, which is how "tools execute only on the device that owns them" holds.
 */
class AgentPeerEndpoints(private val runtime: AgentRuntime) {

    suspend fun handle(fromPeer: NodeId, request: LinkMessage.DelegateRequest): LinkMessage.DelegateResponse {
        fun reply(status: Int, body: String) = LinkMessage.DelegateResponse(request.requestId, status, body)
        fun error(e: AgentRequestException) = reply(e.httpStatus, errorJson(e.code, e.message ?: e.code))
        return try {
            when (request.endpoint) {
                AgentPeerWire.TURNS -> {
                    val body = AgentPeerWire.json.decodeFromString(AgentPeerWire.TurnBody.serializer(), request.bodyJson)
                    val response = runtime.start(AgentPeerWire.owner(fromPeer, body.app), body.request.copy(locality = "local_only"))
                    reply(200, AgentPeerWire.json.encodeToString(AgentTurnResponse.serializer(), response))
                }
                AgentPeerWire.RUN_GET -> {
                    val body = AgentPeerWire.json.decodeFromString(AgentPeerWire.RunBody.serializer(), request.bodyJson)
                    val wait = (body.waitMs ?: 0L).coerceIn(0L, 30_000L).milliseconds
                    val response = runtime.get(AgentPeerWire.owner(fromPeer, body.app), body.runId, body.since, wait)
                    reply(200, AgentPeerWire.json.encodeToString(AgentTurnResponse.serializer(), response))
                }
                AgentPeerWire.RUN_RESULTS -> {
                    val body = AgentPeerWire.json.decodeFromString(AgentPeerWire.ResultsBody.serializer(), request.bodyJson)
                    val response = runtime.submit(AgentPeerWire.owner(fromPeer, body.app), body.runId, body.results, body.async)
                    reply(200, AgentPeerWire.json.encodeToString(AgentTurnResponse.serializer(), response))
                }
                AgentPeerWire.RUN_CANCEL -> {
                    val body = AgentPeerWire.json.decodeFromString(AgentPeerWire.RunBody.serializer(), request.bodyJson)
                    if (runtime.cancel(AgentPeerWire.owner(fromPeer, body.app), body.runId)) reply(204, "")
                    else reply(404, errorJson("run_not_found", "no such run: ${body.runId}"))
                }
                else -> reply(404, errorJson("unknown_endpoint", "no agent endpoint ${request.endpoint}"))
            }
        } catch (e: AgentRequestException) {
            error(e)
        } catch (e: kotlinx.serialization.SerializationException) {
            reply(400, errorJson("invalid_request", "malformed agent request"))
        }
    }

    private fun errorJson(code: String, message: String): String =
        """{"error":{"message":${Json.encodeToString(kotlinx.serialization.serializer<String>(), message)},"type":"invalid_request_error","code":"$code"}}"""
}
