package fi.italeino.aidos.engine.ui

import android.app.Application
import fi.italeino.aidos.engine.EngineState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.aidos.cookbook.CookbookVerdict
import dev.aidos.kernel.ModelKind
import dev.aidos.models.BrowsableModel
import dev.aidos.models.DefaultModelInstallerWorkflow
import dev.aidos.models.InstallerEvent
import dev.aidos.models.ModelDownloadRequest
import fi.italeino.aidos.engine.EngineService
import fi.italeino.aidos.engine.suggestions.SuggestedModel
import fi.italeino.aidos.engine.suggestions.SuggestedModels
import fi.italeino.aidos.engine.suggestions.SuggestionPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import fi.italeino.aidos.engine.http.KtorEffectBroker
import dev.aidos.kernel.BasicResourceHandle
import dev.aidos.kernel.CapabilityId
import dev.aidos.huggingface.HuggingFaceClient
import dev.aidos.cookbook.CookbookEngine
import dev.aidos.models.ModelBrowser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.AfterVersion
import dev.aidos.downloads.LocalDownloadManager
import dev.aidos.models.DatabaseModelCatalogManager

private data class InstallProgress(val percent: Int = 0, val error: String? = null, val active: Boolean = true)

/**
 * ViewModel for the Models screen (RFC-0103 Phase E).
 *
 * Bridges the Engine's ModelBrowser, catalog and installer to the UI. The Engine's services only
 * exist once EngineService is READY, so this waits for that state rather than reading a null
 * browser once at construction and showing an empty screen forever.
 */
class ModelsViewModel(application: Application) : AndroidViewModel(application) {

    private data class SearchParams(
        val query: String,
        val kind: ModelKind?,
        val minContext: Int?,
        val maxSizeMb: Int?,
    )

    private val suggestionPrefs = SuggestionPreferences.get(application)

    private val _localModels = MutableStateFlow<List<CookbookModel>>(emptyList())
    val localModels: StateFlow<List<CookbookModel>> = _localModels.asStateFlow()

    private val _cookbookModels = MutableStateFlow<List<CookbookModel>>(emptyList())
    val cookbookModels: StateFlow<List<CookbookModel>> = _cookbookModels.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val engineState: StateFlow<EngineState> = EngineService.state

    private val installedIds = MutableStateFlow<Set<String>>(emptySet())
    private val installs = MutableStateFlow<Map<String, InstallProgress>>(emptyMap())

