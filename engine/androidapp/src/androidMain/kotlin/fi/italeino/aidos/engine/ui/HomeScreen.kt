package fi.italeino.aidos.engine.ui

import android.os.Build
import android.content.Intent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.unit.dp
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
    AndroidStatusPane(viewModel)
}

/**
 * Status pane: resident models, memory budget, connected apps, and Engine Control. The layout is
 * the shared [StatusPane]; this adds the Android hold-to-toggle gesture and Android data sources.
 */
@Composable
private fun AndroidStatusPane(viewModel: StatusViewModel) {
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

    // Device storage summary
    val statFs = android.os.StatFs(android.os.Environment.getDataDirectory().path)
    val availableGB = (statFs.availableBytes / (1024L * 1024L * 1024L)).toInt()
    // Small changing info about engine state (plain state name)
    val engineStateName = try { EngineService.state.value.name } catch (_: Exception) { "OFF" }

    StatusPane(
        state = StatusUiState(
            isEngineRunning = isEngineRunning,
            engineStateName = engineStateName,
            residentModels = residentModels,
            memory = memory,
            freeStorageGb = availableGB,
            approvedApps = approvedApps,
            pendingAppCount = pendingAppCount,
        ),
        engineControl = {
            // Card-level press to toggle Engine; requires 1s hold to change state.
            var cardPressing by remember { mutableStateOf(false) }
            var cardProgress by remember { mutableFloatStateOf(0f) }

            Box(modifier = Modifier.fillMaxWidth()) {
                EngineControlCard(
                    isEngineRunning = isEngineRunning,
                    engineStateName = engineStateName,
                    hint = if (isEngineRunning) "Hold 1 second to turn off" else "Hold 1 second to turn on",
                    modifier = Modifier
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
                        },
                )

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
        },
    )
}
