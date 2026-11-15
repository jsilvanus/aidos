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
    suspend fun admin(call: ApplicationCall, block: suspend () -> Unit) = adminGuard(call, adminSubjects, block)

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

/** Shared by every `/v1/admin/` route group: only the Engine's own local client may call, and errors map to 400 and 409. */
internal suspend fun adminGuard(call: ApplicationCall, adminSubjects: Set<String>, block: suspend () -> Unit) {
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

/** `/v1/admin/approvals`: what is waiting for the user, and the user's answer (RFC-0115, D59). */
internal fun Route.adminApprovalsRoutes(control: fi.italeino.aidos.engine.control.ApprovalsControl, adminSubjects: Set<String>) {
    get("/v1/admin/approvals") { adminGuard(call, adminSubjects) { call.respond(control.pending()) } }
    post("/v1/admin/approvals/{id}") {
        adminGuard(call, adminSubjects) {
            val r = call.receive<fi.italeino.aidos.engine.control.ApprovalDecision>()
            val ok = control.decide(call.parameters["id"].orEmpty(), r.allow)
            if (ok) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorDetail("nothing is waiting under that id", "invalid_request_error", code = "not_found")))
        }
    }
}

/** `POST /v1/admin/shutdown`: answers 202, then asks the host to stop. Only installed when the host provides a callback. */
internal fun Route.adminShutdownRoute(shutdown: () -> Unit, adminSubjects: Set<String>) {
    post("/v1/admin/shutdown") {
        adminGuard(call, adminSubjects) {
            call.respond(HttpStatusCode.Accepted)
            Thread({ Thread.sleep(200); shutdown() }, "engine-admin-shutdown").apply { isDaemon = true }.start()
        }
    }
}

/** `/v1/admin/tunnels`: SSH reverse tunnels (RFC-0108, RFC-0115). Loopback and admin only. */
internal fun Route.adminTunnelsRoutes(control: fi.italeino.aidos.engine.control.TunnelsControl, adminSubjects: Set<String>) {
    suspend fun admin(call: ApplicationCall, block: suspend () -> Unit) = adminGuard(call, adminSubjects, block)
    fun id(call: ApplicationCall) = call.parameters["id"].orEmpty()

    get("/v1/admin/tunnels") { admin(call) { call.respond(control.list()) } }
    post("/v1/admin/tunnels") {
        admin(call) { call.respond(fi.italeino.aidos.engine.control.ControlValue(control.create(call.receive()))) }
    }
    post("/v1/admin/tunnels/{id}/probe") { admin(call) { call.respond(fi.italeino.aidos.engine.control.ControlValue(control.probeHostKey(id(call)))) } }
    post("/v1/admin/tunnels/{id}/confirm") { admin(call) { control.confirmHostKey(id(call)); call.respond(HttpStatusCode.NoContent) } }
    post("/v1/admin/tunnels/{id}/start") { admin(call) { control.start(id(call)); call.respond(HttpStatusCode.NoContent) } }
    post("/v1/admin/tunnels/{id}/stop") { admin(call) { control.stop(id(call)); call.respond(HttpStatusCode.NoContent) } }
    post("/v1/admin/tunnels/{id}/rotate-key") { admin(call) { call.respond(fi.italeino.aidos.engine.control.ControlValue(control.rotateKey(id(call)))) } }
    delete("/v1/admin/tunnels/{id}") { admin(call) { control.delete(id(call)); call.respond(HttpStatusCode.NoContent) } }
}

