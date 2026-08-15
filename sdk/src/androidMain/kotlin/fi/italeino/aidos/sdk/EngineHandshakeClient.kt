package fi.italeino.aidos.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.CompletableFuture
import kotlinx.datetime.Clock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Binder handshake client for Aidos Engine (RFC-0103).
 *
 * Implements the signature-protected Binder surface that Engine exposes for initial
 * caller identification and token acquisition.
 *
 * RFC-0103 Trust model: "The handshake's signature protection level is a hard, OS-enforced
 * gate: only apps signed with the same key as Aidos Engine can complete a handshake at all.
 * Broadening this to differently-signed clients needs a new authorization step — deferred."
 *
 * Handshake steps:
 * 1. Bind to Aidos Engine's Binder service via signature-protected permission
 * 2. Query service for current port, token, API version, and capabilities
 * 3. Cache results for use by HTTP client and adapter factory
 * 4. Return connection details to caller, or signal unavailability
 */
class EngineHandshakeClient(
    private val context: Context,
) {
    companion object {
        const val AIDOS_ENGINE_PACKAGE = "fi.italeino.aidos.engine"
        const val AIDOS_ENGINE_HANDSHAKE_ACTION = "fi.italeino.aidos.engine.HANDSHAKE"
        const val AIDOS_ENGINE_HANDSHAKE_PERMISSION = "fi.italeino.aidos.engine.HANDSHAKE"
    }

    private var handshakeService: IEngineHandshake? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            handshakeService = IEngineHandshake.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handshakeService = null
        }
    }

    /**
     * Perform handshake with Aidos Engine.
     *
     * This is the entry point for establishing a session. The method:
     * 1. Binds to Engine's Binder service (with signature-level protection)
     * 2. Calls the handshake RPC to get port, token, and capabilities
     * 3. Returns the response or an error signal
     *
     * RFC-0103 Degradation: "Aidos SDK surfaces 'Engine not installed' and
     * 'handshake or version negotiation fails' as one signal — local inference
     * unavailable — which every consuming app handles the same way."
     */
    suspend fun handshake(): Result<HandshakeResponse> = suspendCancellableCoroutine { continuation ->
        try {
            val intent = Intent(AIDOS_ENGINE_HANDSHAKE_ACTION).apply {
                component = ComponentName(AIDOS_ENGINE_PACKAGE, "fi.italeino.aidos.engine.EngineHandshakeService")
            }

            val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                continuation.resume(
                    Result.failure(
                        EngineUnavailability.NotInstalled("Could not bind to Aidos Engine service")
                    )
                )
                return@suspendCancellableCoroutine
            }

            // Give the service a moment to connect
            val maxRetries = 10
            var retries = 0
            while (handshakeService == null && retries < maxRetries) {
                Thread.sleep(100)
                retries++
            }

            val service = handshakeService
            if (service == null) {
                context.unbindService(serviceConnection)
                continuation.resume(
                    Result.failure(
                        EngineUnavailability.NotInstalled("Aidos Engine handshake service did not connect")
                    )
                )
                return@suspendCancellableCoroutine
            }

            // Call the handshake RPC
            val response = try {
                val request = HandshakeRequest(
                    callerPackage = context.packageName,
                    timestamp = Clock.System.now(),
                )
                service.handshake(request)
            } catch (e: Exception) {
                context.unbindService(serviceConnection)
                continuation.resume(
                    Result.failure(
                        EngineUnavailability.HandshakeFailed("Handshake RPC failed: ${e.message}")
                    )
                )
                return@suspendCancellableCoroutine
            }

            context.unbindService(serviceConnection)

            if (response == null) {
                continuation.resume(
                    Result.failure(
                        EngineUnavailability.HandshakeFailed("Engine returned null response")
                    )
                )
            } else {
                continuation.resume(Result.success(response))
            }
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }

    /**
     * Unbind from the Engine service and clean up.
     *
     * Safe to call even if no binding exists.
     */
    fun disconnect() {
        try {
            context.unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            // Service was not bound; ignore
        }
    }
}

/**
 * AIDL interface for Aidos Engine handshake.
 *
 * This interface is defined as part of the Engine's service contract.
 * It is signature-protected (OS-enforced at bind time).
 *
 * In a real implementation, this would be generated from IEngineHandshake.aidl.
 * For now, we define it as a stub interface to show the contract.
 */
interface IEngineHandshake : android.os.IInterface {
    fun handshake(request: HandshakeRequest): HandshakeResponse?

    abstract class Stub : Binder(), IEngineHandshake {
        companion object {
            fun asInterface(obj: IBinder?): IEngineHandshake? {
                if (obj == null) return null
                val iin = obj.queryLocalInterface("fi.italeino.aidos.engine.IEngineHandshake")
                return if (iin is IEngineHandshake) iin else Proxy(obj)
            }
        }
    }

    class Proxy(private val remote: IBinder) : IEngineHandshake {
        override fun handshake(request: HandshakeRequest): HandshakeResponse? {
            // TODO(RFC-0103): Implement Binder call marshalling
            return null
        }

        override fun asBinder(): IBinder = remote
    }

    fun asBinder(): IBinder
}
