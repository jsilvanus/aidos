package fi.italeino.aidos.engine.desktop

import dev.aidos.cookbook.CookbookEngine
import dev.aidos.decisions.LayaPackage
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.modelruntime.ModelRunSettings
import dev.aidos.models.DefaultModelInstallerWorkflow
import dev.aidos.models.InstallerEvent
import dev.aidos.models.ModelDownloadRequest
import fi.italeino.aidos.engine.EngineState
import fi.italeino.aidos.engine.control.ContextFitInfo
import fi.italeino.aidos.engine.control.DownloadInfo
import fi.italeino.aidos.engine.control.InstalledModelInfo
import fi.italeino.aidos.engine.control.ModelDetailControl
import fi.italeino.aidos.engine.control.ModelDetailInfo
import fi.italeino.aidos.engine.control.ResidencyInfo
import fi.italeino.aidos.engine.control.RunSettingsInfo
import fi.italeino.aidos.engine.control.SelfTestInfo
import fi.italeino.aidos.engine.ui.runModelSelfTest
import fi.italeino.aidos.engine.ui.selfTestKind
import fi.italeino.aidos.sdk.client.EmbeddingsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * [ModelDetailControl] over this process's Engine: the in-process path, and what the admin API serves from. The logic
 * is the model detail screen's, moved here unchanged in behaviour (RFC-0115): downloads run on this control's scope so
 * they belong to the Engine and outlive the window that started them.
 */
