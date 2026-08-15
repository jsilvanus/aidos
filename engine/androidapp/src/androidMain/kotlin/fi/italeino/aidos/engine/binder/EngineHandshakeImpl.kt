package fi.italeino.aidos.engine.binder

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import fi.italeino.aidos.engine.approval.AppApprovalManager
import fi.italeino.aidos.engine.approval.ApprovalDecision
import fi.italeino.aidos.engine.http.Capabilities
import fi.italeino.aidos.engine.http.EngineHttpServer
import fi.italeino.aidos.engine.http.ModelInfo
import fi.italeino.aidos.engine.http.TokenManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Implementation of the Aidos Engine handshake Binder interface (RFC-0103).
 *
 * Called by client apps after the OS verifies their signature matches Aidos Engine's.
 * Returns the ephemeral HTTP port, bearer token, and capability list (if APPROVED),
 * or a deep-link intent to ConnectedAppsScreen (if PENDING_APPROVAL).
 *
 * This is the one Binder surface Engine exposes. All other traffic goes via HTTP.
 */
class EngineHandshakeImpl(
    private val context: Context,
    private val tokenManager: TokenManager,
    private val httpServer: EngineHttpServer,
    private val approvalManager: AppApprovalManager
) : fi.italeino.aidos.engine.IEngineHandshake.Stub() {

    override fun performHandshake(): HandshakeResult {
        // Get the caller's package name
        val callerUid = Binder.getCallingUid()
        val callerPackageName = getPackageNameForUid(callerUid)
        
        if (callerPackageName == null) {
            return HandshakeResult(status = "DENIED")
        }
        
        // Check approval status. runBlocking is necessary because Binder calls
        // are synchronous but approvalManager uses coroutines.
        val approval = runBlocking {
            approvalManager.checkApproval(callerPackageName)
        }
        
        return when (approval) {
            is ApprovalDecision.Approved -> buildApprovedResult()
            is ApprovalDecision.Denied -> HandshakeResult(status = "DENIED")
            is ApprovalDecision.PendingApproval -> HandshakeResult(
                status = "PENDING_APPROVAL",
                deepLinkPendingIntent = approval.deepLinkIntent
            )
        }
    }
    
    private fun buildApprovedResult(): HandshakeResult {
        // Generate a new bearer token for this handshake
        val tokenInfo = tokenManager.generateNewToken()

        // Get the port the HTTP server is bound to
        val port = httpServer.getBoundPort()
            ?: throw IllegalStateException("HTTP server not running or port not bound")

        // Build capability list
        // TODO(RFC-0103 Phase B): Populate actual available models from model catalog
        val capabilities = Capabilities(
            endpoints = listOf("chat.completions", "embeddings", "audio.transcriptions"),
            models = listOf(
                // Placeholder models — real ones populated during Phase B
                ModelInfo(
                    id = "llama-7b",
                    kind = "llm",
                    context_window = 2048,
                    quantization = "q4_k_m"
                ),
                ModelInfo(
                    id = "nomic-embed-text",
                    kind = "embedding",
                    context_window = 2048,
                    quantization = "q8_0"
                )
            )
        )

        val capabilitiesJson = Json.encodeToString(capabilities)

        return HandshakeResult(
            status = "APPROVED",
            port = port,
            token = tokenInfo.token,
            apiVersion = 1,
            capabilitiesJson = capabilitiesJson
        )
    }
    
    private fun getPackageNameForUid(uid: Int): String? {
        val packageManager = context.packageManager
        val packages = packageManager.getPackagesForUid(uid)
        return packages?.firstOrNull()
    }

    fun asBinder(): IBinder = this
}
