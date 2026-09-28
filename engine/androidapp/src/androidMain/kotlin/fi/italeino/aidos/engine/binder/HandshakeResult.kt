package fi.italeino.aidos.engine

import android.app.PendingIntent
import android.os.Bundle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import fi.italeino.aidos.engine.http.Capabilities

/**
 * Result of the Aidos Engine handshake Binder call (RFC-0103), carried across the Binder
 * boundary as a [Bundle] (see [toBundle]) rather than a hand-written AIDL parcelable, so that
 * Engine and independently-versioned callers stay compatible without matching field layouts.
 *
 * Handles both approval scenarios:
 *
 * 1. APPROVED (app is pre-approved or was just approved by user):
 *    - status = "APPROVED"
 *    - port: ephemeral HTTP server port (loopback only, 127.0.0.1)
 *    - token: bearer token for HTTP Authorization header
 *    - apiVersion: handshake API version (1 for MVP)
 *    - capabilitiesJson: JSON-serialized [Capabilities]
 *    - deepLinkPendingIntent: null
 *
 * 2. PENDING_APPROVAL (first handshake, needs user approval):
 *    - status = "PENDING_APPROVAL"
 *    - port: 0
 *    - token: empty string
 *    - apiVersion: 1
 *    - capabilitiesJson: "{}"
 *    - deepLinkPendingIntent: PendingIntent that opens ConnectedAppsScreen
 *
 * 3. DENIED (user explicitly denied this app):
 *    - status = "DENIED"
 *    - All other fields zero/empty
 */
data class HandshakeResult(
    val status: String = "APPROVED",  // "APPROVED", "PENDING_APPROVAL", or "DENIED"
    val port: Int = 0,
    val token: String = "",
    val apiVersion: Int = 1,
    val capabilitiesJson: String = "{}",  // JSON-serialized Capabilities
    val deepLinkPendingIntent: PendingIntent? = null
) {

    /** Bundle keys are documented on [fi.italeino.aidos.engine.IEngineHandshake.performHandshake]. */
    fun toBundle(): Bundle = Bundle().apply {
        putString("status", status)
        putInt("port", port)
        putString("token", token)
        putInt("apiVersion", apiVersion)
        putString("capabilitiesJson", capabilitiesJson)
        putParcelable("deepLinkPendingIntent", deepLinkPendingIntent)
    }
}
