package fi.italeino.aidos.engine.desktop

import dev.aidos.tcr.engine.LanDeviceUi
import dev.aidos.tcr.engine.LanPairingUi
import dev.aidos.tcr.engine.LanSharingUiState
import fi.italeino.aidos.engine.control.LanControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Presenter for the LAN sharing pane (RFC-0051). It knows only [LanControl] (RFC-0115, D61): the same pane works
 * over the in-process Engine ([LocalLanControl]) or a background Engine service (`HttpLanControl`). Every action
 * is one control call followed by a refresh; failures show in the pane.
 */
class DesktopLanSharingModel(
    private val scope: CoroutineScope,
    private val control: () -> LanControl,
) {
    private val _state = MutableStateFlow(LanSharingUiState())
    val state: StateFlow<LanSharingUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch { act { } }
    }

    private fun act(block: suspend (LanControl) -> Unit) = scope.launch {
        try {
            val c = control()
            block(c)
            val s = c.status()
            val now = Instant.now()
            _state.update {
                it.copy(
                    enabled = s.enabled, port = s.port, fingerprintSha256 = s.fingerprintSha256, error = null,
                    devices = s.devices.map { d -> LanDeviceUi(d.id, d.label, d.expiresAt, d.revoked, expired = Instant.parse(d.expiresAt).isBefore(now)) }
                        .sortedByDescending { d -> d.expiresAt },
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: e::class.simpleName) }
        }
    }

    fun setEnabled(enabled: Boolean) { act { it.setEnabled(enabled) } }

    fun addByCode(label: String, lifetimeHours: Long) {
        act { c ->
            val uri = c.startCodePairing(label.trim(), lifetimeHours * 3600)
            val code = uri.substringAfterLast('/').substringBefore('?')
            _state.update { it.copy(pairing = LanPairingUi.Code(label.trim(), uri, code)) }
        }
    }

    fun addManually(label: String, lifetimeHours: Long) {
        act { c ->
            val m = c.addManually(label.trim(), lifetimeHours * 3600)
            _state.update { it.copy(pairing = LanPairingUi.Manual(label.trim(), m.url, m.fingerprintSha256, m.credential, m.expiresAt)) }
        }
    }

    fun revoke(deviceId: String) { act { it.revoke(deviceId) } }

    fun dismissPairing() = _state.update { it.copy(pairing = null) }
}
