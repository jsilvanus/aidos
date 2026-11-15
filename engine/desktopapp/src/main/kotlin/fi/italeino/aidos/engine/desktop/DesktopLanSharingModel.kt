package fi.italeino.aidos.engine.desktop

import dev.aidos.tcr.engine.LanDeviceUi
import dev.aidos.tcr.engine.LanPairingUi
import dev.aidos.tcr.engine.LanSharingUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/**
 * Presenter for the LAN sharing pane (RFC-0051). Every action goes through [DesktopLanSharing]; this only
 * turns its state into what the pane shows and keeps the visible state in step after each action.
 */
class DesktopLanSharingModel(private val host: DesktopEngineHost) {
    private val _state = MutableStateFlow(LanSharingUiState())
    val state: StateFlow<LanSharingUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    private val lan get() = host.lanSharing

    fun refresh() {
        val now = Instant.now()
        _state.update {
            it.copy(
                enabled = lan.isEnabled,
                port = lan.port,
                fingerprintSha256 = lan.fingerprint(),
                devices = lan.devices.list().map { g ->
                    LanDeviceUi(g.id, g.label, g.expiresAt, g.revoked, expired = Instant.parse(g.expiresAt).isBefore(now))
                }.sortedByDescending { it.expiresAt },
            )
        }
    }

    fun setEnabled(enabled: Boolean) = guarded {
        lan.setEnabled(enabled)
        // Enabling creates the certificate; if the server is running the listener starts now.
    }

    fun addByCode(label: String, lifetimeHours: Long) = guarded {
        val uri = lan.startCodePairingUri(label.trim(), lifetimeHours * 3600)
        val code = uri.substringAfterLast('/').substringBefore('?')
        _state.update { it.copy(pairing = LanPairingUi.Code(label.trim(), uri, code)) }
    }

    fun addManually(label: String, lifetimeHours: Long) = guarded {
        val m = lan.addManually(label.trim(), lifetimeHours * 3600)
        _state.update { it.copy(pairing = LanPairingUi.Manual(label.trim(), m.url, m.fingerprintSha256, m.credential, m.expiresAt)) }
    }

    fun revoke(deviceId: String) = guarded { lan.revoke(deviceId) }

    fun dismissPairing() = _state.update { it.copy(pairing = null) }

    private fun guarded(block: () -> Unit) {
        try {
            block()
            _state.update { it.copy(error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e::class.simpleName) }
        }
        refresh()
    }
}