class LocalModelDetailControl(
    private val host: DesktopEngineHost,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ModelDetailControl {
    private val downloads = ConcurrentHashMap<String, DownloadInfo>()
    private val jobs = ConcurrentHashMap<String, Job>()

    // ---- facts -----------------------------------------------------------------------------------------------------

    override suspend fun detail(modelId: String): ModelDetailInfo = try {
        val detail = withContext(Dispatchers.IO) { host.modelBrowser.getModelDetail(modelId).getOrThrow() }
        val license = if (detail.provider == "huggingface") withContext(Dispatchers.IO) { host.hfClient.getModel(modelId).getOrNull()?.license } else null
        detail.toInfo(license)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw IllegalStateException(e.message ?: e::class.simpleName)
    }

    /** Catalog row plus an on-disk file of the recorded size. */
    override suspend fun installedState(modelId: String): InstalledModelInfo {
        val installed = withContext(Dispatchers.IO) { host.catalogManager.listInstalled().getOrNull()?.firstOrNull { it.modelId == modelId } }
        val file = installed?.path?.let(::File)
        // A GGUF is one file of the recorded size; an ONNX decision model is a package directory.
        val valid = installed != null && file != null && installed.sizeBytes > 0L && when {
            file.isFile -> file.length() == installed.sizeBytes
            file.isDirectory -> (LayaPackage.isPackage(file) || dev.aidos.stt.WhisperPackage.isPackage(file) || dev.aidos.vision.VisionPackage.isPackage(file)) &&
                file.listFiles { f -> f.isFile }.orEmpty().sumOf { it.length() } == installed.sizeBytes
            else -> false
        }
        return if (valid) InstalledModelInfo(true, installed?.path, installed?.sizeBytes, installed?.digest) else InstalledModelInfo(false)
    }

    private fun dev.aidos.models.ModelDetail.toInfo(license: String?): ModelDetailInfo {
        val device = host.deviceProfile()
        val cookbook = CookbookEngine()
        val descriptor = ModelDescriptor(id, name, kind, provider, false, contextWindow, sizeBytes, null)
        val fitRows = listOf(4096, 8192, 16384, 32768).map { ctx ->
            val fit = cookbook.ramFit(descriptor, device, ctx)
            ContextFitInfo(ctx / 1024, fit.currentVerdict.toUiVerdict().name, (fit.neededBytes / (1024 * 1024)).toInt(), fit.baselineVerdict?.toUiVerdict()?.name)
        }
        val format = formatFromProperties(catalogEntry.propertiesJson)
        return ModelDetailInfo(
            id = id,
            name = name,
            description = "${kind.name} · ${format.uppercase()} · from $provider",
            providerName = provider,
            kind = kind.name,
            licenseName = license,
            modelUrl = remoteUrl?.takeIf { it.startsWith("https://huggingface.co/") && !it.contains("/resolve/") }
                ?: if (provider == "huggingface") "https://huggingface.co/$id" else null,
            sizeMB = ((sizeBytes ?: 0L) / (1024L * 1024L)).toInt(),
            contextFitTable = fitRows,
            isRunnable = dev.aidos.models.PackageInstall.isRunnable(format, kind),
            format = format,
            deviceTotalRamMB = (device.totalRamBytes / (1024L * 1024L)).toInt(),
            deviceFreeRamMB = (device.availableRamBytes / (1024L * 1024L)).toInt(),
            baselineFreeRamMB = device.baselineAvailableRamBytes?.let { (it / (1024L * 1024L)).toInt() },
        )
    }

    // ---- license ---------------------------------------------------------------------------------------------------

    override suspend fun licenseAccepted(modelId: String): Boolean {
        val (version, digest) = resolveStoredOrRemoteLicense(modelId) ?: return true
        return host.licenseAcceptanceStore.isAccepted(modelId, version, digest)
    }

    override suspend fun acceptLicense(modelId: String) {
        val (version, digest) = resolveStoredOrRemoteLicense(modelId) ?: return
        recordLicenseAcceptance(modelId, version, digest)
    }

    private suspend fun resolveStoredOrRemoteLicense(modelId: String): Pair<String, String>? {
        val catalogEntry = withContext(Dispatchers.IO) { host.catalogManager.getCatalog(modelId).getOrNull() }
        val props = catalogEntry?.propertiesJson?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val catalogText = runCatching { props?.get("license_text")?.jsonPrimitive?.takeIf { it.isString }?.content }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val catalogDigest = runCatching { props?.get("license_digest")?.jsonPrimitive?.takeIf { it.isString }?.content }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: catalogText?.let(::sha256Hex)
        val catalogVersion = runCatching { props?.get("version")?.jsonPrimitive?.takeIf { it.isString }?.content }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (catalogDigest != null) {
            val installedDigest = withContext(Dispatchers.IO) { host.catalogManager.listInstalled().getOrNull()?.firstOrNull { it.modelId == modelId }?.digest }
            return (catalogVersion ?: installedDigest ?: "") to catalogDigest
        }

        val remote = withContext(Dispatchers.IO) { host.hfClient.getModel(modelId).getOrNull() }
        if (remote != null) {
            val licenseText = remote.license?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val safeId = modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return resolveLicenseModelVersion(remote, safeId) to sha256Hex(licenseText)
        }
        return null
    }

    private suspend fun recordLicenseAcceptance(modelId: String, modelVersion: String, licenseDigest: String?) {
        if (licenseDigest.isNullOrBlank()) return
        if (!host.licenseAcceptanceStore.isAccepted(modelId, modelVersion, licenseDigest)) host.licenseAcceptanceStore.accept(modelId, modelVersion, licenseDigest)
    }

    private fun resolveLicenseModelVersion(remote: dev.aidos.huggingface.HuggingFaceModel, safeId: String): String {
        if (remote.quantizations.isEmpty()) {
            val onnxPackage = dev.aidos.models.PackageInstall.choose(remote.packages)
            if (onnxPackage != null) return onnxPackage.files.firstOrNull()?.sha256Digest ?: onnxPackage.files.firstOrNull()?.path ?: safeId
            val visionPackage = remote.packages.firstOrNull { it.isVisionPackage }
            if (visionPackage != null) return visionPackage.files.firstOrNull()?.sha256Digest ?: visionPackage.files.firstOrNull()?.path ?: safeId
        }
        val quant = remote.quantizations.find { it.name.contains("Q4_K_M", ignoreCase = true) } ?: remote.quantizations.firstOrNull()
        if (quant != null) return quant.sha256Digest ?: quant.name
        val artifact = remote.artifacts.firstOrNull { it.sizeBytes > 0L }
        if (artifact != null) return artifact.sha256Digest ?: artifact.filename
        return safeId
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ---- download --------------------------------------------------------------------------------------------------

    override suspend fun downloadStatus(modelId: String): DownloadInfo? = downloads[modelId]

    override suspend fun cancelDownload(modelId: String) { jobs[modelId]?.cancel() }

    override suspend fun startDownload(modelId: String) {
        if (jobs[modelId]?.isActive == true) return
        downloads[modelId] = DownloadInfo(active = true)
        jobs[modelId] = scope.launch {
            try {
                install(modelId)
                downloads.remove(modelId)
            } catch (e: CancellationException) {
                downloads.remove(modelId)
                throw e
            } catch (e: Exception) {
                downloads[modelId] = DownloadInfo(active = false, error = "Download failed: ${e.message ?: "Unknown error"}")
            } finally {
                jobs.remove(modelId)
            }
        }
    }

    private fun progress(modelId: String, percent: Int) { downloads[modelId] = DownloadInfo(active = true, percent = percent) }

    private suspend fun install(modelId: String) {
        val detail = host.modelBrowser.getModelDetail(modelId).getOrThrow()
        val remote = host.hfClient.getModel(modelId).getOrThrow()

        val safeModelId = modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val licenseText = remote.license?.trim()?.takeIf { it.isNotEmpty() }
        val licenseDigest = licenseText?.let(::sha256Hex)
        val modelVersion = resolveLicenseModelVersion(remote, safeModelId)

        // A GGUF quantization is the single-file path (prefer Q4_K_M). Failing that, an ONNX decision package, a Whisper
        // speech-to-text package or a vision package installs as a directory. Anything else (a lone .onnx,
        // safetensors, ...) has no backend here, so installing it would leave a file the Engine can never load.
        val onnxPackage = dev.aidos.models.PackageInstall.choose(remote.packages)
        if (remote.quantizations.isEmpty() && onnxPackage != null) {
            recordLicenseAcceptance(modelId, modelVersion, licenseDigest)
            installPackage(modelId, safeModelId, onnxPackage, licenseText, licenseDigest, modelVersion)
            return
        }
        // A SmolVLM-style vision model is three graphs and configs installed as one unit.
        val visionPackage = remote.packages.firstOrNull { it.isVisionPackage }
        if (remote.quantizations.isEmpty() && visionPackage != null) {
            recordLicenseAcceptance(modelId, modelVersion, licenseDigest)
            installPackage(modelId, safeModelId, visionPackage, licenseText, licenseDigest, modelVersion)
            return
        }
        if (remote.quantizations.isEmpty()) {
            val found = remote.artifacts.map { it.format.name }.distinct().joinToString().ifEmpty { "none" }
            throw IllegalStateException("No GGUF file, ONNX decision, Whisper or vision package in $modelId (formats found: $found); this Engine cannot run it.")
        }
        val (artifactBaseName, downloadUrl, expectedDigest) = remote.quantizations.let { qs ->
            val q = qs.find { it.name.contains("Q4_K_M", ignoreCase = true) } ?: qs.first()
            Triple(q.name, q.downloadUrl, q.sha256Digest)
        }
        recordLicenseAcceptance(modelId, modelVersion, licenseDigest)

        val artifactName = dev.aidos.models.PackageInstall.ggufFileName(safeModelId, artifactBaseName)
        val destination = File(host.artifactDir, artifactName).absolutePath
        val format = if (artifactName.lowercase().endsWith(".gguf")) "gguf" else artifactName.substringAfterLast('.', "bin").lowercase()

        var failure: String? = null
        val result = DefaultModelInstallerWorkflow(host.downloadManager, host.catalogManager).install(
            ModelDownloadRequest(
                modelId = modelId,
                artifactName = artifactName,
                downloadUrl = downloadUrl,
                expectedDigest = expectedDigest,
                destination = destination,
                kind = detail.kind,
                format = format,
                backend = if (format == "gguf") "llama.cpp" else "unknown",
                quantization = if (format == "gguf") artifactBaseName else null,
                licenseText = licenseText,
                licenseTextDigest = licenseDigest,
                modelVersion = modelVersion,
            ),
        ) { event ->
            when (event) {
                is InstallerEvent.DownloadProgress -> event.totalBytes?.takeIf { it > 0 }?.let { progress(modelId, ((event.bytesDownloaded * 100L) / it).toInt().coerceIn(0, 100)) }
                is InstallerEvent.InstallationFailed -> failure = event.reason
                else -> Unit
            }
        }
        result.exceptionOrNull()?.let { throw it }
        failure?.let { throw IllegalStateException(it) }
    }

    /** Downloads every file of [pkg] into `<models>/<safeId>/`; the graph last, so a partial install is never a loadable package. */
    private suspend fun installPackage(modelId: String, safeId: String, pkg: dev.aidos.huggingface.ModelPackage, licenseText: String?, licenseDigest: String?, modelVersion: String?) {
        var failure: String? = null
        val result = DefaultModelInstallerWorkflow(host.downloadManager, host.catalogManager).installPackage(
            dev.aidos.models.PackageInstall.toRequest(modelId, pkg, File(host.artifactDir, safeId).absolutePath)
                .copy(licenseText = licenseText, licenseTextDigest = licenseDigest, modelVersion = modelVersion),
        ) { event ->
            when (event) {
                is InstallerEvent.DownloadProgress -> event.totalBytes?.takeIf { it > 0 }?.let { progress(modelId, ((event.bytesDownloaded * 100L) / it).toInt().coerceIn(0, 100)) }
                is InstallerEvent.InstallationFailed -> failure = event.reason
                else -> Unit
            }
        }
        result.exceptionOrNull()?.let { throw it }
        failure?.let { throw IllegalStateException(it) }
    }

    // ---- residency and settings ------------------------------------------------------------------------------------

    override suspend fun residency(modelId: String): ResidencyInfo {
        val runtime = host.modelRuntime
        val loaded = runtime != null && modelId in runtime.loaded()
        return ResidencyInfo(loaded, if (loaded) runtime?.loadedSettings(modelId)?.toInfo() else null)
    }

    override suspend fun load(modelId: String) {
        val runtime = host.modelRuntime
        if (runtime == null || host.state.value != EngineState.READY) {
            throw IllegalStateException(
                when (host.state.value) {
                    EngineState.FAILED -> "Engine failed to start: ${host.lastError.value ?: "unknown error"}"
                    else -> "Start the Engine before loading a model."
                },
            )
        }
        val installed = installedState(modelId)
        val path = installed.path
        if (!installed.installed || path.isNullOrBlank()) throw IllegalStateException("Model artifact is not installed. Download it before loading.")
        val result = try {
            withTimeout(LOAD_TIMEOUT_MS) { runtime.load(modelId, path) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        result.exceptionOrNull()?.let { throw IllegalStateException(it.message ?: "Unknown error") }
    }

    override suspend fun unload(modelId: String) {
        val runtime = host.modelRuntime ?: return
        withTimeout(LOAD_TIMEOUT_MS) { runtime.unload(modelId) }
    }

    override suspend fun runSettings(modelId: String): RunSettingsInfo = (host.preferences?.runSettingsFor(modelId) ?: ModelRunSettings.DEFAULT).toInfo()

    override suspend fun setRunSettings(modelId: String, settings: RunSettingsInfo) {
        val prefs = host.preferences ?: throw IllegalStateException("This Engine has no preferences store.")
        prefs.setRunSettings(modelId, settings.toRuntime())
    }

    // ---- self tests ------------------------------------------------------------------------------------------------

    override suspend fun embed(modelId: String, input: String): List<Float> {
        val client = host.createEngineClient() ?: throw IllegalStateException("Start the Engine first.")
        val response = try {
            client.embeddings(EmbeddingsRequest(model = modelId, input = listOf(input)))
        } finally {
            client.close()
        }
        if (response == null) throw IllegalStateException("Engine did not return an embedding")
        val vector = response.data.firstOrNull()?.embedding.orEmpty()
        if (vector.isEmpty()) throw IllegalStateException("Embedding model returned an empty vector")
        return vector
    }

    override suspend fun selfTest(modelId: String, text: String, file: String): SelfTestInfo {
        val detail = detail(modelId)
        val kind = ModelKind.entries.firstOrNull { it.name == detail.kind }?.selfTestKind() ?: throw IllegalArgumentException("This kind of model has no self test.")
        val client = host.createEngineClient() ?: throw IllegalStateException("Start the Engine first.")
        val outcome = try {
            withContext(Dispatchers.IO) { runModelSelfTest(client, kind, modelId, text, file) }
        } finally {
            client.close()
        }
        return SelfTestInfo(outcome.passed, outcome.title, outcome.lines, outcome.durationMs, outcome.generatedTokens)
    }

    private fun ModelRunSettings.toInfo() = RunSettingsInfo(contextLength, threads, batchSize, gpuLayers, temperature, topP, topK, useMmap)
    private fun RunSettingsInfo.toRuntime() = ModelRunSettings(contextLength, threads, batchSize, gpuLayers, temperature, topP, topK, useMmap)

    private companion object {
        /** Same budget as Android's ModelLoader default. */
        const val LOAD_TIMEOUT_MS = 30_000L
    }
}
