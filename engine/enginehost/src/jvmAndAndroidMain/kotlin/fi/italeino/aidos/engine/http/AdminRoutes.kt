package fi.italeino.aidos.engine.http

import fi.italeino.aidos.engine.control.LanControl
import fi.italeino.aidos.engine.control.LanEnableRequest
import fi.italeino.aidos.engine.control.LanPairingLink
import fi.italeino.aidos.engine.control.LanPairingRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/**
 * `/v1/admin/lan` (RFC-0115, D58). Install inside the authenticated block of the loopback listener only. Every
 * route checks the caller is one of [adminSubjects]: an approved app's token authenticates but is not an admin.
 */
internal fun Route.adminLanRoutes(control: LanControl, adminSubjects: Set<String>) {
    suspend fun admin(call: ApplicationCall, block: suspend () -> Unit) {
        val who = call.principal<UserIdPrincipal>()?.name
        if (who == null || who !in adminSubjects) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(ErrorDetail("the Engine management API is for the Engine's own local client", "permission_error", code = "not_admin")))
            return
        }
        try {
            block()
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail(e.message ?: "invalid request", "invalid_request_error", code = "invalid_request")))
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse(ErrorDetail(e.message ?: "not possible now", "invalid_request_error", code = "conflict")))
        }
    }

    get("/v1/admin/lan") { admin(call) { call.respond(control.status()) } }
    put("/v1/admin/lan") { admin(call) { val r = call.receive<LanEnableRequest>(); call.respond(control.setEnabled(r.enabled, r.port)) } }
    post("/v1/admin/lan/pairing-code") {
        admin(call) { val r = call.receive<LanPairingRequest>(); call.respond(LanPairingLink(control.startCodePairing(r.label, r.lifetimeSeconds))) }
    }
    post("/v1/admin/lan/devices") {
        admin(call) { val r = call.receive<LanPairingRequest>(); call.respond(control.addManually(r.label, r.lifetimeSeconds)) }
    }
    delete("/v1/admin/lan/devices/{id}") {
        admin(call) { control.revoke(call.parameters["id"].orEmpty()); call.respond(HttpStatusCode.NoContent) }
    }
}
