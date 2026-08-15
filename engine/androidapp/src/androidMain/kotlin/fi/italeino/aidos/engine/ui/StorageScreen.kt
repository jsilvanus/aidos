package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Storage screen (RFC-0103, Phase D, RFC-0022).
 *
 * The RFC-0022 accounting table, unmodified — total used/free, per-model size
 * and last-used, and the "never run · will not fit" row that names pure waste.
 *
 * Removal is manual only (RFC-0022) — Engine never deletes weights on its own to make room, so
 * this screen's row-tap action is the only path to freeing space.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen() {
    val state = remember {
        StorageState(
            totalDeviceMB = 128_000,
            freeDeviceMB = 32_000,
            installedModels = listOf(
                InstalledModel(
                    "qwen-3b",
                    "Qwen2.5 3B Q4_K_M",
                    sizeMB = 2_100,
                    lastUsedMs = System.currentTimeMillis() - 600_000,
                    isWastedSpace = false
                ),
                InstalledModel(
                    "llama-7b",
                    "Llama 2 7B Chat Q4_K_M",
                    sizeMB = 4_081,
                    lastUsedMs = System.currentTimeMillis() - 3_600_000,
                    isWastedSpace = false
                ),
                InstalledModel(
                    "nomic-embed",
                    "Nomic Embed Text v1.5",
                    sizeMB = 77,
                    lastUsedMs = System.currentTimeMillis() - 86_400_000 * 30,
                    isWastedSpace = false
                ),
                InstalledModel(
                    "old-model",
                    "Old Model (Won't Fit)",
                    sizeMB = 8_000,
                    lastUsedMs = null,
                    isWastedSpace = true
                ),
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Storage") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Overall Storage Summary
            val usedMB = state.usedDeviceMB
            val usedPercent = if (state.totalDeviceMB > 0) {
                usedMB / state.totalDeviceMB.toFloat()
            } else 0f

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Device Storage",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${usedMB / 1024} GB / ${state.totalDeviceMB / 1024} GB",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    LinearProgressIndicator(
                        progress = { usedPercent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(4.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${(usedPercent * 100).roundToInt()}% used",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            "${state.freeDeviceMB / 1024} GB free",
                            fontSize = 11.sp,
                            color = Color(0xFF22C55E)
                        )
                    }
                }
            }

            Text(
                "Installed Models",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.installedModels) { model ->
                    StorageModelCard(model)
                }
            }
        }
    }
}

@Composable
private fun StorageModelCard(model: InstalledModel) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (model.isWastedSpace) Color(0xFFEF4444).copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (model.isWastedSpace) Color(0xFFEF4444).copy(alpha = 0.1f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (model.isWastedSpace) Color(0xFFEF4444)
                        else MaterialTheme.colorScheme.primary
                    )
                    val statusText = if (model.isWastedSpace) {
                        "never run · will not fit"
                    } else if (model.lastUsedMs != null) {
                        val minutesAgo = (System.currentTimeMillis() - model.lastUsedMs) / 60_000
                        when {
                            minutesAgo < 60 -> "used ${minutesAgo}m ago"
                            minutesAgo < 1440 -> "used ${minutesAgo / 60}h ago"
                            else -> "used ${minutesAgo / 1440}d ago"
                        }
                    } else {
                        "never used"
                    }
                    Text(
                        statusText,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${model.sizeMB} MB",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )
                    IconButton(
                        onClick = { /* TODO: Remove model */ },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.height(18.dp)
                        )
                    }
                }
            }
        }
    }
}

