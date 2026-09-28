package fi.italeino.aidos.engine.ui

import android.content.Intent
import fi.italeino.aidos.engine.EngineState
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aidos.modelruntime.GlobalModelRuntime
import fi.italeino.aidos.engine.EngineService
import fi.italeino.aidos.engine.loading.ModelLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Model detail / acquire screen (RFC-0103, RFC-0022, Phase D/E).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDetailScreen(
    modelId: String,
    onBackClick: () -> Unit,
    onTestChatClick: ((modelId: String, modelName: String) -> Unit)? = null,
    globalModelRuntime: GlobalModelRuntime? = null,
    engineState: EngineState = EngineState.STARTING,
    viewModel: ModelDetailViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(modelId, engineState) {
        viewModel.loadModelDetail(modelId)
    }

    var modelLoadingState by remember {
        mutableStateOf(
            ModelLoadingState(
                modelId = modelId,
                status = ModelLoadingStatus.NOT_LOADED,
            )
        )
    }
    val coroutineScope = rememberCoroutineScope()
    val modelLoader = remember(globalModelRuntime) {
        globalModelRuntime?.let { ModelLoader(it) }
    }

    LaunchedEffect(modelId, modelLoader, engineState) {
        if (modelLoader == null || engineState != EngineState.READY) {
            modelLoadingState = modelLoadingState.copy(status = ModelLoadingStatus.NOT_LOADED)
            return@LaunchedEffect
        }

        while (true) {
            val loaded = modelLoader.isModelLoaded(modelId)
            val current = modelLoadingState.status
            if (current != ModelLoadingStatus.LOADING && current != ModelLoadingStatus.UNLOADING) {
                modelLoadingState = modelLoadingState.copy(
                    status = if (loaded) ModelLoadingStatus.LOADED else ModelLoadingStatus.NOT_LOADED,
                    error = if (loaded) null else modelLoadingState.error,
                )
            }
            delay(500)
        }
    }

    // Hoisted from the content so the choice survives recomposition and is persisted per change.
    var preferredContext by remember { mutableStateOf(SettingsStore.getPreferredContextLength(context).coerceIn(512, 32768)) }
    LaunchedEffect(preferredContext) {
        SettingsStore.setPreferredContextLength(context, preferredContext)
    }

    val onToggleLoad: () -> Unit = onToggleLoad@{
        if (modelLoader == null) {
            modelLoadingState = modelLoadingState.copy(
                status = ModelLoadingStatus.ERROR,
                error = when (engineState) {
                    EngineState.STARTING -> "Engine is still starting. Try again in a moment."
                    EngineState.FAILED -> "Engine failed to start. Check the Engine notification for details."
                    EngineState.READY -> "Engine runtime is unavailable. Restart the Engine."
                }
            )
            return@onToggleLoad
        }

        if (modelLoadingState.status == ModelLoadingStatus.LOADED) {
            modelLoadingState = modelLoadingState.copy(status = ModelLoadingStatus.UNLOADING)
            coroutineScope.launch {
                modelLoader.unloadModel(modelId) { progress ->
                    modelLoadingState = modelLoadingState.copy(loadProgress = progress)
                }.onSuccess {
                    modelLoadingState = modelLoadingState.copy(status = ModelLoadingStatus.NOT_LOADED)
                }.onFailure { error ->
                    modelLoadingState = modelLoadingState.copy(status = ModelLoadingStatus.ERROR, error = error.message)
                }
            }
        } else {
            val installedPath = state.installedPath
            if (!state.isInstalled || installedPath.isNullOrBlank()) {
                viewModel.refreshInstalledState(modelId)
                modelLoadingState = modelLoadingState.copy(
                    status = ModelLoadingStatus.ERROR,
                    error = "Model artifact is not installed. Download it before loading."
                )
                return@onToggleLoad
            }
            modelLoadingState = modelLoadingState.copy(
                status = ModelLoadingStatus.LOADING,
                loadProgress = 0,
                error = null
            )
            coroutineScope.launch {
                modelLoader.loadModel(
                    modelId = modelId,
                    artifactPath = installedPath,
                    estimatedSizeMB = state.model?.sizeMB ?: 2_400,
                    onProgress = { progress ->
                        modelLoadingState = modelLoadingState.copy(loadProgress = progress)
                    },
                    onError = { error ->
                        modelLoadingState = modelLoadingState.copy(status = ModelLoadingStatus.ERROR, error = error)
                    }
                ).onSuccess {
                    modelLoadingState = modelLoadingState.copy(
                        status = ModelLoadingStatus.LOADED,
                        loadTimeMs = System.currentTimeMillis()
                    )
                }.onFailure { error ->
                    modelLoadingState = modelLoadingState.copy(
                        status = ModelLoadingStatus.ERROR,
                        error = error.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.model?.name?.take(30) ?: "Model") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        ModelDetailContent(
            state = state,
            loadingState = modelLoadingState,
            engineState = engineState,
            preferredContext = preferredContext,
            actions = ModelDetailActions(
                onPreferredContextChange = { preferredContext = it },
                onOpenUrl = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                onStartDownload = {
                    if (state.downloadError != null) viewModel.clearDownloadError()
                    viewModel.startDownload()
                },
                onCancelDownload = viewModel::cancelDownload,
                onDelete = { state.model?.let { viewModel.deleteInstalledModel(it.id) } },
                onEmbeddingInputChange = viewModel::setEmbeddingInput,
                onTestEmbeddings = viewModel::testEmbeddings,
                onToggleLoad = onToggleLoad,
                onTestChat = { state.model?.let { onTestChatClick?.invoke(it.id, it.name) } },
            ),
            modifier = Modifier.padding(innerPadding),
        )
    }
}
