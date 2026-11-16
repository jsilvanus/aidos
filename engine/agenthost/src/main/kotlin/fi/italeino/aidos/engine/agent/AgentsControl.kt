package fi.italeino.aidos.engine.agent

import fi.italeino.aidos.engine.control.AdminAuth
import fi.italeino.aidos.engine.control.EngineControlException
import fi.italeino.aidos.engine.http.ErrorDetail
import fi.italeino.aidos.engine.http.ErrorResponse
import fi.italeino.aidos.engine.http.adminGuard
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The agents group of `EngineControl` (RFC-0115, D61): what the Engine's agent runtime is doing, the routing the user
 * chose for it, and cancelling a run. The Agents pane reads this instead of the in-process runtime, so it works over a
 * background Engine service. The DTOs are the ones the agent routes already put on the wire.
 */
interface AgentsControl {
    suspend fun overview(): AgentsOverview
    suspend fun setRouting(config: AgentRoutingConfigDto)
    suspend fun setRunLaunch(config: AgentRunLaunchConfigDto)

    /** Cancels any run regardless of owner (the user supervising). False when there is no such run. */
    suspend fun cancel(runId: String): Boolean
}

/** [available] is false while the Engine runs no agent runtime (it is stopped or still starting): there is nothing to show. */
@Serializable
data class AgentsOverview(
    val available: Boolean,
    val stats: AgentOverviewStatsDto = AgentOverviewStatsDto(0, 0, 0, 0, 0, 0, 0, emptyMap(), 0),
    val runs: List<AgentRunSummaryDto> = emptyList(),
)

/** [AgentsControl] over this process's [AgentRuntime]; [runtime] is read at each call because the Engine can restart. */
class LocalAgentsControl(private val runtime: () -> AgentRuntime?) : AgentsControl {
    override suspend fun overview(): AgentsOverview {
        val r = runtime() ?: return AgentsOverview(available = false)
        return AgentsOverview(available = true, stats = r.stats(), runs = r.listAll())
    }

    override suspend fun setRouting(config: AgentRoutingConfigDto) { runtime()?.setRoutingConfig(config) }
    override suspend fun setRunLaunch(config: AgentRunLaunchConfigDto) { runtime()?.setRunLaunchConfig(config) }
    override suspend fun cancel(runId: String): Boolean = runtime()?.cancelAny(runId) ?: false
}

/** `/v1/admin/agents`: loopback and admin only (mount through the host's admin routes). */
fun Route.adminAgentsRoutes(control: AgentsControl, adminSubjects: Set<String>) {
    suspend fun admin(call: ApplicationCall, block: suspend () -> Unit) = adminGuard(call, adminSubjects, block)
    get("/v1/admin/agents") { admin(call) { call.respond(control.overview()) } }
    put("/v1/admin/agents/routing") { admin(call) { control.setRouting(call.receive()); call.respond(HttpStatusCode.NoContent) } }
    put("/v1/admin/agents/run-launch") { admin(call) { control.setRunLaunch(call.receive()); call.respond(HttpStatusCode.NoContent) } }
    delete("/v1/admin/agents/runs/{id}") {
        admin(call) {
            if (control.cancel(call.parameters["id"].orEmpty())) call.respond(HttpStatusCode.NoContent)
            else call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorDetail("no such run", "invalid_request_error", code = "not_found")))
        }
    }
}

/** [AgentsControl] over a running Engine's loopback admin API. */
class HttpAgentsControl(
    private val baseUrl: String,
    token: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
    reloadToken: () -> String? = { null },
) : AgentsControl {
    private val auth = AdminAuth(token, reloadToken)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private suspend fun call(method: String, path: String, body: String? = null): HttpResponse<String> = withContext(Dispatchers.IO) {
        val b = HttpRequest.newBuilder(URI("$baseUrl$path")).timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer ${auth.token}").header("Content-Type", "application/json")
        auth.send(
            client,
            when (method) {
                "GET" -> b.GET().build()
                "DELETE" -> b.DELETE().build()
                else -> b.method(method, HttpRequest.BodyPublishers.ofString(body ?: "{}")).build()
            },
        )
    }

    private fun ok(r: HttpResponse<String>) {
        if (r.statusCode() !in 200..299) throw EngineControlException(r.statusCode(), "Engine answered ${r.statusCode()}")
    }

    override suspend fun overview(): AgentsOverview = call("GET", "/v1/admin/agents").also(::ok).let { json.decodeFromString(it.body()) }
    override suspend fun setRouting(config: AgentRoutingConfigDto) { ok(call("PUT", "/v1/admin/agents/routing", json.encodeToString(config))) }
    override suspend fun setRunLaunch(config: AgentRunLaunchConfigDto) { ok(call("PUT", "/v1/admin/agents/run-launch", json.encodeToString(config))) }

    override suspend fun cancel(runId: String): Boolean {
        val r = call("DELETE", "/v1/admin/agents/runs/" + URLEncoder.encode(runId, Charsets.UTF_8))
        return when (r.statusCode()) {
            204 -> true
            404 -> false
            else -> throw EngineControlException(r.statusCode(), "Engine answered ${r.statusCode()}")
        }
    }
}
