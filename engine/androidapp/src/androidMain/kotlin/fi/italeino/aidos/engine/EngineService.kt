package fi.italeino.aidos.engine

import android.content.Intent
import androidx.lifecycle.LifecycleService
import fi.italeino.aidos.engine.approval.ApprovalManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Android foreground service hosting Aidos Engine Core (RFC-0103).
 *
 * Wires the Engine core (model loading, inference backends) into the Android service
 * lifecycle. [onStartCommand] starts the Engine and calls `startForeground` with an
 * ongoing notification; [onDestroy] shuts it down.
 *
 * TODO(RFC-0103): Binder handshake, foreground service, HTTP server implementation.
 * This includes:
 * - Handshake Binder surface with approval-based access control (RFC-0103)
 * - Loopback HTTP server binding to 127.0.0.1
 * - OpenAI-compatible endpoints (/v1/chat/completions, /v1/embeddings, /v1/audio/transcriptions)
 * - Token-based authentication for HTTP requests
 * - Model loading and management from Aidos Engine Core
 * - Graceful degradation on Engine unavailability
 */
class EngineService : LifecycleService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    /**
     * Manages app approval state for handshake requests (RFC-0103).
     * Approval grants are session-scoped (cleared on Engine restart) in v1.
     */
    private val approvalManager = ApprovalManager()

    override fun onCreate() {
        super.onCreate()
        // TODO(RFC-0103): Initialize Engine core, start HTTP server, expose Binder handshake
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // TODO(RFC-0103): Start foreground notification
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        // TODO(RFC-0103): Graceful shutdown of Engine core and HTTP server
    }
    
    /**
     * Returns the approval manager instance.
     * Exposed for Binder handshake and UI screens to check/manage approval state.
     */
    fun getApprovalManager(): ApprovalManager = approvalManager
}
