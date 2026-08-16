package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aidos.modelruntime.GlobalModelRuntime
import fi.italeino.aidos.engine.loading.ModelLoader
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
    viewModel: ModelDetailViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    
    LaunchedEffect(modelId) {
        viewModel.loadModelDetail(modelId)
    }

    var modelLoadingState by remember {
        mutableStateOf(
            ModelLoadingState(
                modelId = modelId,
                status = ModelLoadingStatus.NOT_LOADED,
                estimatedMemoryMB = 2_400
            )
        )
    }
    val coroutineScope = rememberCoroutineScope()
    val modelLoader = remember { globalModelRuntime?.let { ModelLoader(it) } }

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
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.error != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
            }
        } else if (state.model != null) {
            val model = state.model!!

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    model.description,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "Size: ${model.sizeMB} MB",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                ContextFitTable(model.contextFitTable)

                LicenseAcceptanceCard(
                    licenseName = model.licenseName,
                    licenseText = model.licenseText,
                    accepted = state.licenseAccepted,
                    onAcceptedChange = { accepted -> viewModel.toggleLicenseAccepted(accepted) }
                )

                Button(
                    onClick = { viewModel.startDownload() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    enabled = true, // Temporarily bypass license for demo
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        if (state.isDownloading) "Downloading (${state.downloadProgress}%)..." else "Download Model",
                        color = Color.White
                    )
                }

                // Phase E: Test Chat button
                Button(
                    onClick = {
                        onTestChatClick?.invoke(model.id, model.name)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(
                        "Test Chat",
                        color = Color.White
                    )
                }

                // Phase E: Load to Memory button
                Button(
                    onClick = {
                        if (modelLoadingState.status == ModelLoadingStatus.LOADED) {
                            // Unload from memory
                            modelLoadingState = modelLoadingState.copy(status = ModelLoadingStatus.UNLOADING)
                            coroutineScope.launch {
                                if (modelLoader != null) {
                                    modelLoader.unloadModel(modelId) { progress ->
                                        modelLoadingState = modelLoadingState.copy(loadProgress = progress)
                                    }.onSuccess {
                                        modelLoadingState = modelLoadingState.copy(status = ModelLoadingStatus.NOT_LOADED)
                                    }.onFailure { error ->
                                        modelLoadingState = modelLoadingState.copy(
                                            status = ModelLoadingStatus.ERROR,
                                            error = error.message
                                        )
                                    }
                                }
                            }
                        } else {
                            // Load to memory
                            modelLoadingState = modelLoadingState.copy(
                                status = ModelLoadingStatus.LOADING,
                                loadProgress = 0,
                                error = null
                            )
                            coroutineScope.launch {
                                if (modelLoader != null) {
                                    modelLoader.loadModel(
                                        modelId = modelId,
                                        estimatedSizeMB = state.model?.sizeMB ?: 2_400,
                                        onProgress = { progress ->
                                            modelLoadingState = modelLoadingState.copy(loadProgress = progress)
                                        },
                                        onError = { error ->
                                            modelLoadingState = modelLoadingState.copy(
                                                status = ModelLoadingStatus.ERROR,
                                                error = error
                                            )
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
                                } else {
                                    // Fallback: no model runtime, just simulate
                                    modelLoadingState = modelLoadingState.copy(
                                        status = ModelLoadingStatus.LOADED,
                                        loadTimeMs = System.currentTimeMillis()
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    enabled = !state.isDownloading && modelLoadingState.status != ModelLoadingStatus.LOADING && modelLoadingState.status != ModelLoadingStatus.UNLOADING,
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
            }
        }
    }
}
