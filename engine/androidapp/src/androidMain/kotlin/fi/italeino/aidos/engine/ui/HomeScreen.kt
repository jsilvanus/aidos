package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import fi.italeino.aidos.engine.ModelStateManager
import kotlinx.coroutines.launch

/**
 * Home screen showing status (what Engine is doing now) and the cookbook (what could run),
 * RFC-0103.
 *
 * Two panes with horizontal swipe between them, status first — mirroring Aidos Agent's
 * Inbox-before-Projects ordering: "what's happening" beats "what's possible". Same pager pattern
 * as fi.italeino.aidos.ui.HomeScreen — inherited, not reinvented.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onModelSelected: (modelId: String) -> Unit,
    onTestChat: () -> Unit = {},
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.padding(vertical = 8.dp),
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
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            ) { page ->
                when (page) {
                    0 -> StatusPane(onTestChat)
                    1 -> CookbookPane(onModelSelected)
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
private fun StatusPane(onTestChat: () -> Unit) {
    val modelStateManager = remember { ModelStateManager.getInstance() }
    val loadedModels by modelStateManager.loadedModels.collectAsState()
    val isLoading by modelStateManager.loadingModel.collectAsState()
    val error by modelStateManager.loadError.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Status — what's running now", style = MaterialTheme.typography.headlineSmall)
        
        // Loaded models section
        Text("Loaded Models", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        
        if (loadedModels.isEmpty()) {
            Text(
                "No models loaded into memory",
                modifier = Modifier.padding(8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            loadedModels.forEach { modelId ->
                Card(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(modelId, style = MaterialTheme.typography.bodyMedium)
                            Text("Status: Loaded", style = MaterialTheme.typography.labelSmall)
                        }
                        Button(
                            onClick = { /* TODO: unload model */ },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("Unload")
                        }
                    }
                }
            }
        }
        
        // Test chat button
        if (loadedModels.isNotEmpty()) {
            Button(
                onClick = onTestChat,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                Text("Test Chat with Loaded Model")
            }
        }
        
        // Loading indicator
        if (isLoading != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text("Loading model: $isLoading")
            }
        }
        
        // Error message
        if (error != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .background(MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        
        Text("(Memory budget and connected apps will appear here)", modifier = Modifier.padding(top = 16.dp))
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
