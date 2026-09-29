package fi.italeino.aidos.engine.handshake

import fi.italeino.aidos.engine.approval.AppApprovalStatus
import fi.italeino.aidos.engine.approval.AppApprovalStore
import fi.italeino.aidos.engine.http.Capabilities
import fi.italeino.aidos.engine.http.TokenManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The handshake wire vocabulary of Aidos Engine API v1 (RFC-0103, "Handshake and transport").
 *
 * These strings are the Bundle contract between Engine and every SDK client, which are versioned
 * and released independently — they are frozen for API v1. The SDK keeps its own copy of the same
 * list (sdk/client's `EngineBinderHandshake`, in a separate Gradle project by design), and both
 * sides pin it in a test (`HandshakeWireContractTest` here, `HandshakeWireContractTest` in
 * sdk/client), so a rename on one side shows up as a failing test rather than a silent
 * "handshake failed" on a user's phone. See sdk/CONTRACT.md.
 */
object HandshakeWire {
    const val KEY_STATUS = "status"
    const val KEY_PORT = "port"
    const val KEY_TOKEN = "token"
    const val KEY_API_VERSION = "apiVersion"
    const val KEY_CAPABILITIES_JSON = "capabilitiesJson"
    const val KEY_DEEP_LINK = "deepLinkPendingIntent"

    const val STATUS_APPROVED = "APPROVED"
    const val STATUS_PENDING_APPROVAL = "PENDING_APPROVAL"
    const val STATUS_DENIED = "DENIED"

    const val API_VERSION = 1
}

/** Host-neutral result of a handshake; the Android layer turns it into a Bundle. */
data class HandshakeReply(
    val status: String,
    val port: Int = 0,
    val token: String = "",
    val apiVersion: Int = HandshakeWire.API_VERSION,
    val capabilitiesJson: String = "{}"
)

/**
 * Engine's handshake decision, without Binder or Android (RFC-0103, "Trust model"): who is
 * calling → is the user's approval on record → if so, mint that app's token.
 *
 * Extracted from `EngineHandshakeImpl` so the permission flow — first request lands as PENDING
 * and notifies the user once, approval unlocks a token, denial is sticky, revocation cuts a live
 * session — can be tested on the JVM instead of only on a device. `EngineHandshakeImpl` is now
 * just: resolve the caller from Binder, call [handshake], attach the deep-link `PendingIntent`.
 */
class HandshakeCore(
    private val store: AppApprovalStore,
    private val tokenManager: TokenManager,
    private val boundPort: suspend () -> Int?,
    private val capabilities: suspend () -> Capabilities,
    private val resolveDisplayName: (packageName: String) -> String = { it },
    /** Called exactly once per app, on its first ever handshake (Android: posts the notification). */
    private val onFirstRequest: suspend (packageName: String, displayName: String) -> Unit = { _, _ -> }
) {
    /**
     * @param callerPackage the package Binder identified for the calling UID, or null if it could
     *   not be resolved — which is denied, never guessed at.
     * @throws IllegalStateException if the caller is approved but the HTTP server has no bound port.
     */
    suspend fun handshake(callerPackage: String?): HandshakeReply {
        if (callerPackage == null) return HandshakeReply(HandshakeWire.STATUS_DENIED)

        val existing = store.getApproval(callerPackage)
        if (existing == null) {
            val displayName = resolveDisplayName(callerPackage)
            store.recordFirstHandshake(callerPackage, displayName)
            onFirstRequest(callerPackage, displayName)
            return HandshakeReply(HandshakeWire.STATUS_PENDING_APPROVAL)
        }
        store.recordHandshakeAttempt(callerPackage)

        return when (existing.status) {
            AppApprovalStatus.APPROVED -> approved(callerPackage)
            AppApprovalStatus.DENIED -> HandshakeReply(HandshakeWire.STATUS_DENIED)
            AppApprovalStatus.PENDING -> HandshakeReply(HandshakeWire.STATUS_PENDING_APPROVAL)
        }
    }

    private suspend fun approved(callerPackage: String): HandshakeReply {
        val port = boundPort() ?: throw IllegalStateException("HTTP server not running or port not bound")
        // Per-app token: this replaces only callerPackage's previous token (TokenManager).
        val token = tokenManager.generateNewToken(subject = callerPackage)
        return HandshakeReply(
            status = HandshakeWire.STATUS_APPROVED,
            port = port,
            token = token.token,
            capabilitiesJson = Json.encodeToString(capabilities())
        )
    }
}
