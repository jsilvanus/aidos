package fi.italeino.aidos.engine.control

import kotlinx.serialization.Serializable

/**
 * The LAN sharing group of `EngineControl` (RFC-0115, D58, D61): what a UI can do to an Engine's LAN sharing
 * (RFC-0051), whether the Engine runs in the same process (`LocalLanControl`) or as a background service
 * reached over the loopback admin API (`HttpLanControl`). The interface is the only thing the pane's presenter
 * knows, which is what lets the app attach to a service without changing the pane.
 */
interface LanControl {
    suspend fun status(): LanStatus
    suspend fun setEnabled(enabled: Boolean, port: Int? = null): LanStatus

    /** A single-use pairing link (`aidos-pair://...`) for an SDK client; the Engine must have LAN sharing on. */
    suspend fun startCodePairing(label: String, lifetimeSeconds: Long): String

    /** Connection details for a generic client; the credential in the result is shown once and not kept. */
    suspend fun addManually(label: String, lifetimeSeconds: Long): LanManualPairing

    suspend fun revoke(deviceId: String)
}

@Serializable
data class LanDeviceInfo(val id: String, val label: String, val expiresAt: String, val revoked: Boolean)

@Serializable
data class LanStatus(
    val enabled: Boolean,
    val port: Int,
    /** The port actually bound, or null while the listener is off. */
    val boundPort: Int? = null,
    val fingerprintSha256: String? = null,
    val devices: List<LanDeviceInfo> = emptyList(),
)

@Serializable
data class LanManualPairing(
    val url: String,
    val fingerprintSha256: String?,
    val credential: String,
    val expiresAt: String,
    val deviceId: String,
)

@Serializable data class LanEnableRequest(val enabled: Boolean, val port: Int? = null)
@Serializable data class LanPairingRequest(val label: String, val lifetimeSeconds: Long)
@Serializable data class LanPairingLink(val link: String)
