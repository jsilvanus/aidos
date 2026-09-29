package fi.italeino.aidos.engine.binder

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import fi.italeino.aidos.engine.HandshakeResult
import fi.italeino.aidos.engine.approval.AppApprovalManager
import fi.italeino.aidos.engine.handshake.HandshakeCore
import fi.italeino.aidos.engine.handshake.HandshakeWire
import fi.italeino.aidos.engine.http.Capabilities
import fi.italeino.aidos.engine.http.EngineHttpServer
import fi.italeino.aidos.engine.http.ModelInfo
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
    httpServer: EngineHttpServer,
    private val approvalManager: AppApprovalManager,
    private val modelRuntime: dev.aidos.modelruntime.GlobalModelRuntime
) : fi.italeino.aidos.engine.IEngineHandshake.Stub() {

    private val core = HandshakeCore(
        store = approvalManager.store,
        tokenManager = tokenManager,
        boundPort = { httpServer.getBoundPort() },
        capabilities = { buildCapabilities() },
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

    private suspend fun buildCapabilities(): Capabilities {
        val catalog = modelRuntime.catalog()
        return Capabilities(
            endpoints = listOf("models", "chat.completions", "embeddings", "audio.transcriptions"),
            models = catalog.map { descriptor ->
                ModelInfo(
                    id = descriptor.id,
                    kind = descriptor.kind.toString().lowercase(),
                    context_window = descriptor.contextWindow,
                    quantization = deriveQuantization(descriptor.id)
                )
            }
        )
    }

    private fun deriveQuantization(modelId: String): String {
        return when {
            modelId.contains("q4_k_m", ignoreCase = true) -> "q4_k_m"
            modelId.contains("q8_0", ignoreCase = true) -> "q8_0"
            modelId.contains("q5_k_m", ignoreCase = true) -> "q5_k_m"
            else -> "q4_k_m" // Default to q4_k_m if not specified in filename
        }
    }
    
    private fun getPackageNameForUid(uid: Int): String? {
        val packageManager = context.packageManager
        val packages = packageManager.getPackagesForUid(uid)
        return packages?.firstOrNull()
    }

    override fun asBinder(): IBinder = this
}
