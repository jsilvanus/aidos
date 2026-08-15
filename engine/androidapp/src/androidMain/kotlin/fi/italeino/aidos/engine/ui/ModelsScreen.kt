package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Models screen (RFC-0103, Phase D).
 *
 * Three tabs:
 * 1. Local: Installed models (formerly Storage screen)
 * 2. Cookbook: Browsable catalog
 * 3. Providers: Remote providers
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModelsScreen(
    onModelSelected: (String) -> Unit,
    onProviderSelected: (String) -> Unit,
    onModelConfigClick: (String) -> Unit,
    viewModel: ModelsViewModel = viewModel()
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    // Refresh state when entering screen
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Models") })
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                        text = { Text("Local") }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                        text = { Text("Cookbook") }
                    )
                    Tab(
                        selected = pagerState.currentPage == 2,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(2) } },
                        text = { Text("Providers") }
                    )
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(8.dp),
        ) { page ->
            when (page) {
                0 -> LocalModelsPane(onModelConfigClick, viewModel)
                1 -> CookbookPane(onModelSelected, viewModel)
                2 -> ProvidersPane(onProviderSelected)
            }
        }
    }
}

@Composable
private fun LocalModelsPane(onModelConfigClick: (String) -> Unit, viewModel: ModelsViewModel) {
    var showDeleteDialog by remember { mutableStateOf<CookbookModel?>(null) }
    val localModels by viewModel.localModels.collectAsState()
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
                    onToggleEnabled = { /* TODO: Implement enabled state in VM */ },
                    onDeleteClick = { showDeleteDialog = model },
                    onCardClick = { onModelConfigClick(model.id) }
                )
            }
        }
    }

    if (showDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Model") },
            text = { Text("Are you sure you want to delete ${showDeleteDialog?.name}? This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    // TODO: Implement delete in VM
                    showDeleteDialog = null
                }) {
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
    onToggleEnabled: (Boolean) -> Unit,
    onDeleteClick: () -> Unit,
    onCardClick: () -> Unit
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
                    Text("Installed", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = true, // Mock enabled state
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.scale(0.7f)
                    )
                    IconButton(onClick = onDeleteClick) {
                        Icon(Icons.Default.Close, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
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
 * Cookbook pane: local models, searchable with filters and fit scoring (RFC-0103).
 *
 * Moved from HomeScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CookbookPane(onModelSelected: (modelId: String) -> Unit, viewModel: ModelsViewModel) {
    val cookbookModels by viewModel.cookbookModels.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Search HuggingFace...",
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(label = { Text("All", fontSize = 10.sp) }, onClick = { selectedFilter = null }, selected = selectedFilter == null)
            FilterChip(label = { Text("Perfect", fontSize = 10.sp) }, onClick = { selectedFilter = "RUNS_WELL" }, selected = selectedFilter == "RUNS_WELL")
            FilterChip(label = { Text("Tight", fontSize = 10.sp) }, onClick = { selectedFilter = "RUNS_TIGHT" }, selected = selectedFilter == "RUNS_TIGHT")
            FilterChip(label = { Text("LLM", fontSize = 10.sp) }, onClick = { selectedFilter = "LLM" }, selected = selectedFilter == "LLM")
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(cookbookModels) { model ->
                CookbookModelCard(model, onTap = { onModelSelected(model.id) })
            }
            item {
                TextButton(onClick = { }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Add from Hugging Face", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

/**
 * Providers pane: remote model provider configuration (RFC-0103).
 *
 * Moved from HomeScreen.
 */
@Composable
private fun ProvidersPane(onProviderSelected: (providerId: String) -> Unit) {
    val state = remember {
        ProvidersPaneState(
            providers = listOf(
                RemoteProvider("openai", "OpenAI", ProviderConfigStatus.ENABLED, System.currentTimeMillis() - 3_600_000),
                RemoteProvider("anthropic", "Anthropic (Claude)", ProviderConfigStatus.NOT_CONFIGURED),
                RemoteProvider("together", "Together AI", ProviderConfigStatus.CONFIGURED_DISABLED),
            )
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Remote Providers", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        }
        items(state.providers) { provider ->
            ProviderStatusRow(provider, onTap = { onProviderSelected(provider.id) })
        }
    }
}
