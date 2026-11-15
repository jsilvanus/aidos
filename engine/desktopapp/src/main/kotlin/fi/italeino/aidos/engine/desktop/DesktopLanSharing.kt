package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.approval.AppApprovalStore
import fi.italeino.aidos.engine.http.EngineHttpServer
import fi.italeino.aidos.engine.lan.ExternalDeviceGrants
import fi.italeino.aidos.engine.lan.IssuedExternalDevice
import fi.italeino.aidos.engine.lan.LanDiscovery
import fi.italeino.aidos.engine.lan.LanSslConfig
import fi.italeino.aidos.engine.lan.LanTlsIdentity
import fi.italeino.aidos.engine.lan.PairingCodes
import fi.italeino.aidos.sdk.client.LanPairingUri
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * RFC-0051 on the desktop: opt-in HTTPS sharing of this Engine with other devices on the LAN.
 *
 * Off by default; the loopback listener is unaffected either way. Adding a device is the user's
 * approval of that device for model use: it appears in the same approval store as every other
 * app (Connected Apps), under an `ext:<id>` subject, with the agent grant OFF until the user
 * turns it on there, and with the same per-app locality limit and revocation.
 */
class DesktopLanSharing(
    private val stateDir: File,
    private val approvalStore: AppApprovalStore,
) {
    @Serializable
    private data class Settings(val enabled: Boolean = false, val port: Int = DEFAULT_PORT)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val settingsFile = File(stateDir, "lan.json")

    val devices = ExternalDeviceGrants(
        file = File(stateDir, "external-devices.json"),
        onIssued = { grant ->
            // Pairing a device is approving it for model use; agent use stays a separate, later grant (D49).
            runBlocking {
                approvalStore.recordFirstHandshake(grant.subject, "LAN: ${grant.label}")
                approvalStore.approveApp(grant.subject)
            }
        },
    )
    val pairing = PairingCodes(devices)

    @Volatile
    private var ssl: LanSslConfig? = null

    @Volatile
    private var activeServer: EngineHttpServer? = null

    @Volatile
    private var announcement: LanDiscovery.Announcement? = null

    /** The port the LAN listener is actually bound to (differs from [port] only when that is 0), or null while it is off. */
    @Volatile
    var boundPort: Int? = null
        private set

    val isEnabled: Boolean get() = readSettings().enabled
    val port: Int get() = readSettings().port

    /** Persists the choice and applies it to the running server, if any. */
    fun setEnabled(enabled: Boolean, port: Int = readSettings().port) {
        require(port in 0..65535) { "invalid port" }
        settingsFile.parentFile?.mkdirs()
        settingsFile.writeText(json.encodeToString(Settings.serializer(), Settings(enabled, port)))
        activeServer?.let { stop(); if (enabled) start(it) }
    }

    /** Called by the host once its server is up: starts the LAN listener only if the user turned it on. */
    fun start(server: EngineHttpServer) {
        activeServer = server
        val s = readSettings()
        if (!s.enabled) return
        val identity = ssl ?: LanTlsIdentity.loadOrCreate(File(stateDir, "lan-tls.p12"), password()).also { ssl = it }
        server.startLan(identity, io.ktor.server.netty.Netty, port = s.port)
        boundPort = runBlocking { server.getBoundLanPort() }
        // RFC-0051 "Service discovery": announce the listener so clients need not type an address. Best effort:
        // a network without multicast just means the address has to be entered.
        announcement = runCatching {
            LanDiscovery.announce("aidos-engine-${java.net.InetAddress.getLocalHost().hostName}".take(60), boundPort ?: s.port, identity.fingerprintSha256)
        }.getOrNull()
    }

    fun stop() {
        announcement?.close()
        announcement = null
        boundPort = null
        activeServer?.stopLan()
    }

    /** SHA-256 fingerprint a client pins; null until the listener has been enabled once. */
    fun fingerprint(): String? = ssl?.fingerprintSha256
        ?: File(stateDir, "lan-tls.p12").takeIf { it.exists() }
            ?.let { LanTlsIdentity.loadOrCreate(it, password()).also { id -> ssl = id }.fingerprintSha256 }

    /** Mode A: a short-lived code for an SDK-aware client to exchange at `/lan/v1/pair`. */
    fun startCodePairing(label: String, deviceLifetimeSeconds: Long): String =
        pairing.start(label, deviceLifetimeSeconds)

    /**
     * Mode A as shown to the user: `aidos-pair://host:port/code?fp=...`, which is what the QR code holds and what
     * the SDK's `LanPairing.redeem` takes. The fingerprint is inside it so the code is only ever sent to the
     * certificate on this screen. Needs LAN sharing to have been enabled once (that is what creates the certificate).
     */
    fun startCodePairingUri(label: String, deviceLifetimeSeconds: Long): String {
        val fp = fingerprint() ?: error("Turn LAN sharing on first: the Engine has no TLS identity yet")
        val host = LanTlsIdentity.lanAddresses().firstOrNull() ?: "localhost"
        return LanPairingUri(host, boundPort ?: port, startCodePairing(label, deviceLifetimeSeconds), fp).toString()
    }

    /** Mode B: a credential plus the connection details to enter or scan into a generic client. */
    fun addManually(label: String, deviceLifetimeSeconds: Long): ManualPairing {
        val issued = devices.issue(label, Instant.now().plusSeconds(deviceLifetimeSeconds))
        return describe(issued)
    }

    fun describe(issued: IssuedExternalDevice): ManualPairing {
        val host = LanTlsIdentity.lanAddresses().firstOrNull() ?: "localhost"
        return ManualPairing(
            url = "https://$host:${boundPort ?: port}",
            fingerprintSha256 = fingerprint(),
            credential = issued.credential,
            expiresAt = issued.grant.expiresAt,
            deviceId = issued.grant.id,
        )
    }

    /** Revokes the credential and the device's approval, so an in-flight session ends at its next request. */
    fun revoke(deviceId: String) {
        devices.revoke(deviceId)
        runBlocking { approvalStore.revokeApproval(fi.italeino.aidos.engine.lan.ExternalDeviceGrant.subjectFor(deviceId)) }
    }

    data class ManualPairing(
        val url: String,
        val fingerprintSha256: String?,
        val credential: String,
        val expiresAt: String,
        val deviceId: String,
    )

    private fun readSettings(): Settings =
        runCatching { json.decodeFromString(Settings.serializer(), settingsFile.readText()) }.getOrDefault(Settings())

    /** Key-store password: random, kept beside the store. The store is a self-signed LAN identity, not a secret vault. */
    private fun password(): String {
        val f = File(stateDir, "lan-tls.pw")
        if (f.exists()) return f.readText().trim()
        val bytes = ByteArray(24).also(SecureRandom()::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }.also { stateDir.mkdirs(); f.writeText(it) }
    }

    companion object {
        const val DEFAULT_PORT = 18443
    }
}
