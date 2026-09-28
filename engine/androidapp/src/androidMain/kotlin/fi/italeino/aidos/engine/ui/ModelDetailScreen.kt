package fi.italeino.aidos.engine.ui

import android.content.Intent
import fi.italeino.aidos.engine.EngineState
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aidos.kernel.ModelKind
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
        when {
            state.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                }
            }

            state.model != null -> {
                val model = state.model!!

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(model.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Size: ${formatSize(state.installedSizeBytes ?: model.sizeMB.toLong() * 1024 * 1024)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    val preferred = remember { SettingsStore.getPreferredContextLength(context) }
                    var sliderValue by remember { mutableStateOf(preferred.coerceIn(512, 32768)) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Preferred context: ${sliderValue} tokens", fontSize = 12.sp)
                        Slider(
                            value = sliderValue.toFloat(),
                            onValueChange = { sliderValue = it.toInt() },
                            valueRange = 512f..32768f,
                            steps = 31,
                            modifier = Modifier.fillMaxWidth()
                        )
                        val nearest = model.contextFitTable.minByOrNull { kotlin.math.abs(it.contextLength * 1024 - sliderValue) }
                        nearest?.let { FitVerdictChip(it.verdict) }
                        LaunchedEffect(sliderValue) {
                            SettingsStore.setPreferredContextLength(context, sliderValue)
                        }
                    }

                    LicenseInfoCard(
                        licenseName = model.licenseName,
                        modelUrl = model.modelUrl,
                        onReviewTerms = {
                            model.modelUrl?.let { url ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    )

                    if (state.isInstalled) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Model downloaded",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        var showDeleteConfirm by remember { mutableStateOf(false) }
                        if (showDeleteConfirm) {
                            AlertDialog(
                                onDismissRequest = { showDeleteConfirm = false },
                                confirmButton = {
                                    TextButton(onClick = {
                                        viewModel.deleteInstalledModel(model.id)
                                        showDeleteConfirm = false
                                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                                },
                                title = { Text("Delete model") },
                                text = { Text("Are you sure you want to permanently delete this model and its downloaded artifacts? This cannot be undone.") }
                            )
                        }
                        OutlinedButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Delete model")
                        }
                    }

                    Button(
                        onClick = {
                            if (state.downloadError != null) viewModel.clearDownloadError()
                            viewModel.startDownload()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        enabled = !state.isInstalled &&
                            !state.isDownloading &&
                            modelLoadingState.status != ModelLoadingStatus.LOADING &&
                            modelLoadingState.status != ModelLoadingStatus.UNLOADING,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            if (state.downloadError != null) "Retry Download"
                            else if (state.isDownloading) "Downloading (${state.downloadProgress}%)..."
                            else if (state.isInstalled) "Model Downloaded"
                            else "Download Model",
                            color = Color.White
                        )
                    }

                    if (state.isDownloading) {
                        val downloadProgress = (state.downloadProgress / 100f).coerceIn(0f, 1f)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator(progress = { downloadProgress }, modifier = Modifier.size(40.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Downloading model...", fontWeight = FontWeight.SemiBold)
                                    Text("${state.downloadProgress}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedButton(onClick = { viewModel.cancelDownload() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Cancel download")
                                    }
                                }
                            }
                        }
                    }

                    if (!state.isDownloading && state.downloadError != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Download failed", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(state.downloadError!!, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = {
                                        viewModel.clearDownloadError()
                                        viewModel.startDownload()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Retry download") }
                            }
                        }
                    }

                    if (model.kind == ModelKind.EMBEDDING) {
                        OutlinedTextField(
                            value = state.embeddingInput,
                            onValueChange = viewModel::setEmbeddingInput,
                            label = { Text("Text to embed") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { viewModel.testEmbeddings() },
                            enabled = state.isInstalled && !state.isEmbeddingTesting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text(if (state.isEmbeddingTesting) "Testing embeddings..." else "Test Embeddings", color = Color.White)
                        }
                        if (state.embeddingError != null) {
                            Text(state.embeddingError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                        if (state.embeddingVector.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Vector (${state.embeddingVector.size} dims)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text(
                                        state.embeddingVector.take(24).joinToString(prefix = "[", postfix = if (state.embeddingVector.size > 24) ", ...]" else "]") { "%.4f".format(it) },
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        Button(
                            onClick = { onTestChatClick?.invoke(model.id, model.name) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            enabled = engineState == EngineState.READY && state.isInstalled && model.isRunnable,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text(
                                when {
                                    engineState == EngineState.STARTING -> "Engine starting..."
                                    !state.isInstalled -> "Download model to test"
                                    !model.isRunnable -> "No runtime for this format yet"
                                    else -> "Test Chat"
                                },
                                color = Color.White
                            )
                        }
                    }

                    if (modelLoadingState.status == ModelLoadingStatus.LOADING ||
                        modelLoadingState.status == ModelLoadingStatus.UNLOADING
                    ) {
                        val progress = (modelLoadingState.loadProgress / 100f).coerceIn(0f, 1f)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                if (modelLoadingState.status == ModelLoadingStatus.LOADING) {
                                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(40.dp))
                                } else {
                                    CircularProgressIndicator(modifier = Modifier.size(40.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        if (modelLoadingState.status == ModelLoadingStatus.LOADING) "Loading model..." else "Unloading model...",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (modelLoadingState.status == ModelLoadingStatus.LOADING) {
                                        Text("${modelLoadingState.loadProgress}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (modelLoader == null) {
                                modelLoadingState = modelLoadingState.copy(
                                    status = ModelLoadingStatus.ERROR,
                                    error = when (engineState) {
                                        EngineState.STARTING -> "Engine is still starting. Try again in a moment."
                                        EngineState.FAILED -> "Engine failed to start. Check the Engine notification for details."
                                        EngineState.READY -> "Engine runtime is unavailable. Restart the Engine."
                                    }
                                )
                                return@Button
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
                                    return@Button
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
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        enabled = engineState == EngineState.READY &&
                            state.isInstalled &&
                            model.isRunnable &&
                            !state.isDownloading &&
                            modelLoadingState.status != ModelLoadingStatus.LOADING &&
                            modelLoadingState.status != ModelLoadingStatus.UNLOADING,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (modelLoadingState.status == ModelLoadingStatus.LOADED)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text(
                            when (modelLoadingState.status) {
                                ModelLoadingStatus.NOT_LOADED -> "Load to Memory"
                                ModelLoadingStatus.LOADING -> "Loading (${modelLoadingState.loadProgress}%)"
                                ModelLoadingStatus.LOADED -> "Unload from Memory"
                                ModelLoadingStatus.ERROR -> "Retry Load"
                                ModelLoadingStatus.UNLOADING -> "Unloading..."
                            },
                            color = Color.White
                        )
                    }

                    if (modelLoadingState.status == ModelLoadingStatus.ERROR && modelLoadingState.error != null) {
                        Text(modelLoadingState.error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
