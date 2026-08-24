package fi.italeino.aidos.sdk.client

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import fi.italeino.aidos.engine.IEngineHandshake
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Real Binder handshake with Aidos Engine (RFC-0103). Binds to Engine's exported
 * `EngineService`, calls `IEngineHandshake.performHandshake()`, and parses the returned Bundle
 * (key contract documented on that AIDL method) into [HandshakeOutcome].
 *
 * One handshake is one bind/call/unbind cycle — Engine hands out a fresh port and token per
 * handshake (RFC-0103, "Handshake and transport"), so there is nothing to gain from holding the
 * service connection open between calls.
 */
internal class EngineBinderHandshake(private val context: Context) : HandshakePerformer {

    /**
     * Deep-link intent from the most recent PENDING_APPROVAL response, if any — opens Engine's
     * ConnectedAppsScreen (RFC-0103, "Trust model"). Null once consumed by a later handshake that
     * didn't return one.
     */
    var pendingApprovalIntent: PendingIntent? = null
        private set

    override suspend fun performHandshake(): HandshakeOutcome {
        val connected = bindAndAwait() ?: return HandshakeOutcome.NotInstalled
        return try {
            parseBundle(IEngineHandshake.Stub.asInterface(connected.binder).performHandshake())
        } catch (e: Exception) {
            HandshakeOutcome.Failed
        } finally {
            context.unbindService(connected.connection)
        }
    }

    private class Connected(val binder: IBinder, val connection: ServiceConnection)

    private suspend fun bindAndAwait(): Connected? {
        val result = CompletableDeferred<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                result.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                result.complete(null)
            }

            override fun onBindingDied(name: ComponentName?) {
                result.complete(null)
            }

            override fun onNullBinding(name: ComponentName?) {
                result.complete(null)
            }
        }

        val intent = Intent(HANDSHAKE_ACTION).setPackage(ENGINE_PACKAGE)
        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }
        if (!bound) return null

        val binder = withTimeoutOrNull(BIND_TIMEOUT_MS) { result.await() }
        if (binder == null) {
            context.unbindService(connection)
            return null
        }
        return Connected(binder, connection)
    }

    private fun parseBundle(bundle: Bundle): HandshakeOutcome {
        return when (bundle.getString("status")) {
            "APPROVED" -> {
                pendingApprovalIntent = null
                HandshakeOutcome.Approved(
                    HandshakeResponse(
                        port = bundle.getInt("port"),
                        token = bundle.getString("token") ?: "",
                        apiVersion = bundle.getInt("apiVersion", 1),
                        capabilities = parseCapabilitiesJson(bundle.getString("capabilitiesJson") ?: "{}")
                    )
                )
            }
            "PENDING_APPROVAL" -> {
                pendingApprovalIntent = bundle.getPendingIntentCompat("deepLinkPendingIntent")
                HandshakeOutcome.PendingApproval
            }
            "DENIED" -> {
                pendingApprovalIntent = null
                HandshakeOutcome.Denied
            }
            else -> {
                // A status this client doesn't recognize yet — RFC-0103's Bundle wire shape
                // tolerates that by design (see IEngineHandshake.aidl). Treat it as a failure
                // rather than silently mapping to any specific known state.
                pendingApprovalIntent = null
                HandshakeOutcome.Failed
            }
        }
    }

    private companion object {
        const val HANDSHAKE_ACTION = "fi.italeino.aidos.engine.HANDSHAKE"
        const val ENGINE_PACKAGE = "fi.italeino.aidos.engine"
        const val BIND_TIMEOUT_MS = 5000L
    }
}

@Suppress("DEPRECATION")
private fun Bundle.getPendingIntentCompat(key: String): PendingIntent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, PendingIntent::class.java)
    } else {
        getParcelable(key)
    }
