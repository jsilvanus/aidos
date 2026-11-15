package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.control.LanControl
import fi.italeino.aidos.engine.control.LanDeviceInfo
import fi.italeino.aidos.engine.control.LanManualPairing
import fi.italeino.aidos.engine.control.LanStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [LanControl] over this process's [DesktopLanSharing]: the in-process path, and what the admin API serves from. */
class LocalLanControl(private val lan: DesktopLanSharing) : LanControl {
    override suspend fun status(): LanStatus = LanStatus(
        enabled = lan.isEnabled,
        port = lan.port,
        boundPort = lan.boundPort,
        fingerprintSha256 = lan.fingerprint(),
        devices = lan.devices.list().map { LanDeviceInfo(it.id, it.label, it.expiresAt, it.revoked) },
    )

    override suspend fun setEnabled(enabled: Boolean, port: Int?): LanStatus {
        withContext(Dispatchers.IO) { lan.setEnabled(enabled, port ?: lan.port) }
        return status()
    }

    override suspend fun startCodePairing(label: String, lifetimeSeconds: Long): String =
        withContext(Dispatchers.IO) { lan.startCodePairingUri(label.trim(), lifetimeSeconds) }

    override suspend fun addManually(label: String, lifetimeSeconds: Long): LanManualPairing = withContext(Dispatchers.IO) {
        val m = lan.addManually(label.trim(), lifetimeSeconds)
        LanManualPairing(m.url, m.fingerprintSha256, m.credential, m.expiresAt, m.deviceId)
    }

    override suspend fun revoke(deviceId: String) {
        withContext(Dispatchers.IO) { lan.revoke(deviceId) }
    }
}
