package fi.italeino.aidos.engine.binder

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import fi.italeino.aidos.engine.HandshakeResult
import fi.italeino.aidos.engine.approval.AppApprovalManager
import fi.italeino.aidos.engine.handshake.HandshakeCore
import fi.italeino.aidos.engine.handshake.HandshakeWire
import fi.italeino.aidos.engine.handshake.handshakeCapabilities
import fi.italeino.aidos.engine.http.TokenManager
import kotlinx.coroutines.runBlocking

/**
 * Implementation of the Aidos Engine handshake Binder interface (RFC-0103).
 *
 * Only the Android-specific parts live here: resolving the caller from the Binder UID and
 * attaching the deep-link `PendingIntent`. The decision itself (unknown → PENDING + notify,
 * approved → per-app token, denied, revoked) is [HandshakeCore], which is unit-tested on the JVM.
 *
 * This is the one Binder surface Engine exposes. All other traffic goes via HTTP.
 */
class EngineHandshakeImpl(
    private val context: Context,
    tokenManager: TokenManager,
    boundPort: suspend () -> Int?,
    private val approvalManager: AppApprovalManager,
    private val modelRuntime: suspend () -> dev.aidos.kernel.ModelRuntime?
) : fi.italeino.aidos.engine.IEngineHandshake.Stub() {

    private val core = HandshakeCore(
        store = approvalManager.store,
        tokenManager = tokenManager,
        boundPort = boundPort,
        capabilities = { (modelRuntime() ?: throw IllegalStateException("Model runtime not ready")).handshakeCapabilities() },
        resolveDisplayName = approvalManager::displayNameOf,
        onFirstRequest = approvalManager::notifyFirstRequest
    )

    override fun performHandshake(): Bundle {
        val callerPackageName = getPackageNameForUid(Binder.getCallingUid())

        // runBlocking is necessary because Binder calls are synchronous but the core is suspend.
        val reply = runBlocking { core.handshake(callerPackageName) }

        return HandshakeResult(
            status = reply.status,
            port = reply.port,
            token = reply.token,
            apiVersion = reply.apiVersion,
            capabilitiesJson = reply.capabilitiesJson,
            deepLinkPendingIntent =
                if (reply.status == HandshakeWire.STATUS_PENDING_APPROVAL) approvalManager.deepLinkIntent() else null
        ).toBundle()
    }

    private fun getPackageNameForUid(uid: Int): String? {
        val packageManager = context.packageManager
        val packages = packageManager.getPackagesForUid(uid)
        return packages?.firstOrNull()
    }

    override fun asBinder(): IBinder = this
}
