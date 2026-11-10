package fi.italeino.sshtunnel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds one SSH tunnel up, doing what autossh does: connect, keep the forward open, and reconnect
 * with capped exponential backoff after network failures. Terminal problems (changed host key,
 * refused key, refused forward) stop the loop and are reported, never retried, because retrying
 * them would only hammer the server or silently re-trust it.
 *
 * Has no UI, storage or policy. The caller decides when to [start] and [stop].
 */
class SshTunnel(
    private val spec: TunnelSpec,
    private val keys: KeyProvider,
    private val hostKeys: HostKeyPolicy,
    private val transport: SshTransport,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Stopped)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private var job: Job? = null
    private var session: SshSession? = null

    /** Starts the loop. Calling it while running does nothing; after a terminal state it restarts. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /** Stops and closes the connection. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        session?.close()
        session = null
        _state.value = TunnelState.Stopped
    }

    private suspend fun run() {
        var attempt = 0
        while (true) {
            _state.value = TunnelState.Connecting
            try {
                val key = keys.privateKey()
                    ?: run { _state.value = TunnelState.AuthRefused("no key available"); return }
                val s = try {
                    transport.connect(spec, key, hostKeys)
                } finally {
                    key.fill(0) // do not keep key material longer than the connect call needs it
                }
                session = s
                attempt = 0
                _state.value = TunnelState.Up(s.boundPort)
                s.awaitClosed()
                session = null
                // A clean close from the server side is still a lost tunnel: reconnect.
                attempt++
                backoff(attempt, "connection closed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: TunnelException.HostKeyRejected) {
                _state.value = TunnelState.HostKeyChanged(e.fingerprint); return
            } catch (e: TunnelException.AuthFailed) {
                _state.value = TunnelState.AuthRefused(e.message ?: "authentication failed"); return
            } catch (e: TunnelException.ForwardRefused) {
                _state.value = TunnelState.ForwardRefused(e.message ?: "forward refused"); return
            } catch (e: Exception) {
                attempt++
                backoff(attempt, e.message ?: e::class.simpleName ?: "error")
            }
        }
    }

    private suspend fun backoff(attempt: Int, cause: String) {
        val d = spec.backoff.delayMillis(attempt)
        _state.value = TunnelState.Backoff(attempt, d, cause)
        delay(d)
    }
}