/** `/v1/admin/peers`: paired AIDIN peers and what the user lets each do (RFC-0107, RFC-0108, RFC-0115). Loopback and admin only. */
internal fun Route.adminPeersRoutes(control: fi.italeino.aidos.engine.control.PeersControl, adminSubjects: Set<String>) {
    suspend fun admin(call: ApplicationCall, block: suspend () -> Unit) = adminGuard(call, adminSubjects, block)
    fun id(call: ApplicationCall) = call.parameters["id"].orEmpty()
    val no = HttpStatusCode.NoContent

    get("/v1/admin/peers") { admin(call) { call.respond(control.status()) } }
    put("/v1/admin/peers/{id}/hosts-agent-loops") { admin(call) { control.setHostsAgentLoops(id(call), call.receive<fi.italeino.aidos.engine.control.PeerFlagRequest>().enabled); call.respond(no) } }
    put("/v1/admin/peers/{id}/preferred") { admin(call) { control.setPreferred(id(call), call.receive<fi.italeino.aidos.engine.control.PeerFlagRequest>().enabled); call.respond(no) } }
    put("/v1/admin/peers/{id}/remote-access") { admin(call) { control.setRemoteAccess(id(call), call.receive<fi.italeino.aidos.engine.control.PeerFlagRequest>().enabled); call.respond(no) } }
    post("/v1/admin/peers/{id}/hints") { admin(call) { val r = call.receive<fi.italeino.aidos.engine.control.PeerHintRequest>(); control.addHint(id(call), r.host, r.port); call.respond(no) } }
    post("/v1/admin/peers/{id}/hints/remove") { admin(call) { control.removeHint(id(call), call.receive<fi.italeino.aidos.engine.control.PeerHintRemoveRequest>().hint); call.respond(no) } }
    put("/v1/admin/peers/{id}/tool-allowlist") { admin(call) { val r = call.receive<fi.italeino.aidos.engine.control.PeerToolAllowlistRequest>(); control.setToolAllowlist(id(call), r.app, r.tools); call.respond(no) } }
}

/** `/v1/admin/apps`: the connected apps and the user's decision on each (RFC-0103, D49, RFC-0104, RFC-0115). Loopback and admin only. */
internal fun Route.adminAppsRoutes(control: fi.italeino.aidos.engine.control.AppsControl, adminSubjects: Set<String>) {
    suspend fun admin(call: ApplicationCall, block: suspend () -> Unit) = adminGuard(call, adminSubjects, block)
    fun id(call: ApplicationCall) = call.parameters["id"].orEmpty()
    suspend fun answer(call: ApplicationCall, found: Boolean) {
        if (found) call.respond(HttpStatusCode.NoContent)
        else call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorDetail("no such app", "invalid_request_error", code = "not_found")))
    }

    get("/v1/admin/apps") { admin(call) { call.respond(control.list()) } }
    post("/v1/admin/apps/{id}/approve") { admin(call) { answer(call, control.approve(id(call))) } }
    post("/v1/admin/apps/{id}/deny") { admin(call) { answer(call, control.deny(id(call))) } }
    post("/v1/admin/apps/{id}/revoke") { admin(call) { answer(call, control.revoke(id(call))) } }
    post("/v1/admin/apps/{id}/undo-deny") { admin(call) { answer(call, control.undoDeny(id(call))) } }
    put("/v1/admin/apps/{id}/agent-allowed") { admin(call) { answer(call, control.setAgentAllowed(id(call), call.receive<fi.italeino.aidos.engine.control.AppAgentAllowedRequest>().allowed)) } }
    put("/v1/admin/apps/{id}/locality-limit") { admin(call) { answer(call, control.setLocalityLimit(id(call), call.receive<fi.italeino.aidos.engine.control.AppLocalityLimitRequest>().limit)) } }
}

/** `GET /v1/admin/status`: whether the Engine is up and what it holds (RFC-0115). Loopback and admin only. */
internal fun Route.adminStatusRoute(control: fi.italeino.aidos.engine.control.StatusControl, adminSubjects: Set<String>) {
    get("/v1/admin/status") { adminGuard(call, adminSubjects) { call.respond(control.status()) } }
}

/** `/v1/admin/vault`: lock state of the Engine's vault and unlocking it with its passphrase (RFC-0115, D56). Loopback and admin only. */
internal fun Route.adminVaultRoutes(control: fi.italeino.aidos.engine.control.VaultControl, adminSubjects: Set<String>) {
    get("/v1/admin/vault") { adminGuard(call, adminSubjects) { call.respond(control.status()) } }
    post("/v1/admin/vault/unlock") {
        adminGuard(call, adminSubjects) {
            control.unlock(call.receive<fi.italeino.aidos.engine.control.VaultUnlockRequest>().passphrase.toCharArray())
            call.respond(HttpStatusCode.NoContent)
        }
    }
    post("/v1/admin/vault/lock") { adminGuard(call, adminSubjects) { control.lock(); call.respond(HttpStatusCode.NoContent) } }
}
