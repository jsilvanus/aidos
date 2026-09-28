package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aidos.kernel.ModelKind
import fi.italeino.aidos.engine.EngineState

/** User actions from the model detail view. */
class ModelDetailActions(
    val onPreferredContextChange: (Int) -> Unit,
    val onOpenUrl: (String) -> Unit,
    /** Start (or retry) the download; the host clears any previous download error first. */
    val onStartDownload: () -> Unit,
    val onCancelDownload: () -> Unit,
    val onDelete: () -> Unit,
    val onEmbeddingInputChange: (String) -> Unit,
    val onTestEmbeddings: () -> Unit,
    /** Load when not resident, unload when resident. The host owns [ModelLoadingState]. */
    val onToggleLoad: () -> Unit,
    /** Null hides the Test Chat button (hosts without a chat screen). */
    val onTestChat: (() -> Unit)? = null,
)

/**
 * Model detail / acquire content (RFC-0103, RFC-0022, Phase D/E): size, preferred context with fit
 * verdict, license, download/delete, embeddings test, and load/unload. No scaffold: Android wraps
 * it in a screen with a back arrow, desktop in the right-hand panel.
 */
@Composable
fun ModelDetailContent(
    state: ModelDetailState,
    loadingState: ModelLoadingState,
    engineState: EngineState,
    preferredContext: Int,
    actions: ModelDetailActions,
    modifier: Modifier = Modifier,
) {
    // Locals: ModelDetailState lives in :enginehost, so its properties can't be smart-cast here.
    val error = state.error
    val model = state.model
    val downloadError = state.downloadError
    val embeddingError = state.embeddingError
    val loadError = loadingState.error
    val onTestChat = actions.onTestChat
    when {
        state.isLoading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        error != null -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        }

        model != null -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
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

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Preferred context: $preferredContext tokens", fontSize = 12.sp)
                    Slider(
                        value = preferredContext.toFloat(),
                        onValueChange = { actions.onPreferredContextChange(it.toInt()) },
                        valueRange = 512f..32768f,
                        steps = 31,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val nearest = model.contextFitTable.minByOrNull { kotlin.math.abs(it.contextLength * 1024 - preferredContext) }
                    nearest?.let { FitVerdictChip(it.verdict) }
                }

                LicenseInfoCard(
                    licenseName = model.licenseName,
                    modelUrl = model.modelUrl,
                    onReviewTerms = { model.modelUrl?.let(actions.onOpenUrl) }
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
                                    actions.onDelete()
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
                    onClick = actions.onStartDownload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    enabled = !state.isInstalled &&
                        !state.isDownloading &&
                        loadingState.status != ModelLoadingStatus.LOADING &&
                        loadingState.status != ModelLoadingStatus.UNLOADING,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        if (downloadError != null) "Retry Download"
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
                                OutlinedButton(onClick = actions.onCancelDownload, modifier = Modifier.fillMaxWidth()) {
                                    Text("Cancel download")
                                }
                            }
                        }
                    }
                }

                if (!state.isDownloading && downloadError != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Download failed", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(downloadError, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = actions.onStartDownload,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Retry download") }
                        }
                    }
                }

                if (model.kind == ModelKind.EMBEDDING) {
                    OutlinedTextField(
                        value = state.embeddingInput,
                        onValueChange = actions.onEmbeddingInputChange,
                        label = { Text("Text to embed") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = actions.onTestEmbeddings,
                        enabled = state.isInstalled && !state.isEmbeddingTesting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text(if (state.isEmbeddingTesting) "Testing embeddings..." else "Test Embeddings", color = Color.White)
                    }
                    if (embeddingError != null) {
                        Text(embeddingError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
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
                } else if (onTestChat != null) {
                    Button(
                        onClick = onTestChat,
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

                if (loadingState.status == ModelLoadingStatus.LOADING ||
                    loadingState.status == ModelLoadingStatus.UNLOADING
                ) {
                    val progress = (loadingState.loadProgress / 100f).coerceIn(0f, 1f)
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
                            if (loadingState.status == ModelLoadingStatus.LOADING) {
                                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(40.dp))
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(40.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (loadingState.status == ModelLoadingStatus.LOADING) "Loading model..." else "Unloading model...",
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (loadingState.status == ModelLoadingStatus.LOADING) {
                                    Text("${loadingState.loadProgress}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = actions.onToggleLoad,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    enabled = engineState == EngineState.READY &&
                        state.isInstalled &&
                        model.isRunnable &&
                        !state.isDownloading &&
                        loadingState.status != ModelLoadingStatus.LOADING &&
                        loadingState.status != ModelLoadingStatus.UNLOADING,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (loadingState.status == ModelLoadingStatus.LOADED)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(
                        when (loadingState.status) {
                            ModelLoadingStatus.NOT_LOADED -> "Load to Memory"
                            ModelLoadingStatus.LOADING -> "Loading (${loadingState.loadProgress}%)"
                            ModelLoadingStatus.LOADED -> "Unload from Memory"
                            ModelLoadingStatus.ERROR -> "Retry Load"
                            ModelLoadingStatus.UNLOADING -> "Unloading..."
                        },
                        color = Color.White
                    )
                }

                if (loadingState.status == ModelLoadingStatus.ERROR && loadError != null) {
                    Text(loadError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        }
    }
}
