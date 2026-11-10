package fi.italeino.aidos.engine.agent

import fi.italeino.aidos.engine.http.ErrorDetail
import fi.italeino.aidos.engine.http.ErrorResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import fi.italeino.aidos.engine.inference.Locality
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.http.CacheControl
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val eventJson = Json { encodeDefaults = true }

/** Longest a GET on a run may block waiting for a change. */
private const val MAX_WAIT_MS = 30_000L

/**
 * The agent routes (additive to Engine API v1). Install inside the Engine's authenticated block so
 * the same bearer tokens and per-app approval apply, and (D49) running an agent also needs the
 * separate per-app agent grant, [mayRunAgent]. Runs belong to the token subject that started them ([subjectOf]); another app's run id is
 * a 404. Per-agent and per-tool authorization (AGENT-MANAGEMENT.md) is not enforced here yet; the
 * only tools a run can use are the ones the calling app hands it.
 */
fun Route.agentRoutes(
    runtime: AgentRuntime,
    subjectOf: (ApplicationCall) -> String = { it.principal<UserIdPrincipal>()?.name ?: "anonymous" },
    /** Applies the host's locality layering (app and Engine limits); the default is the caller's own limit, else local only. */
    localityFor: suspend (subject: String?, requested: Locality?) -> Locality = { _, requested -> requested ?: Locality.LOCAL_ONLY },
    /**
     * D49: an inference grant is not an agent grant. Whether [subject] may use the agent routes at all;
     * the host answers from the per-app agent grant (default deny). The default allows, for hosts and
     * tests that have no per-app approval.
     */
    mayRunAgent: suspend (subject: String) -> Boolean = { true },
) {
    // Answers 403 and returns true when the caller has no agent grant; each handler but /info starts with it.
    suspend fun refused(call: ApplicationCall): Boolean {
        if (mayRunAgent(subjectOf(call))) return false
        call.respond(
            HttpStatusCode.Forbidden,
            ErrorResponse(ErrorDetail("this app has not been granted agent access; the user can allow it in Engine's connected apps", "permission_error", code = "agent_not_granted")),
        )
        return true
    }

    get("/v1/agent/info") { call.respond(runtime.info()) }

    post("/v1/agent/turns") {
        if (refused(call)) return@post
        guarded(call) {
            val request = call.receive<AgentTurnRequest>()
            val requested = request.locality?.let {
                Locality.parse(it) ?: throw AgentRequestException("invalid_request", "locality must be local_only, trusted_cluster or any")
            }
            val subject = subjectOf(call)
            val effective = localityFor(subject, requested)
            call.respond(runtime.start(subject, request.copy(locality = effective.wire)))
        }
    }

    get("/v1/agent/runs") {
        if (refused(call)) return@get
        call.respond(runtime.list(subjectOf(call)))
    }

    // Task snapshot. ?since=<seq>&waitMs=<n> long-polls until the run changes past `since`.
    get("/v1/agent/runs/{id}") {
        if (refused(call)) return@get
        guarded(call) {
            val wait = (call.request.queryParameters["waitMs"]?.toLongOrNull() ?: 0L).coerceIn(0, MAX_WAIT_MS)
            call.respond(runtime.get(subjectOf(call), runId(call), call.request.queryParameters["since"]?.toIntOrNull(), wait.milliseconds))
        }
    }

    post("/v1/agent/runs/{id}/tool-results") {
        if (refused(call)) return@post
        guarded(call) {
            val body = call.receive<ToolResultsRequest>()
            call.respond(runtime.submit(subjectOf(call), runId(call), body.results, body.async))
        }
    }

    delete("/v1/agent/runs/{id}") {
        if (refused(call)) return@delete
        val id = call.parameters["id"].orEmpty()
        if (runtime.cancel(subjectOf(call), id)) call.respond(HttpStatusCode.NoContent)
        else call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorDetail("no such run: $id", "invalid_request_error", code = "run_not_found")))
    }

    // Server-sent notifications for the caller's runs. Lossy and advisory (a client that was away
    // or missed one reconciles with GET /v1/agent/runs/{id}); `: ping` comments keep the line open.
    get("/v1/agent/events") {
        if (refused(call)) return@get
        val owner = subjectOf(call)
        call.response.cacheControl(CacheControl.NoCache(null))
        call.respondTextWriter(ContentType.Text.EventStream) {
            coroutineScope {
                val channel = runtime.events.filter { it.first == owner }.map { it.second }.produceIn(this)
                write(": connected\n\n")
                flush()
                try {
                    while (isActive) {
                        val event = withTimeoutOrNull(15.seconds) { channel.receiveCatching() }
                        if (event == null) {
                            write(": ping\n\n")
                        } else {
                            val e = event.getOrNull() ?: break
                            write("event: ${e.type}\ndata: ${eventJson.encodeToString(AgentEvent.serializer(), e)}\n\n")
                        }
                        flush()
                    }
                } finally {
                    channel.cancel()
                }
            }
        }
    }
}

private fun runId(call: ApplicationCall): String =
    call.parameters["id"] ?: throw AgentRequestException("invalid_request", "missing run id")

private suspend fun guarded(call: ApplicationCall, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: AgentRequestException) {
        call.respond(HttpStatusCode.fromValue(e.httpStatus), ErrorResponse(ErrorDetail(e.message ?: e.code, "invalid_request_error", code = e.code)))
    } catch (e: kotlinx.serialization.SerializationException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail(e.message ?: "invalid body", "invalid_request_error", code = "invalid_request")))
    } catch (e: io.ktor.server.plugins.BadRequestException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail(e.message ?: "invalid body", "invalid_request_error", code = "invalid_request")))
    }
}
