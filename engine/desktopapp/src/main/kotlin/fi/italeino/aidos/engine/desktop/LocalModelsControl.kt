package fi.italeino.aidos.engine.desktop

import dev.aidos.kernel.ModelKind
import dev.aidos.models.DefaultModelInstallerWorkflow
import dev.aidos.models.InstallerEvent
import dev.aidos.models.ModelDownloadRequest
import fi.italeino.aidos.engine.control.InstallStatus
import fi.italeino.aidos.engine.control.ModelInfo
import fi.italeino.aidos.engine.control.ModelSearchFiltersInfo
import fi.italeino.aidos.engine.control.ModelsControl
import fi.italeino.aidos.engine.suggestions.SuggestedModels
import fi.italeino.aidos.engine.ui.CookbookModel
import fi.italeino.aidos.engine.ui.ModelSearchFilters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [ModelsControl] over this process's Engine: the in-process path, and what the admin API serves from. Installs run
 * on the control's own scope, so they belong to the Engine and outlive whichever window started them (RFC-0115).
 */
class LocalModelsControl(
    private val host: DesktopEngineHost,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ModelsControl {
    private val installs = ConcurrentHashMap<String, InstallStatus>()

    override suspend fun installed(): List<ModelInfo> = withContext(Dispatchers.IO) {
        host.modelBrowser.browse(onlyInstalled = true).getOrThrow().map { it.toUiModel().toInfo() }.distinctBy { it.id }
    }

    override suspend fun searchRemote(query: String?, kind: String?, minContext: Int?, filters: ModelSearchFiltersInfo): List<ModelInfo> {
        val modelKind = kind?.let { k -> ModelKind.entries.firstOrNull { it.name == k } ?: throw IllegalArgumentException("unknown model kind $k") }
        val f = ModelSearchFilters(filters.minSizeMb, filters.maxSizeMb, filters.fitsDeviceOnly, filters.runnableOnly)
        return withContext(Dispatchers.IO) {
            host.modelBrowser.searchRemote(query?.ifBlank { null }, modelKind, minContext, accept = { f.accepts(it.toUiModel()) })
                .getOrThrow().map { it.toUiModel().toInfo() }.distinctBy { it.id }
        }
    }

    override suspend fun startInstall(suggestionId: String) {
        val model = SuggestedModels.all.firstOrNull { it.id == suggestionId } ?: throw IllegalArgumentException("no such suggested model")
        if (installs[suggestionId]?.active == true) return
        installs[suggestionId] = InstallStatus(suggestionId)
        scope.launch {
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
                ),
            ) { event ->
                if (event is InstallerEvent.DownloadProgress) {
                    val total = event.totalBytes ?: model.approxSizeBytes
                    val percent = if (total > 0) ((event.bytesDownloaded * 100) / total).toInt().coerceIn(0, 100) else 0
                    installs[suggestionId] = InstallStatus(suggestionId, percent)
                }
            }
            result.onSuccess { installs.remove(suggestionId) }
                .onFailure { e -> installs[suggestionId] = InstallStatus(suggestionId, 0, "Install failed: ${e.message ?: "unknown error"}", active = false) }
        }
    }

    override suspend fun installs(): List<InstallStatus> = installs.values.toList()

    override suspend fun delete(modelId: String) {
        host.modelRuntime?.let { runtime -> if (modelId in runtime.loaded()) runtime.unload(modelId) }
        withContext(Dispatchers.IO) { DefaultModelInstallerWorkflow(host.downloadManager, host.catalogManager).uninstall(modelId).getOrThrow() }
    }
}

private fun CookbookModel.toInfo() = ModelInfo(id, name, kind, quantization, sizeBytes, contextLength, fitVerdict.name, isRunnable)
