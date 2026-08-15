package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FAB
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Home screen showing status, cookbook, and providers (RFC-0103, Phase D).
 *
 * Three panes with horizontal swipe between them:
 * 0. Status: what's running now (resident models, memory budget, connected apps, downloads)
 * 1. Cookbook: browsable catalog of local models with search/filters and fit scoring
 * 2. Providers: remote model providers (not a catalog, just configuration)
 *
 * Same pager pattern as Aidos Agent's HomeScreen — inherited, not reinvented.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onModelSelected: (modelId: String) -> Unit,
    onProviderSelected: (providerId: String) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(0)
                        }
                    },
                    text = { Text("Status") }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(1)
                        }
                    },
                    text = { Text("Cookbook") }
                )
                Tab(
                    selected = pagerState.currentPage == 2,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(2)
                        }
                    },
                    text = { Text("Providers") }
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            ) { page ->
                when (page) {
                    0 -> StatusPane()
                    1 -> CookbookPane(onModelSelected)
                    2 -> ProvidersPane(onProviderSelected)
                    else -> Box(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * Status pane: resident models, memory budget, connected apps, in-flight downloads (RFC-0103).
 *
 * What Engine is doing right now — the question someone opening Engine actually has, the same
 * way Aidos Agent's inbox answers "what needs me?".
 */
@Composable
private fun StatusPane() {
    // Sample data (will be bound to ViewModel later)
    val state = remember {
        StatusPaneState(
            residentModels = listOf(
                ResidentModel("qwen-3b", "Qwen2.5 3B Q4", "Q4_K_M", 120_000, "Aidos Agent"),
            ),
            memoryBudget = MemoryBudget(2_400, 4_096),
            connectedApps = listOf(
                ConnectedAppStatus("Aidos Agent", "fi.italeino.aidos"),
            ),
            inProgressDownload = DownloadProgress(
                "llama-7b",
                "Llama 2 7B Chat Q4",
                progressPercent = 35,
                speedMBps = 12.5f,
                etaSeconds = 600
            )
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Resident Now",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        items(state.residentModels.size) { index ->
            ResidentModelCard(state.residentModels[index])
        }

        item {
            MemoryBudgetIndicator(state.memoryBudget, modifier = Modifier.padding(vertical = 4.dp))
        }

        item {
            Column {
                Text(
                    "Connected Apps",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    state.connectedApps.joinToString(", ") { it.appName },
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (state.inProgressDownload != null) {
            item {
                Text(
                    "In Progress",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            item {
                DownloadProgressCard(state.inProgressDownload)
            }
        }
    }
}

/**
 * Cookbook pane: local models, searchable with filters and fit scoring (RFC-0103).
 *
 * High-density technical view: each model shows quantization, size, fit verdict, and
 * performance estimates (tokens/s, VRAM). Search queries hit Hugging Face; results are
 * filtered locally based on hardware fit.
 */
@Composable
private fun CookbookPane(onModelSelected: (modelId: String) -> Unit) {
    var state by remember {
        mutableStateOf(
            CookbookPaneState(
                models = listOf(
                    CookbookModel(
                        "qwen-3b",
                        "Qwen2.5 3B Instruct Q4_K_M",
                        "LLM",
                        "Q4_K_M",
                        2_100,
                        32768,
                        ModelFitVerdict.RUNS_WELL,
                        tokensPerSecond = 45.2f,
                        estimatedVramMB = 2_400
                    ),
                    CookbookModel(
                        "llama-7b",
                        "Llama 2 7B Chat Q4_K_M",
                        "LLM",
                        "Q4_K_M",
                        4_081,
                        4096,
                        ModelFitVerdict.RUNS_TIGHT,
                        tokensPerSecond = 22.1f,
                        estimatedVramMB = 3_800
                    ),
                    CookbookModel(
                        "nomic-embed",
                        "Nomic Embed Text v1.5",
                        "Embedding",
                        "Q4_0",
                        77,
                        2048,
                        ModelFitVerdict.RUNS_WELL,
                        estimatedVramMB = 300
                    ),
                )
            )
        )
    }

    var selectedFilter by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
    ) {
        SearchBar(
            query = state.searchQuery,
            onQueryChange = { state = state.copy(searchQuery = it) },
            placeholder = "Search HuggingFace...",
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AssistChip(
                label = { Text("All", fontSize = 10.sp) },
                onClick = { selectedFilter = null },
                selected = selectedFilter == null
            )
            AssistChip(
                label = { Text("Perfect", fontSize = 10.sp) },
                onClick = { selectedFilter = "RUNS_WELL" },
                selected = selectedFilter == "RUNS_WELL"
            )
            AssistChip(
                label = { Text("Tight", fontSize = 10.sp) },
                onClick = { selectedFilter = "RUNS_TIGHT" },
                selected = selectedFilter == "RUNS_TIGHT"
            )
            AssistChip(
                label = { Text("LLM", fontSize = 10.sp) },
                onClick = { selectedFilter = "LLM" },
                selected = selectedFilter == "LLM"
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.models.size) { index ->
                CookbookModelCard(
                    state.models[index],
                    onTap = { onModelSelected(state.models[index].id) }
                )
            }

            item {
                TextButton(
                    onClick = { /* TODO: Show HF search dialog */ },
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
 *
 * NOT a catalog — just configuration. Each provider shows current status:
 * - not configured (no API key)
 * - configured but disabled
 * - enabled
 */
@Composable
private fun ProvidersPane(onProviderSelected: (providerId: String) -> Unit) {
    val state = remember {
        ProvidersPaneState(
            providers = listOf(
                RemoteProvider(
                    "openai",
                    "OpenAI",
                    ProviderConfigStatus.ENABLED,
                    lastCheckedMs = System.currentTimeMillis() - 3_600_000
                ),
                RemoteProvider(
                    "anthropic",
                    "Anthropic (Claude)",
                    ProviderConfigStatus.NOT_CONFIGURED
                ),
                RemoteProvider(
                    "together",
                    "Together AI",
                    ProviderConfigStatus.CONFIGURED_DISABLED
                ),
            )
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
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

        items(state.providers.size) { index ->
            ProviderStatusRow(
                state.providers[index],
                onTap = { onProviderSelected(state.providers[index].id) }
            )
        }
    }
}

/**
 * Cookbook pane: the RFC-0022 cookbook — label, kind, size, verdict against this device.
 */
@Composable
private fun CookbookPane(onModelSelected: (modelId: String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Cookbook — what could run here")
        Text("(Catalogue entries with cookbook verdicts will appear here, per RFC-0022)")
        // TODO(RFC-0103/RFC-0022): bind to the cookbook's computed verdicts; tapping an entry
        // calls onModelSelected(modelId) to navigate to EngineRoute.ModelDetail.
    }
}
