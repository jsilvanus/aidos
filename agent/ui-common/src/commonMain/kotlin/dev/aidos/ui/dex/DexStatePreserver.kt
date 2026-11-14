package dev.aidos.ui.dex

import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.spatial.canvas.ViewportTransform

data class PreservedWorkspaceSession(
    val activeProjectId: String,
    val activeNodeId: String?,
    val activeDocumentText: String?,
    val canvasViewport: ViewportTransform,
    val formFactor: FormFactor,
)

/**
 * Retains state across Samsung DeX / OTG hot-docking transitions (RFC-0112).
 * Ensures zero-restart layout migration when USB-C display is connected.
 */
class DexStatePreserver {
    private var cachedSession: PreservedWorkspaceSession? = null

    fun captureSession(
        projectId: String,
        nodeId: String?,
        documentText: String?,
        viewport: ViewportTransform,
        formFactor: FormFactor,
    ) {
        cachedSession = PreservedWorkspaceSession(
            activeProjectId = projectId,
            activeNodeId = nodeId,
            activeDocumentText = documentText,
            canvasViewport = viewport,
            formFactor = formFactor,
        )
    }

    fun restoreSession(): PreservedWorkspaceSession? = cachedSession

    fun clear() {
        cachedSession = null
    }
}
