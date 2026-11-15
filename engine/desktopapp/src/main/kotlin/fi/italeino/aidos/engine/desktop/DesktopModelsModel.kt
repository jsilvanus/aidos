package fi.italeino.aidos.engine.desktop

import dev.aidos.cookbook.CookbookVerdict
import dev.aidos.kernel.ModelKind
import dev.aidos.models.BrowsableModel
import dev.aidos.models.DefaultModelInstallerWorkflow
import dev.aidos.models.InstallerEvent
import dev.aidos.models.ModelDownloadRequest
import fi.italeino.aidos.engine.suggestions.SuggestedModels
import fi.italeino.aidos.engine.ui.CookbookModel
import fi.italeino.aidos.engine.ui.ModelFitVerdict
import fi.italeino.aidos.engine.ui.ModelSearchFilters
import fi.italeino.aidos.engine.ui.ModelsPaneState
import fi.italeino.aidos.engine.ui.SuggestionUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private data class InstallProgress(val percent: Int = 0, val error: String? = null, val active: Boolean = true)

private data class RawModelsState(
    val localModels: List<CookbookModel> = emptyList(),
    val isLoading: Boolean = false,
    val cookbookModels: List<CookbookModel> = emptyList(),
    val isSearching: Boolean = false,
)

/**
 * Desktop port of Android's ModelsViewModel (RFC-0103 Phase E). The catalog and browser always
 * exist on desktop (see [DesktopEngineHost]), so there are no "Engine not running" fallbacks.
 */
