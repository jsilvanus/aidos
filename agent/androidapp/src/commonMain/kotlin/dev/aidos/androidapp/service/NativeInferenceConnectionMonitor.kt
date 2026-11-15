package dev.aidos.androidapp.service

import dev.aidos.ui.system.NativeProcessHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform-neutral state machine for D49 `:native_inference` Binder crash isolation (RFC-0111, RFC-0112).
 *
 * Tracks [NativeProcessHealth] across `DeadObjectException` / `binderDied()` events and
 * orchestrates non-blocking warm restarts without losing UI or SQLite state.
 */
class NativeInferenceConnectionMonitor(
    private val onRequestWarmRestart: () -> Unit = {},
) {
    private val _health = MutableStateFlow(NativeProcessHealth.HEALTHY)
    val health: StateFlow<NativeProcessHealth> = _health.asStateFlow()

    var restartCount: Int = 0
        private set

    fun onConnected() {
        _health.value = if (restartCount > 0) {
            NativeProcessHealth.RECOVERED
        } else {
            NativeProcessHealth.HEALTHY
        }
    }

    fun onBinderDied() {
        restartCount++
        _health.value = NativeProcessHealth.CRASHED_WARM_RESTARTING
        onRequestWarmRestart()
    }

    fun onDeadObjectException() {
        onBinderDied()
    }

    fun acknowledgeRecovered() {
        if (_health.value == NativeProcessHealth.RECOVERED) {
            _health.value = NativeProcessHealth.HEALTHY
        }
    }
}
