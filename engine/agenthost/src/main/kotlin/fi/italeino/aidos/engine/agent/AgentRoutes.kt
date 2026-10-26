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
 * the same bearer tokens and per-app approval apply: an app that may call the Engine may run an
 * agent. Runs belong to the token subject that started them ([subjectOf]); another app's run id is
 * a 404. Per-agent and per-tool authorization (AGENT-MANAGEMENT.md) is not enforced here yet; the
 * only tools a run can use are the ones the calling app hands it.
 */
fun Route.agentRoutes(
    runtime: AgentRuntime,
    subjectOf: (ApplicationCall) -> String = { it.principal<UserIdPrincipal>()?.name ?: "anonymous" },
) {
    get("/v1/agent/info") { call.respond(runtime.info()) }

    post("/v1/agent/turns") {
        guarded(call) { call.respond(runtime.start(subjectOf(call), call.receive<AgentTurnRequest>())) }
    }

    get("/v1/agent/runs") { call.respond(runtime.list(subjectOf(call))) }

    // Task snapshot. ?since=<seq>&waitMs=<n> long-polls until the run changes past `since`.
    get("/v1/agent/runs/{id}") {
        guarded(call) {
            val wait = (call.request.queryParameters["waitMs"]?.toLongOrNull() ?: 0L).coerceIn(0, MAX_WAIT_MS)
            call.respond(runtime.get(subjectOf(call), runId(call), call.request.queryParameters["since"]?.toIntOrNull(), wait.milliseconds))
        }
    }

    post("/v1/agent/runs/{id}/tool-results") {
        guarded(call) {
            val body = call.receive<ToolResultsRequest>()
            call.respond(runtime.submit(subjectOf(call), runId(call), body.results, body.async))
        }
    }

    delete("/v1/agent/runs/{id}") {
        val id = call.parameters["id"].orEmpty()
        if (runtime.cancel(subjectOf(call), id)) call.respond(HttpStatusCode.NoContent)
        else call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorDetail("no such run: $id", "invalid_request_error", code = "run_not_found")))
    }

    // Server-sent notifications for the caller's runs. Lossy and advisory (a client that was away
    // or missed one reconciles with GET /v1/agent/runs/{id}); `: ping` comments keep the line open.
    get("/v1/agent/events") {
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
