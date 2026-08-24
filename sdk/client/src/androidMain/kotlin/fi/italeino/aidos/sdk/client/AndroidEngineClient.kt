package fi.italeino.aidos.sdk.client

import android.app.PendingIntent
import android.content.Context

/**
 * [AidosEngineClient] plus the one piece of the handshake that only makes sense on Android: the
 * deep-link intent Engine returns while [AidosEngineClient.availability] is
 * [EngineAvailability.PendingApproval] (RFC-0103, "Trust model"). Kept off the shared interface
 * because [PendingIntent] doesn't exist on the jvm() target sdk/client also builds for.
 */
class AndroidEngineClient internal constructor(
    private val delegate: AidosEngineClient,
    private val handshake: EngineBinderHandshake
) : AidosEngineClient by delegate {
    /**
     * Deep-link intent to Engine's ConnectedAppsScreen from the most recent handshake, if
     * [availability] is currently [EngineAvailability.PendingApproval]. Present the caller with
     * this instead of re-polling [initialize].
     */
    fun pendingApprovalIntent(): PendingIntent? = handshake.pendingApprovalIntent
}

/**
 * Creates [AidosEngineClient] instances backed by a real Binder handshake with Aidos Engine
 * (RFC-0103). This is the entry point Android apps use; [AidosEngineClientFactory] in the shared
 * source set stays Binder-free for the jvm() target.
 */
object AndroidAidosEngineClientFactory {
    /**
     * Create a new client that will perform the Binder handshake on [AidosEngineClient.initialize].
     * @param context any Context; only its [Context.getApplicationContext] is retained.
     * @param requiredApiVersion the wire-format major version this client was built against
     *   (RFC-0103, "Version and capability contract"); a mismatch reports
     *   [EngineAvailability.IncompatibleVersion] instead of proceeding.
     */
    fun createClient(context: Context, requiredApiVersion: Int = 1): AndroidEngineClient {
        val handshake = EngineBinderHandshake(context.applicationContext)
        return AndroidEngineClient(EngineClientImpl(handshake, requiredApiVersion), handshake)
    }
}
