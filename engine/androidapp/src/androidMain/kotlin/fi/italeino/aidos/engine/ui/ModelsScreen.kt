package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import fi.italeino.aidos.engine.EngineState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aidos.kernel.ModelKind
import fi.italeino.aidos.engine.EngineService
import kotlinx.coroutines.launch

/**
 * Models screen (RFC-0103, Phase D).
 *
 * Three tabs:
 * 1. Local: Installed models
 * 2. Cookbook: Suggested models plus Hugging Face search, with fit scoring
 * 3. Providers: Remote providers (not supported by the Engine yet)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModelsScreen(
    onModelSelected: (String) -> Unit,
    viewModel: ModelsViewModel = viewModel(),
) {
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(
                message = it,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Long
            )
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(title = { Text("Models") })
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    listOf("Local", "Cookbook", "Providers").forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (engineState != EngineState.READY) {
                EngineNotReadyBanner(engineState)
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp),
                beyondViewportPageCount = 0
            ) { page ->
                when (page) {
                    0 -> LocalModelsPane(onModelSelected, viewModel)
                    1 -> CookbookPane(onModelSelected, viewModel)
                    2 -> ProvidersPane()
                }
            }
        }
    }
}

@Composable
private fun EngineNotReadyBanner(state: EngineState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            when (state) {
                EngineState.FAILED -> "The Engine failed to start, so models can't be listed. Restart it from Home."
                else -> "The Engine is starting or turned off. Models appear once it is running — you can start it from Home."
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun LocalModelsPane(onModelSelected: (String) -> Unit, viewModel: ModelsViewModel) {
    var showDeleteDialog by remember { mutableStateOf<CookbookModel?>(null) }
    val localModels by viewModel.localModels.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    if (isLoading && localModels.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (localModels.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No models installed. Install a suggested model from the Cookbook tab.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(24.dp),
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(localModels, key = { it.id }) { model ->
                LocalModelCard(
                    model = model,
                    onDeleteClick = { showDeleteDialog = model },
                    onCardClick = { onModelSelected(model.id) },
                )
            }
        }
    }

    showDeleteDialog?.let { model ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Model") },
            text = {
                Text("Delete ${model.name}? The model file is removed from this device and it is unloaded if resident.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteModel(model.id)
                        showDeleteDialog = null
                    }
                ) {
                    Text("Delete", color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun LocalModelCard(
    model: CookbookModel,
    onDeleteClick: () -> Unit,
    onCardClick: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    model.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "${model.quantization} • ${formatSize(model.sizeBytes)}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (model.isRunnable) "Installed · tap to load or test"
                    else "Installed · no runtime for this format yet",
                    fontSize = 10.sp,
                    color = if (model.isRunnable) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFF97316),
                )
            }
            IconButton(onClick = onDeleteClick) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Delete",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Cookbook pane: suggested models, then the Hugging Face catalog with fit scoring (RFC-0103).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CookbookPane(onModelSelected: (modelId: String) -> Unit, viewModel: ModelsViewModel) {
    val cookbookModels by viewModel.cookbookModels.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedKind by remember { mutableStateOf<ModelKind?>(null) }
    var isCodingOnly by remember { mutableStateOf(false) }
    var minContext by remember { mutableStateOf<Int?>(null) }
    var sizeFilterMb by remember { mutableStateOf<Int?>(null) }
    var showSizeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery, selectedKind, isCodingOnly, minContext, sizeFilterMb) {
        val effectiveQuery = if (isCodingOnly) {
            if (searchQuery.isBlank()) "code" else "$searchQuery code"
        } else {
            searchQuery
        }
        viewModel.searchRemote(effectiveQuery, selectedKind, minContext, sizeFilterMb)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (suggestions.isNotEmpty()) {
            item {
                Text(
                    "Suggested",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }
            items(suggestions, key = { "suggestion:" + it.model.id }) { suggestion ->
                SuggestedModelCard(
                    suggestion = suggestion,
                    onInstall = { viewModel.installSuggestion(suggestion.model.id) },
                    onOpen = { onModelSelected(suggestion.model.id) },
                    onDismiss = { viewModel.dismissSuggestion(suggestion.model.id) },
                )
            }
        }

        item {
            Text(
                "Hugging Face",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        item {
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search GGUF models on Hugging Face...",
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        item {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    FilterChip(
                        label = { Text("Size", fontSize = 10.sp) },
                        onClick = { showSizeDialog = true },
                        selected = sizeFilterMb != null
                    )
                    if (showSizeDialog) {
                        var sliderValueState = remember { mutableStateOf((sizeFilterMb ?: 100).toFloat()) }
                        AlertDialog(
                            onDismissRequest = { showSizeDialog = false },
                            title = { Text("Filter by size (MB)") },
                            text = {
                                Column {
                                    Slider(
                                        value = sliderValueState.value,
                                        onValueChange = { sliderValueState.value = it },
                                        valueRange = 1f..20000f,
                                        steps = 100
                                    )
                                    Text("Max size: ${sliderValueState.value.toInt()} MB", fontSize = 12.sp)
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    sizeFilterMb = sliderValueState.value.toInt()
                                    showSizeDialog = false
                                }) { Text("Apply") }
                            },
                            dismissButton = { TextButton(onClick = { showSizeDialog = false }) { Text("Cancel") } }
                        )
                    }
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
                item {
                    FilterChip(
                        label = { Text("All", fontSize = 10.sp) },
                        onClick = {
                            selectedKind = null
                            minContext = null
                            isCodingOnly = false
                        },
                        selected = (selectedKind == null && minContext == null && !isCodingOnly)
                    )
                }
                item {
                    FilterChip(
                        label = { Text("LLM", fontSize = 10.sp) },
                        onClick = { selectedKind = if (selectedKind == ModelKind.LLM) null else ModelKind.LLM },
                        selected = selectedKind == ModelKind.LLM
                    )
                }
                item {
                    FilterChip(
                        label = { Text("Coding", fontSize = 10.sp) },
                        onClick = {
                            isCodingOnly = !isCodingOnly
                            if (isCodingOnly) selectedKind = ModelKind.LLM
                        },
                        selected = isCodingOnly
                    )
                }
                item {
                    FilterChip(
                        label = { Text("Embedding", fontSize = 10.sp) },
                        onClick = { selectedKind = if (selectedKind == ModelKind.EMBEDDING) null else ModelKind.EMBEDDING },
                        selected = selectedKind == ModelKind.EMBEDDING
                    )
                }
                item {
                    FilterChip(
                        label = { Text("8K+ CTX", fontSize = 10.sp) },
                        onClick = { minContext = if (minContext == 8192) null else 8192 },
                        selected = minContext == 8192
                    )
                }
                item {
                    FilterChip(
                        label = { Text("32K+ CTX", fontSize = 10.sp) },
                        onClick = { minContext = if (minContext == 32768) null else 32768 },
                        selected = minContext == 32768
                    )
                }
            }
        }
        item {
            if (isSearching) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                Spacer(modifier = Modifier.height(2.dp))
            }
        }

        items(cookbookModels, key = { "hub:" + it.id }) { model ->
            CookbookModelCard(model, onTap = { onModelSelected(model.id) })
        }

        if (cookbookModels.isEmpty() && !isSearching) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No Hugging Face results", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestedModelCard(
    suggestion: SuggestionUi,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val model = suggestion.model
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "${model.repoId} • ${model.format.uppercase()} • ≈${formatSize(model.approxSizeBytes)}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Remove suggestion", modifier = Modifier.size(16.dp))
                }
            }
            Text(
                model.description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (suggestion.isInstalling) {
                LinearProgressIndicator(
                    progress = { suggestion.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            suggestion.error?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    suggestion.isInstalled -> OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                        Text("Installed · Open", fontSize = 12.sp)
                    }
                    suggestion.isInstalling -> Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                        Text("Installing ${suggestion.progressPercent}%", fontSize = 12.sp)
                    }
                    else -> Button(onClick = onInstall, modifier = Modifier.weight(1f)) {
                        Text(if (suggestion.error != null) "Retry install" else "Install", fontSize = 12.sp)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Remove suggestion", fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * Providers pane. The Engine runs models on-device only today; RFC-0103's remote providers are
 * not implemented, so this says so instead of listing providers that do nothing.
 */
@Composable
private fun ProvidersPane() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "Remote providers are not supported by Aidos Engine yet. All models run on this device.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
