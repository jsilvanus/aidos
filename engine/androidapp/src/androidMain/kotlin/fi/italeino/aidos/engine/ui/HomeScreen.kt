package fi.italeino.aidos.engine.ui

import android.content.Intent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import fi.italeino.aidos.engine.EngineService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Home screen showing status and Engine control (RFC-0103, Phase D).
 *
 * Simplified to a single Status pane with Engine On/Off toggle.
 */
@Composable
fun HomeScreen(viewModel: StatusViewModel = viewModel()) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            StatusPane(viewModel)
        }
    }
}

/**
 * Status pane: resident models, memory budget, connected apps, and Engine Control.
 */
@Composable
private fun StatusPane(viewModel: StatusViewModel) {
    val context = LocalContext.current
    val isEngineRunning by viewModel.isEngineRunning.collectAsState()
    val residentModels by viewModel.residentModels.collectAsState()
    
    // Refresh state when entering screen
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }
    
    // Engine Control long press logic
    var isPressing by remember { mutableStateOf(false) }
    var pressProgress by remember { mutableFloatStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()

    // Sample data (memory/apps still mocked for now)
    val state = remember {
        StatusPaneState(
            memoryBudget = MemoryBudget(usedMB = 2400, totalMB = 4096),
            connectedApps = listOf(
                ConnectedAppStatus(appName = "Aidos Agent", packageName = "fi.italeino.aidos"),
            )
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Engine Control",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isEngineRunning) "Engine is ON" else "Engine is OFF",
                            fontWeight = FontWeight.Bold,
                            color = if (isEngineRunning) {
                                Color(0xFF22C55E)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(64.dp)
                                .pointerInput(isEngineRunning) {
                                    detectTapGestures(
                                        onTap = {
                                            if (!isEngineRunning) {
                                                val intent = Intent(context, EngineService::class.java)
                                                context.startForegroundService(intent)
                                                
                                                // Small delay for service to start and update instance
                                                coroutineScope.launch {
                                                    delay(500.milliseconds)
                                                    viewModel.refresh()
                                                }
                                            }
                                        },
                                        onPress = {
                                            if (isEngineRunning) {
                                                isPressing = true
                                                pressProgress = 0f
                                                val startTime = System.currentTimeMillis()
                                                val job = coroutineScope.launch {
                                                    while (isPressing && (pressProgress < 1f)) {
                                                        val elapsed = System.currentTimeMillis() - startTime
                                                        pressProgress = (elapsed / 5000f).coerceIn(0f, 1f)
                                                        delay(50.milliseconds)
                                                    }
                                                    if (pressProgress >= 1f) {
                                                        context.stopService(Intent(context, EngineService::class.java))
                                                        isPressing = false
                                                        pressProgress = 0f
                                                        delay(500.milliseconds)
                                                        viewModel.refresh()
                                                    }
                                                }
                                                try {
                                                    awaitRelease()
                                                } finally {
                                                    isPressing = false
                                                    pressProgress = 0f
                                                    job.cancel()
                                                }
                                            }
                                        }
                                    )
                                }
                        ) {
                            if (isPressing) {
                                CircularProgressIndicator(
                                    progress = pressProgress,
                                    modifier = Modifier.fillMaxSize(),
                                    color = Color(0xFFEF4444),
                                    strokeWidth = 4.dp
                                )
                            }
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = if (isEngineRunning) Color(0xFF22C55E) else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        if (isEngineRunning) "ON" else "OFF",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                    if (isPressing) {
                        Text(
                            "Hold 5s to turn off...",
                            fontSize = 11.sp,
                            color = Color(0xFFEF4444),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        item {
            Text(
                "Resident Now",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (residentModels.isEmpty()) {
            item {
                Text(
                    "No models resident",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(residentModels.size) { index ->
                ResidentModelCard(residentModels[index])
            }
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
