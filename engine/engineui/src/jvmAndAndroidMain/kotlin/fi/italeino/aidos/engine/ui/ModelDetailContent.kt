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
    /** Null hides the per-kind test panel (chat, decisions, transcription, vision). */
    val onRunSelfTest: (() -> Unit)? = null,
    val onSelfTestTextChange: (String) -> Unit = {},
    val onSelfTestFileChange: (String) -> Unit = {},
    /** Null hides the run-settings section (hosts that cannot store them). */
    val onRunSettingsChange: ((RunSettingsUi) -> Unit)? = null,
    /** Null hides the "Reload to apply" button (hosts without a reload path). */
    val onReload: (() -> Unit)? = null,
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
    val onRunSelfTest = actions.onRunSelfTest
    val testKind = state.model?.kind?.selfTestKind()
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
                ModelHeader(model, state, loadingState)

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
                    nearest?.let { row ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FitVerdictChip(row.verdict)
                            Text("against free RAM now", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MemoryLines(model, row)
                    }
                }

                actions.onRunSettingsChange?.let { change ->
                    RunSettingsSection(model.kind, state, change, actions.onReload)
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
                } else if (onRunSelfTest != null && testKind != null && (testKind != ModelTestKind.CHAT || onTestChat == null)) {
                    SelfTestPanel(testKind, state, engineState, model.isRunnable, actions)
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

/** The test offered for a model's kind: an input or two, a Run button, and the verdict with its evidence. */
@Composable
private fun SelfTestPanel(
    kind: ModelTestKind,
    state: ModelDetailState,
    engineState: EngineState,
    isRunnable: Boolean,
    actions: ModelDetailActions,
) {
    val outcome = state.selfTestOutcome
    val ready = engineState == EngineState.READY && state.isInstalled && isRunnable
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (kind.needsFile) {
            OutlinedTextField(
                value = state.selfTestFile,
                onValueChange = actions.onSelfTestFileChange,
                label = { Text(kind.fileLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = state.selfTestText,
            onValueChange = actions.onSelfTestTextChange,
            label = { Text(kind.inputLabel) },
            placeholder = { Text(kind.defaultText) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { actions.onRunSelfTest?.invoke() },
            enabled = ready && !state.isSelfTesting,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
        ) {
            Text(
                when {
                    state.isSelfTesting -> "Testing..."
                    engineState == EngineState.STARTING -> "Engine starting..."
                    !state.isInstalled -> "Download model to test"
                    !isRunnable -> "No runtime for this format yet"
                    else -> kind.buttonLabel
                },
                color = Color.White,
            )
        }
        if (outcome != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (outcome.passed) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        (if (outcome.passed) "Passed: " else "Failed: ") + outcome.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    )
                    outcome.lines.forEach { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        testTimingLine(outcome),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Name, what it is, whether it is on this device, and what it serves. */
@Composable
private fun ModelHeader(model: ModelDetail, state: ModelDetailState, loadingState: ModelLoadingState) {
    val status = modelStatus(state, loadingState)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Badge(model.kind.displayName())
            if (model.format.isNotBlank()) Badge(model.format.uppercase())
            Badge(status.first, error = status.second)
        }
        if (model.description.isNotBlank()) {
            Text(model.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        model.kind.engineEndpoint()?.let {
            Text("Serves $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Size on disk: ${formatSize(state.installedSizeBytes ?: model.sizeMB.toLong() * 1024 * 1024)}",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Badge(text: String, error: Boolean = false) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

/** Label for the status chip, and whether it is a problem. */
internal fun modelStatus(state: ModelDetailState, loadingState: ModelLoadingState): Pair<String, Boolean> = when {
    state.isDownloading -> "Downloading ${state.downloadProgress}%" to false
    state.downloadError != null && !state.isInstalled -> "Download failed" to true
    loadingState.status == ModelLoadingStatus.LOADING -> "Loading" to false
    loadingState.status == ModelLoadingStatus.UNLOADING -> "Unloading" to false
    loadingState.status == ModelLoadingStatus.ERROR -> "Load failed" to true
    loadingState.status == ModelLoadingStatus.LOADED -> "Loaded" to false
    state.isInstalled -> "Installed" to false
    else -> "Not installed" to false
}

/** RAM the chosen context needs, next to what the device has free and in total. 0 means the host could not tell. */
@Composable
private fun MemoryLines(model: ModelDetail, row: ContextFitRow) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text("RAM needed at ${row.contextLength}K tokens: ${formatMb(row.estimatedMemoryMB)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    if (model.deviceFreeRamMB > 0) {
        val total = if (model.deviceTotalRamMB > 0) " of ${formatMb(model.deviceTotalRamMB)}" else ""
        Text("Free now: ${formatMb(model.deviceFreeRamMB)}$total", fontSize = 12.sp, color = muted)
    }
}

internal fun formatMb(mb: Int): String = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"

/** "Last run: 3.2 s, 11 tokens/s". The first run on a cold model includes loading it. */
internal fun testTimingLine(outcome: SelfTestOutcome): String {
    val seconds = "%.1f s".format(outcome.durationMs / 1000.0)
    val speed = outcome.tokensPerSecond?.let { ", %.1f tokens/s".format(it) }.orEmpty()
    return "Last run: $seconds$speed (includes loading if the model was not resident)"
}

/** Per-model run settings. Blank = the default shown as the hint; llama.cpp models only. */
@Composable
private fun RunSettingsSection(
    kind: ModelKind,
    state: ModelDetailState,
    onChange: (RunSettingsUi) -> Unit,
    onReload: (() -> Unit)?,
) {
    // Only llama.cpp models read these; ONNX decision and speech packages ignore them.
    if (kind == ModelKind.DECISION || kind == ModelKind.STT) return
    val settings = state.runSettings
    val generates = kind != ModelKind.EMBEDDING
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Run settings", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text("Blank uses the default. Changes apply the next time the model is loaded.", fontSize = 11.sp, color = muted)
        NumberField("Context length (tokens)", settings.contextLength, "2048") { onChange(settings.copy(contextLength = it)) }
        NumberField("CPU threads", settings.threads, "4") { onChange(settings.copy(threads = it)) }
        NumberField("Batch size", settings.batchSize, "512") { onChange(settings.copy(batchSize = it)) }
        NumberField("GPU layers", settings.gpuLayers, "0") { onChange(settings.copy(gpuLayers = it)) }
        if (generates) {
            DecimalField("Temperature", settings.temperature, "0.7") { onChange(settings.copy(temperature = it)) }
            DecimalField("Top-p", settings.topP, "0.95") { onChange(settings.copy(topP = it)) }
            NumberField("Top-k", settings.topK, "40") { onChange(settings.copy(topK = it)) }
        }
        val loaded = state.loadedRunSettings
        val pending = loaded != null && loaded != settings
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onChange(RunSettingsUi()) }, enabled = !settings.isDefault) { Text("Reset to defaults") }
            if (pending && onReload != null) Button(onClick = onReload) { Text("Reload to apply") }
        }
        if (pending && onReload == null) {
            Text("The loaded copy still uses the old settings; unload and load it again.", fontSize = 11.sp, color = muted)
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int?, hint: String, onValue: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { text -> if (text.isBlank()) onValue(null) else text.toIntOrNull()?.let(onValue) },
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DecimalField(label: String, value: Float?, hint: String, onValue: (Float?) -> Unit) {
    // Keep what the user is typing ("0.") instead of re-rendering the parsed float under their cursor.
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            if (it.isBlank()) onValue(null) else it.toFloatOrNull()?.let(onValue)
        },
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
