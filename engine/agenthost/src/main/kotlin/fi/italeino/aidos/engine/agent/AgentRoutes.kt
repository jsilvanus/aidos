package fi.italeino.aidos.engine.agent

import fi.italeino.aidos.engine.http.ErrorDetail
import fi.italeino.aidos.engine.http.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * the /v1/agent routes (additive to Engine API v1). Install inside the Engine's authenticated block so
 * the same bearer tokens and per-app approval apply: an app that may call the Engine may run an
 * agent. Per-agent and per-tool authorization (AGENT-MANAGEMENT.md) is not enforced here yet; the
 * only tools a run can use are the ones the calling app hands it.
 */
fun Route.agentRoutes(runtime: AgentRuntime) {
    get("/v1/agent/info") { call.respond(runtime.info()) }

    post("/v1/agent/turns") {
        guarded(call) { call.respond(runtime.start(call.receive<AgentTurnRequest>())) }
    }

    post("/v1/agent/runs/{id}/tool-results") {
        guarded(call) {
            val id = call.parameters["id"] ?: throw AgentRequestException("invalid_request", "missing run id")
            call.respond(runtime.submit(id, call.receive<ToolResultsRequest>().results))
        }
    }

    delete("/v1/agent/runs/{id}") {
        val id = call.parameters["id"].orEmpty()
        if (runtime.cancel(id)) call.respond(HttpStatusCode.NoContent)
        else call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorDetail("no such run: $id", "invalid_request_error", code = "run_not_found")))
    }
}

private suspend fun guarded(call: io.ktor.server.application.ApplicationCall, block: suspend () -> Unit) {
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