    val suggestions: StateFlow<List<SuggestionUi>> = combine(
        suggestionPrefs.dismissed, installedIds, installs,
    ) { dismissed, installed, progress ->
        SuggestedModels.visible(dismissed).map { model ->
            val p = progress[model.id]
            SuggestionUi(
                model = model,
                isInstalled = model.id in installed,
                isInstalling = p?.active == true,
                progressPercent = p?.percent ?: 0,
                error = p?.error,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var searchJob: Job? = null
    private var lastSearch = SearchParams(query = "", kind = null, minContext = null, maxSizeMb = null)

    init {
        viewModelScope.launch {
            EngineService.state.collect { state ->
                if (state == EngineState.READY) {
                    refresh()
                    runSearch(lastSearch, debounce = false)
                }
            }
        }
    }

    /** Reloads installed models. Cookbook results come from [searchRemote]. */
    fun refresh() {
        val service = EngineService.instance ?: return
        val browser = service.modelBrowser ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val installed = withContext(Dispatchers.IO) {
                    browser.browse(onlyInstalled = true).getOrThrow()
                }
                _localModels.value = installed.map { it.toUiModel() }.distinctBy { it.id }
                installedIds.value = installed.map { it.id }.toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "Could not read installed models: ${e.message ?: e::class.simpleName}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun searchRemote(query: String, kind: ModelKind? = null, minContext: Int? = null, maxSizeMb: Int? = null) {
        val search = SearchParams(query = query, kind = kind, minContext = minContext, maxSizeMb = maxSizeMb)
        lastSearch = search
        runSearch(search, debounce = query.isNotEmpty())
    }

    private fun runSearch(search: SearchParams, debounce: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) delay(500)
            val browser = EngineService.instance?.modelBrowser ?: run {
                // Build a local ModelBrowser that uses a direct HTTP client path to Hugging Face
                val localHttp = HttpClient(Android) {
                    install(ContentNegotiation) { json() }
                }
                val localBroker = KtorEffectBroker(localHttp)
                val hfHandle = BasicResourceHandle(CapabilityId("huggingface"))
                val localHfClient = HuggingFaceClient(localBroker, hfHandle)
                val dbDriver = AndroidSqliteDriver(
                    schema = object : SqlSchema<QueryResult.Value<Unit>> {
                        override val version: Long = 1
                        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
                            DatabaseModelCatalogManager.createTables(driver)
                            return QueryResult.Value(Unit)
                        }

                        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> = QueryResult.Value(Unit)
                    },
                    context = getApplication(),
                    name = "aidos_local_search.db",
                )
                val localCatalog = DatabaseModelCatalogManager(dbDriver)
                ModelBrowser(
                    catalogManager = localCatalog,
                    hfClient = localHfClient,
                    cookbookEngine = CookbookEngine(),
                    deviceProfile = DeviceProfileProvider(getApplication()).getProfile(),
                )
            }

            _isSearching.value = true
            try {
                val (query, kind, minContext, maxSizeMb) = search
                // Hub search plus one metadata fetch per result, and JSON parsing: keep it off
                // the main thread.
                val results = withContext(Dispatchers.IO) {
                    browser.searchRemote(query.ifBlank { null }, kind, minContext).getOrThrow()
                }
                val maxBytes = maxSizeMb?.toLong()?.times(1024L * 1024L)
                val filtered = if (maxBytes == null) results else results.filter { model ->
                    val size = model.sizeBytes
                    size != null && size > 0 && size <= maxBytes
                }
                // The lists are keyed by id; a duplicate key is an IllegalArgumentException in Compose.
                _cookbookModels.value = filtered.map { it.toUiModel() }.distinctBy { it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "Hugging Face search failed: ${e.message ?: "network error"}"
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /** One-tap install of a suggested artifact through the Engine's shared installer. */
    fun installSuggestion(id: String) {
        val model = SuggestedModels.all.firstOrNull { it.id == id } ?: return
        if (installs.value[id]?.active == true) return
        // Use Engine's shared managers when available; otherwise create local equivalents so
        // users can download models without starting the Engine service.
        val service = EngineService.instance
        val (downloader, catalog, filesDir) = if (service != null && service.downloadManager != null && service.catalogManager != null) {
            Triple(service.downloadManager!!, service.catalogManager!!, service.filesDir)
        } else {
            // Create local download manager and a lightweight database-backed catalog using
            // the same SQLDelight schema the Engine uses. Use the Application context for
            // the AndroidSqliteDriver so the DB lives in the app data area.
            val modelsDir = File(getApplication<Application>().filesDir, "models")
            val localDownloader = LocalDownloadManager(modelsDir.absolutePath)
            val databaseDriver = AndroidSqliteDriver(
                schema = object : SqlSchema<QueryResult.Value<Unit>> {
                    override val version: Long = 1
                    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
                        DatabaseModelCatalogManager.createTables(driver)
                        return QueryResult.Value(Unit)
                    }

                    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> = QueryResult.Value(Unit)
                },
                context = getApplication(),
                name = "aidos_engine.db",
            )
            val localCatalog = DatabaseModelCatalogManager(databaseDriver)
            Triple(localDownloader, localCatalog, getApplication<Application>().filesDir)
        }

        viewModelScope.launch {
            setInstall(id, InstallProgress())
            val destination = File(File(filesDir, "models").apply { mkdirs() }, SuggestedModels.artifactName(model))
            val result = DefaultModelInstallerWorkflow(downloader, catalog).install(
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

    fun dismissSuggestion(id: String) = suggestionPrefs.dismiss(id)

    /**
     * Unloads the model if it is resident, then removes its file and its install record. The
     * previous version only asked the runtime to delete, which left the catalog row behind, so
     * the "deleted" model stayed in the Local list.
     */
    fun deleteModel(modelId: String) {
        val service = EngineService.instance ?: return
        val downloader = service.downloadManager ?: return
        val catalog = service.catalogManager ?: return
        viewModelScope.launch {
            try {
                service.modelRuntime?.let { runtime ->
                    if (modelId in runtime.loaded()) runtime.unload(modelId)
                }
                withContext(Dispatchers.IO) {
                    DefaultModelInstallerWorkflow(downloader, catalog).uninstall(modelId).getOrThrow()
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

    private fun BrowsableModel.toUiModel(): CookbookModel {
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
    private fun BrowsableModel.artifactFormat(): String = try {
        Json.parseToJsonElement(catalogEntry.propertiesJson).jsonObject["format"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    } ?: "gguf"

    private fun CookbookVerdict.toUiVerdict(): ModelFitVerdict = when (this) {
        CookbookVerdict.RUNS_WELL -> ModelFitVerdict.RUNS_WELL
        CookbookVerdict.RUNS_TIGHT -> ModelFitVerdict.RUNS_TIGHT
        CookbookVerdict.EXCEEDS_CONTEXT -> ModelFitVerdict.EXCEEDS_CONTEXT
        CookbookVerdict.WILL_NOT_FIT -> ModelFitVerdict.WILL_NOT_FIT
    }
}