class DesktopModelsModel(
    private val host: DesktopEngineHost,
    private val prefs: DesktopPreferences,
    private val scope: CoroutineScope,
    private val control: () -> fi.italeino.aidos.engine.control.ModelsControl = { host.modelsControl },
    private val installPollMillis: Long = 1_000,
) {
    private val raw = MutableStateFlow(RawModelsState())
    private val installedIds = MutableStateFlow<Set<String>>(emptySet())
    private val installs = MutableStateFlow<Map<String, InstallProgress>>(emptyMap())

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val suggestions: StateFlow<List<SuggestionUi>> = combine(
        prefs.data, installedIds, installs,
    ) { data, installed, progress ->
        SuggestedModels.visible(data.dismissedSuggestions).map { model ->
            val p = progress[model.id]
            SuggestionUi(
                model = model,
                isInstalled = model.id in installed,
                isInstalling = p?.active == true,
                progressPercent = p?.percent ?: 0,
                error = p?.error,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<ModelsPaneState> = combine(raw, suggestions, host.state) { r, s, engineState ->
        ModelsPaneState(
            engineState = engineState,
            localModels = r.localModels,
            isLoading = r.isLoading,
            cookbookModels = r.cookbookModels,
            isSearching = r.isSearching,
            suggestions = s,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ModelsPaneState())

    private var searchJob: Job? = null

    init {
        refresh()
        // Installs belong to the Engine (they carry on in a background service), so their progress is read back, not kept here.
        scope.launch {
            var previous = emptySet<String>()
            while (isActive) {
                val now = runCatching { control().installs() }.getOrNull()
                if (now != null) {
                    installs.value = now.associate { it.modelId to InstallProgress(it.percent, it.error, it.active) }
                    val running = now.filter { it.active }.map { it.modelId }.toSet()
                    if ((previous - running).any { id -> now.none { it.modelId == id && !it.active } }) refresh() // finished without an error
                    previous = running
                }
                delay(installPollMillis)
            }
        }
    }

    fun refresh() {
        scope.launch {
            raw.value = raw.value.copy(isLoading = true)
            try {
                val installed = control().installed()
                raw.value = raw.value.copy(localModels = installed.map { it.toUi() }.distinctBy { it.id })
                installedIds.value = installed.map { it.id }.toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "Could not read installed models: ${e.message ?: e::class.simpleName}"
            } finally {
                raw.value = raw.value.copy(isLoading = false)
            }
        }
    }

    fun searchRemote(query: String, kind: ModelKind?, minContext: Int?, filters: ModelSearchFilters) {
        searchJob?.cancel()
        searchJob = scope.launch {
            if (query.isNotEmpty()) delay(500)
            raw.value = raw.value.copy(isSearching = true)
            try {
                val results = control().searchRemote(
                    query.ifBlank { null }, kind?.name, minContext,
                    fi.italeino.aidos.engine.control.ModelSearchFiltersInfo(filters.minSizeMb, filters.maxSizeMb, filters.fitsDeviceOnly, filters.runnableOnly),
                )
                // The lists are keyed by id; a duplicate key is an IllegalArgumentException in Compose.
                raw.value = raw.value.copy(cookbookModels = results.map { it.toUi() }.distinctBy { it.id })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "Hugging Face search failed: ${e.message ?: "network error"}"
            } finally {
                raw.value = raw.value.copy(isSearching = false)
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /** One-click install of a suggested artifact through the Engine's shared installer. */
    fun installSuggestion(id: String) {
        if (installs.value[id]?.active == true) return
        scope.launch {
            setInstall(id, InstallProgress())
            try {
                control().startInstall(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setInstall(id, InstallProgress(error = "Install failed: ${e.message ?: "unknown error"}", active = false))
            }
        }
    }

    fun dismissSuggestion(id: String) = prefs.dismissSuggestion(id)

    /** Unloads the model if it is resident, then removes its file and its install record. */
    fun deleteModel(modelId: String) {
        scope.launch {
            try {
                control().delete(modelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "Delete failed: ${e.message ?: e::class.simpleName}"
            }
            refresh()
        }
    }

    private fun setInstall(id: String, progress: InstallProgress?) {
        installs.value = if (progress == null) installs.value - id else installs.value + (id to progress)
    }
}

// The three mappers below duplicate ModelsViewModel's private ones on Android: the plan keeps
// ViewModel logic per app, and :enginehost does not depend on :models/:cookbook to host them.

internal fun BrowsableModel.toUiModel(): CookbookModel {
    val format = artifactFormat()
    return CookbookModel(
        id = id,
        name = name,
        kind = kind.toString(),
        quantization = installedModel?.quantization ?: format.uppercase(),
        sizeBytes = sizeBytes ?: installedModel?.sizeBytes,
        contextLength = contextWindow,
        fitVerdict = verdict.toUiVerdict(),
        isRunnable = dev.aidos.models.PackageInstall.isRunnable(format, kind),
    )
}

/** Installed entries record their format in catalog properties; Hub results are GGUF-only. */
internal fun BrowsableModel.artifactFormat(): String = formatFromProperties(catalogEntry.propertiesJson)

internal fun formatFromProperties(propertiesJson: String): String = try {
    Json.parseToJsonElement(propertiesJson).jsonObject["format"]?.jsonPrimitive?.content
} catch (_: Exception) {
    null
} ?: "gguf"

internal fun CookbookVerdict.toUiVerdict(): ModelFitVerdict = when (this) {
    CookbookVerdict.RUNS_WELL -> ModelFitVerdict.RUNS_WELL
    CookbookVerdict.RUNS_TIGHT -> ModelFitVerdict.RUNS_TIGHT
    CookbookVerdict.EXCEEDS_CONTEXT -> ModelFitVerdict.EXCEEDS_CONTEXT
    CookbookVerdict.WILL_NOT_FIT -> ModelFitVerdict.WILL_NOT_FIT
}

private fun fi.italeino.aidos.engine.control.ModelInfo.toUi() = CookbookModel(
    id = id,
    name = name,
    kind = kind,
    quantization = quantization,
    sizeBytes = sizeBytes,
    contextLength = contextLength,
    fitVerdict = runCatching { ModelFitVerdict.valueOf(fitVerdict) }.getOrDefault(ModelFitVerdict.WILL_NOT_FIT),
    isRunnable = isRunnable,
)
