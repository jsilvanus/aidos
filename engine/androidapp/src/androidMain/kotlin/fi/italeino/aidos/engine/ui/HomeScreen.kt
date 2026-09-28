package fi.italeino.aidos.engine.ui

import android.os.Build
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    StatusPane(viewModel)
}

/**
 * Status pane: resident models, memory budget, connected apps, and Engine Control.
 */
@Composable
private fun StatusPane(viewModel: StatusViewModel) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val isEngineRunning by viewModel.isEngineRunning.collectAsState()
    val residentModels by viewModel.residentModels.collectAsState()
    val memory by viewModel.memory.collectAsState()
    val approvedApps by viewModel.approvedApps.collectAsState()
    val pendingAppCount by viewModel.pendingAppCount.collectAsState()

    // Refresh while the screen is visible: RAM and resident models change without UI events.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refresh()
            delay(2_000.milliseconds)
        }
    }

    val coroutineScope = rememberCoroutineScope()

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
            // Card-level press to toggle Engine; requires 1s hold to change state.
            var cardPressing by remember { mutableStateOf(false) }
            var cardProgress by remember { mutableFloatStateOf(0f) }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            role = Role.Button
                            contentDescription = "Engine control"
                            stateDescription = if (isEngineRunning) "Engine on. Hold for 1 second to stop." else "Engine off. Hold for 1 second to start."
                        }
                        .pointerInput(isEngineRunning) {
                            detectTapGestures(onPress = {
                                // Long-press to toggle either start or stop
                                cardPressing = true
                                cardProgress = 0f
                                val startTime = System.currentTimeMillis()
                                val job = coroutineScope.launch {
                                    while (cardPressing && cardProgress < 1f) {
                                        val elapsed = System.currentTimeMillis() - startTime
                                        cardProgress = (elapsed / 1000f).coerceIn(0f, 1f)
                                        delay(50.milliseconds)
                                    }
                                    if (cardProgress >= 1f) {
                                        if (isEngineRunning) {
                                            context.stopService(Intent(context, EngineService::class.java))
                                        } else {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                context.startForegroundService(Intent(context, EngineService::class.java))
                                            } else {
                                                context.startService(Intent(context, EngineService::class.java))
                                            }
                                        }
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        delay(400.milliseconds)
                                        viewModel.refresh()
                                    }
                                }
                                try {
                                    awaitRelease()
                                } finally {
                                    if (cardProgress in 0.01f..0.99f) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    cardPressing = false
                                    cardProgress = 0f
                                    job.cancel()
                                }
                            })
                        }
                        ,
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
                                color = if (isEngineRunning) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant
                            )

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

                        // Small changing info about engine state (plain state name)
                        val engineStateName = try { EngineService.state.value.name } catch (_: Exception) { "OFF" }
                        Text(engineStateName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        Text(
                            if (isEngineRunning) "Hold 1 second to turn off" else "Hold 1 second to turn on",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                if (cardPressing) {
                    CircularProgressIndicator(
                        progress = { cardProgress },
                        modifier = Modifier
                            .matchParentSize()
                            .padding(8.dp),
                        color = Color(0xFFEF4444),
                        strokeWidth = 6.dp
                    )
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
            // Device storage summary
            val statFs = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val availableBytes = statFs.availableBytes
            val availableGB = (availableBytes / (1024L * 1024L * 1024L)).toInt()
            Text("Device Space: ${availableGB} GB free", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            MemoryBudgetIndicator(memory, modifier = Modifier.padding(vertical = 4.dp))
        }

        item {
            Column {
                Text(
                    "Approved Apps",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    when {
                        !isEngineRunning -> "Start the Engine to see connected apps"
                        approvedApps.isEmpty() -> "No apps approved yet"
                        else -> approvedApps.joinToString(", ")
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (pendingAppCount > 0) {
                    Text(
                        "$pendingAppCount waiting for approval — see Apps",
                        fontSize = 12.sp,
                        color = Color(0xFFEAB308),
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )
                }
            }
        }
    }
}
