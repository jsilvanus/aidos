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
    }

    fun refresh() {
        scope.launch {
            raw.value = raw.value.copy(isLoading = true)
            try {
                val installed = withContext(Dispatchers.IO) {
                    host.modelBrowser.browse(onlyInstalled = true).getOrThrow()
                }
                raw.value = raw.value.copy(localModels = installed.map { it.toUiModel() }.distinctBy { it.id })
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

    fun searchRemote(query: String, kind: ModelKind?, minContext: Int?, maxSizeMb: Int?) {
        searchJob?.cancel()
        searchJob = scope.launch {
            if (query.isNotEmpty()) delay(500)
            raw.value = raw.value.copy(isSearching = true)
            try {
                val results = withContext(Dispatchers.IO) {
                    host.modelBrowser.searchRemote(query.ifBlank { null }, kind, minContext).getOrThrow()
                }
                val maxBytes = maxSizeMb?.toLong()?.times(1024L * 1024L)
                val filtered = if (maxBytes == null) results else results.filter { model ->
                    val size = model.sizeBytes
                    size != null && size > 0 && size <= maxBytes
                }
                // The lists are keyed by id; a duplicate key is an IllegalArgumentException in Compose.
                raw.value = raw.value.copy(cookbookModels = filtered.map { it.toUiModel() }.distinctBy { it.id })
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
        val model = SuggestedModels.all.firstOrNull { it.id == id } ?: return
        if (installs.value[id]?.active == true) return
        scope.launch {
            setInstall(id, InstallProgress())
            val destination = File(host.artifactDir, SuggestedModels.artifactName(model))
            val result = DefaultModelInstallerWorkflow(host.downloadManager, host.catalogManager).install(
                ModelDownloadRequest(
                    modelId = model.id,
                    artifactName = SuggestedModels.artifactName(model),
                    downloadUrl = model.downloadUrl,
                    destination = destination.absolutePath,
                    kind = ModelKind.LLM,
                    format = model.format,
                    backend = model.backend,
                    quantization = model.format.uppercase(),
                )
            ) { event ->
                if (event is InstallerEvent.DownloadProgress) {
                    val total = event.totalBytes ?: model.approxSizeBytes
                    val percent = if (total > 0) ((event.bytesDownloaded * 100) / total).toInt().coerceIn(0, 100) else 0
                    setInstall(id, InstallProgress(percent))
                }
            }
            result.onSuccess {
                setInstall(id, null)
                refresh()
            }.onFailure { e ->
                setInstall(id, InstallProgress(error = "Install failed: ${e.message ?: "unknown error"}", active = false))
            }
        }
    }

    fun dismissSuggestion(id: String) = prefs.dismissSuggestion(id)

    /** Unloads the model if it is resident, then removes its file and its install record. */
    fun deleteModel(modelId: String) {
        scope.launch {
            try {
                host.modelRuntime?.let { runtime ->
                    if (modelId in runtime.loaded()) runtime.unload(modelId)
                }
                withContext(Dispatchers.IO) {
                    DefaultModelInstallerWorkflow(host.downloadManager, host.catalogManager).uninstall(modelId).getOrThrow()
                }
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
        isRunnable = format == "gguf",
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
