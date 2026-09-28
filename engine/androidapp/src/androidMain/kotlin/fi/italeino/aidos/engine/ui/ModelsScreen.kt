package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aidos.kernel.ModelKind
import kotlinx.coroutines.launch

/**
 * Models screen (RFC-0103, Phase D).
 *
 * Three tabs:
 * 1. Local: Installed models (formerly Storage screen)
 * 2. Cookbook: Browsable catalog with Hugging Face integration
 * 3. Providers: Remote providers
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModelsScreen(
    onModelSelected: (String) -> Unit,
    onProviderSelected: (String) -> Unit,
    onModelConfigClick: (String) -> Unit,
    viewModel: ModelsViewModel = viewModel(),
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Refresh state when entering screen
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    // Show error message if it changes
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(
                message = it,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Short
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
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(0) }
                        },
                        text = { Text("Local") }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(1) }
                        },
                        text = { Text("Cookbook") }
                    )
                    Tab(
                        selected = pagerState.currentPage == 2,
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(2) }
                        },
                        text = { Text("Providers") }
                    )
                }
            }
        }
    ) { innerPadding ->
        // To fix IllegalStateException (infinity constraints), ensure the pager 
        // area is explicitly constrained by the Scaffold's innerPadding.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                // Ensure pager doesn't allow infinite height in its children
                beyondViewportPageCount = 0 
            ) { page ->
                when (page) {
                    0 -> LocalModelsPane(onModelConfigClick, viewModel)
                    1 -> CookbookPane(onModelSelected, viewModel)
                    2 -> ProvidersPane(onProviderSelected)
                }
            }
        }
    }
}

@Composable
private fun LocalModelsPane(onModelConfigClick: (String) -> Unit, viewModel: ModelsViewModel) {
    var showDeleteDialog by remember { mutableStateOf<CookbookModel?>(null) }
    val localModels by viewModel.localModels.collectAsState()
    val enabledModelIds by viewModel.enabledModelIds.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (localModels.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No models installed", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(localModels) { model ->
                LocalModelCard(
                    model = model,
                    isEnabled = enabledModelIds.contains(model.id),
                    onToggleEnabled = { enabled ->
                        viewModel.toggleModelEnabled(model.id, enabled)
                    },
                    onDeleteClick = { showDeleteDialog = model },
                    onCardClick = { onModelConfigClick(model.id) },
                )
            }
        }
    }

    if (showDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Model") },
            text = {
                Text("Are you sure you want to delete ${showDeleteDialog?.name}? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog?.let { viewModel.deleteModel(it.id) }
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
    isEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
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
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Installed",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.scale(0.7f)
                    )
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
            Text(
                "${model.sizeMB} MB",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// Extension to Switch to scale it down slightly to fit the high-density UI
@Composable
private fun Modifier.scale(scale: Float) = this.then(
    Modifier.graphicsLayer(scaleX = scale, scaleY = scale)
)

/**
 * Cookbook pane: Browsable Hugging Face catalog with fit scoring (RFC-0103).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CookbookPane(onModelSelected: (modelId: String) -> Unit, viewModel: ModelsViewModel) {
    val cookbookModels by viewModel.cookbookModels.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    
    var searchQuery by remember { mutableStateOf("") }
    var selectedKind by remember { mutableStateOf<ModelKind?>(null) }
    var isCodingOnly by remember { mutableStateOf(false) }
    var minContext by remember { mutableStateOf<Int?>(null) }

    // Trigger search when query or filters change
    LaunchedEffect(searchQuery, selectedKind, isCodingOnly, minContext) {
        val effectiveQuery = if (isCodingOnly) {
            if (searchQuery.isBlank()) "code" else "$searchQuery code"
        } else {
            searchQuery
        }
        viewModel.searchRemote(effectiveQuery, selectedKind, minContext)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Search HuggingFace...",
            modifier = Modifier.padding(vertical = 8.dp)
        )

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
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
                    },
                    selected = (selectedKind == null && minContext == null)
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
                    label = { Text("Vision", fontSize = 10.sp) },
                    onClick = { selectedKind = if (selectedKind == ModelKind.VISION) null else ModelKind.VISION },
                    selected = selectedKind == ModelKind.VISION
                )
            }
            item {
                FilterChip(
                    label = { Text("STT", fontSize = 10.sp) },
                    onClick = { selectedKind = if (selectedKind == ModelKind.STT) null else ModelKind.STT },
                    selected = selectedKind == ModelKind.STT
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

        if (isSearching) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.secondary
            )
        } else {
            Spacer(modifier = Modifier.height(2.dp))
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(cookbookModels) { model ->
                CookbookModelCard(model, onTap = { onModelSelected(model.id) })
            }
            
            if (cookbookModels.isEmpty() && !isSearching) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No models found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                TextButton(
                    onClick = { /* Could open Hugging Face search or similar */ },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Add from Hugging Face", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

/**
 * Providers pane: remote model provider configuration (RFC-0103).
 */
@Composable
private fun ProvidersPane(onProviderSelected: (providerId: String) -> Unit) {
    val context = LocalContext.current
    val state = remember(context) {
        val prefs = context.getSharedPreferences("aidos_engine_provider_state", Context.MODE_PRIVATE)
        val defaults = listOf(
            RemoteProvider(
                "openai",
                "OpenAI",
                ProviderConfigStatus.ENABLED,
                System.currentTimeMillis() - 3_600_000
            ),
            RemoteProvider("anthropic", "Anthropic (Claude)", ProviderConfigStatus.NOT_CONFIGURED),
            RemoteProvider("together", "Together AI", ProviderConfigStatus.CONFIGURED_DISABLED),
        )
        ProvidersPaneState(
            providers = defaults.map { provider ->
                val enabled = prefs.getBoolean("${provider.id}:enabled", provider.status == ProviderConfigStatus.ENABLED)
                val apiKeyValid = prefs.getBoolean("${provider.id}:api_key_valid", provider.status != ProviderConfigStatus.NOT_CONFIGURED)
                provider.copy(
                    status = when {
                        enabled -> ProviderConfigStatus.ENABLED
                        apiKeyValid -> ProviderConfigStatus.CONFIGURED_DISABLED
                        else -> ProviderConfigStatus.NOT_CONFIGURED
                    },
                )
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Remote Providers",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        items(state.providers) { provider ->
            ProviderStatusRow(provider, onTap = { onProviderSelected(provider.id) })
        }
    }
}
