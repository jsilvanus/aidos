package fi.italeino.aidos.engine.ui

/**
 * UI data models for Aidos Engine screens (RFC-0103, Phase D).
 *
 * These classes represent the domain data transformed into presentation format,
 * ready for binding to Compose components. They serve as a contract between
 * ViewModels (when built) and the UI layer.
 */

// ============================================================================
// Status Pane Models
// ============================================================================

data class ResidentModel(
    val id: String,
    val displayName: String,
    val quantization: String,
    val loadedAgoMs: Long,
    val connectedApp: String? = null, // e.g. "Aidos Agent"
)

data class MemoryBudget(
    val usedMB: Int,
    val totalMB: Int,
) {
    val percentUsed: Float = if (totalMB > 0) usedMB / totalMB.toFloat() else 0f
}

data class ConnectedAppStatus(
    val appName: String,
    val packageName: String,
)

data class DownloadProgress(
    val modelId: String,
    val modelName: String,
    val progressPercent: Int,
    val speedMBps: Float? = null,
    val etaSeconds: Int? = null,
)

data class StatusPaneState(
    val residentModels: List<ResidentModel> = emptyList(),
    val memoryBudget: MemoryBudget = MemoryBudget(0, 4096),
    val connectedApps: List<ConnectedAppStatus> = emptyList(),
    val inProgressDownload: DownloadProgress? = null,
    val isLoading: Boolean = false,
)

// ============================================================================
// Cookbook Pane Models
// ============================================================================

enum class ModelFitVerdict {
    RUNS_WELL,        // Plenty of headroom
    RUNS_TIGHT,       // Fits but tight on memory/compute
    EXCEEDS_CONTEXT,  // Context window doesn't fit
    WILL_NOT_FIT,     // Not enough memory
}

data class CookbookModel(
    val id: String,
    val name: String,
    val kind: String,           // "LLM" or "Embedding"
    val quantization: String,   // "Q4_K_M"
    val sizeMB: Int,
    val contextLength: Int,
    val fitVerdict: ModelFitVerdict,
    val tokensPerSecond: Float? = null,
    val estimatedVramMB: Int? = null,
)

data class CookbookPaneState(
    val searchQuery: String = "",
    val selectedFilters: Set<String> = emptySet(),
    val models: List<CookbookModel> = emptyList(),
    val isLoading: Boolean = false,
    val isSearching: Boolean = false,
)

// ============================================================================
// Providers Pane Models
// ============================================================================

enum class ProviderConfigStatus {
    NOT_CONFIGURED,      // No API key
    CONFIGURED_DISABLED, // Has key but disabled
    ENABLED,            // Has key and enabled
}

data class RemoteProvider(
    val id: String,
    val name: String,
    val status: ProviderConfigStatus,
    val lastCheckedMs: Long? = null, // When validity was last verified
)

data class ProvidersPaneState(
    val providers: List<RemoteProvider> = emptyList(),
    val isLoading: Boolean = false,
)

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
    val providerName: String = "Hugging Face",
    val licenseName: String = "Apache 2.0",
    val licenseText: String = "",
    val sizeMB: Int,
    val contextFitTable: List<ContextFitRow> = emptyList(),
    val requiresHfToken: Boolean = false,
)

data class ModelDetailState(
    val model: ModelDetail? = null,
    val licenseAccepted: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
)

// ============================================================================
// Provider Detail Screen Models
// ============================================================================

data class ConfiguredRemoteModel(
    val modelId: String,
    val displayName: String,
    val isEnabled: Boolean,
)

data class ProviderDetail(
    val id: String,
    val name: String,
    val status: ProviderConfigStatus,
    val apiKeyValid: Boolean,
    val apiKeyLastCheckedMs: Long? = null,
    val isEnabled: Boolean,
    val configuredModels: List<ConfiguredRemoteModel> = emptyList(),
)

data class ProviderDetailState(
    val provider: ProviderDetail? = null,
    val showApiKeyField: Boolean = false,
    val apiKeyInput: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
)

// ============================================================================
// Storage Screen Models
// ============================================================================

data class InstalledModel(
    val id: String,
    val name: String,
    val sizeMB: Int,
    val lastUsedMs: Long? = null,
    val isWastedSpace: Boolean = false, // Never run + won't fit
    val isEnabled: Boolean = true,
)

data class StorageState(
    val totalDeviceMB: Int = 128_000,
    val freeDeviceMB: Int = 64_000,
    val installedModels: List<InstalledModel> = emptyList(),
    val isLoading: Boolean = false,
) {
    val usedDeviceMB: Int get() = totalDeviceMB - freeDeviceMB
}

// ============================================================================
// Connected Apps Screen Models
// ============================================================================

data class RequestMetrics(
    val chatCompletions: Int = 0,
    val embeddings: Int = 0,
    val transcriptions: Int = 0,
) {
    val total: Int get() = chatCompletions + embeddings + transcriptions
}

data class ConnectedApp(
    val packageName: String,
    val displayName: String,
    val iconResId: Int? = null,
    val requestMetrics: RequestMetrics = RequestMetrics(),
    val lastActiveMs: Long,
)

data class ConnectedAppsState(
    val connectedApps: List<ConnectedApp> = emptyList(),
    val isLoading: Boolean = false,
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
    val role: String,  // "user" or "assistant"
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
    NOT_LOADED,      // Model not in memory
    LOADING,         // Currently loading
    LOADED,          // Loaded and ready
    ERROR,           // Load failed
    UNLOADING,       // Currently unloading
}

data class ModelLoadingState(
    val modelId: String = "",
    val status: ModelLoadingStatus = ModelLoadingStatus.NOT_LOADED,
    val loadProgress: Int = 0,  // 0-100
    val estimatedMemoryMB: Int = 0,
    val error: String? = null,
    val loadTimeMs: Long? = null,
)

