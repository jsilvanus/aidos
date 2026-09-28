package fi.italeino.aidos.engine.ui

/**
 * UI data models for Aidos Engine screens (RFC-0103, Phase D).
 *
 * These classes represent the domain data transformed into presentation format,
 * ready for binding to Compose components. They serve as a contract between
 * ViewModels and the UI layer. Every value shown to the user comes from the Engine's runtime,
 * catalog, approval store or the device itself -- there are no sample defaults here.
 */

// ============================================================================
// Status Pane Models
// ============================================================================

data class ResidentModel(
    val id: String,
    val displayName: String,
    val quantization: String,
    val loadedAgoMs: Long,
)

/** Device RAM as reported by ActivityManager.MemoryInfo, in MB. */
data class MemoryBudget(
    val usedMB: Int,
    val totalMB: Int,
) {
    val percentUsed: Float = if (totalMB > 0) usedMB / totalMB.toFloat() else 0f
}

data class DownloadProgress(
    val modelId: String,
    val modelName: String,
    val progressPercent: Int,
    val speedMBps: Float? = null,
    val etaSeconds: Int? = null,
)

// ============================================================================
// Cookbook Pane Models
// ============================================================================

enum class ModelFitVerdict {
    RUNS_WELL,
    RUNS_TIGHT,
    EXCEEDS_CONTEXT,
    WILL_NOT_FIT,
}

data class CookbookModel(
    val id: String,
    val name: String,
    val kind: String,
    /** Artifact format label, e.g. "GGUF" or "ONNX", or the installed quantization. */
    val quantization: String,
    /** Null when neither the Hub nor the install record reports a size. */
    val sizeBytes: Long?,
    val contextLength: Int,
    val fitVerdict: ModelFitVerdict,
    /** False when this Engine build has no backend for the artifact's format. */
    val isRunnable: Boolean = true,
    val tokensPerSecond: Float? = null,
    val estimatedVramMB: Int? = null,
)

/** Human-readable artifact size: "258 KB", "2.3 MB", "4.1 GB", or "size unknown". */
fun formatSize(bytes: Long?): String = when {
    bytes == null || bytes <= 0 -> "size unknown"
    bytes < 1024L * 1024 -> "${(bytes + 1023) / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> oneDecimal(bytes / (1024.0 * 1024)) + " MB"
    else -> oneDecimal(bytes / (1024.0 * 1024 * 1024)) + " GB"
}

private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

// ============================================================================
// Model Detail Screen Models
// ============================================================================

data class ContextFitRow(
    val contextLength: Int,
    val verdict: ModelFitVerdict,
    val estimatedMemoryMB: Int,
)

data class ModelDetail(
    val id: String,
    val name: String,
    val description: String = "",
    val providerName: String,
    /** License id declared on the Hub (e.g. "apache-2.0"), or null when none is declared. */
    val licenseName: String? = null,
    val modelUrl: String? = null,
    val sizeMB: Int,
    val contextFitTable: List<ContextFitRow> = emptyList(),
    val isRunnable: Boolean = true,
)

data class ModelDetailState(
    val model: ModelDetail? = null,
    val isInstalled: Boolean = false,
    val installedPath: String? = null,
    val installedSizeBytes: Long? = null,
    val installedDigest: String? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val downloadError: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

// ============================================================================
// Settings Screen Models
// ============================================================================
data class HfTokenStatus(
    val isConfigured: Boolean,
    val lastValidatedMs: Long? = null,
)

data class SettingsState(
    val hfTokenStatus: HfTokenStatus = HfTokenStatus(false),
    val showTokenInput: Boolean = false,
    val tokenInput: String = "",
    val isLoading: Boolean = false,
    val successMessage: String? = null,
    val errorMessage: String? = null,
)

// ============================================================================
// Test Chat Screen Models (Phase E)
// ============================================================================
data class UiChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val tokensUsed: Int? = null,
    val generationTimeMs: Long? = null,
)

data class TestChatState(
    val modelId: String = "",
    val modelName: String = "",
    val messages: List<UiChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val totalTokensUsed: Int = 0,
    val averageTokensPerSecond: Float = 0f,
)

// ============================================================================
// Model Loading State (Phase E)
// ============================================================================

enum class ModelLoadingStatus {
    NOT_LOADED,
    LOADING,
    LOADED,
    ERROR,
    UNLOADING,
}

data class ModelLoadingState(
    val modelId: String = "",
    val status: ModelLoadingStatus = ModelLoadingStatus.NOT_LOADED,
    val loadProgress: Int = 0,
    val estimatedMemoryMB: Int = 0,
    val error: String? = null,
    val loadTimeMs: Long? = null,
)
