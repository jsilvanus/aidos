package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Everything the Status pane shows; each host fills it from its own runtime and device. */
data class StatusUiState(
    val isEngineRunning: Boolean = false,
    /** Plain lifecycle state name shown under the ON/OFF label, e.g. "READY". */
    val engineStateName: String = "OFF",
    val residentModels: List<ResidentModel> = emptyList(),
    val memory: MemoryBudget = MemoryBudget(0, 0),
    /** Free space on the volume holding the models, or null when unknown. */
    val freeStorageGb: Int? = null,
    /** Display names of apps the user has approved to use the Engine. */
    val approvedApps: List<String> = emptyList(),
    val pendingAppCount: Int = 0,
)

/**
 * Status pane (RFC-0103, Phase D): Engine control, resident models, memory budget and approved
 * apps. [engineControl] is host-specific (Android: hold-to-toggle card; desktop: click), usually
 * an [EngineControlCard] with the host's gesture attached.
 */
@Composable
fun StatusPane(
    state: StatusUiState,
    engineControl: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
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

        item { engineControl() }

        item {
            Text(
                "Resident Now",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (state.residentModels.isEmpty()) {
            item {
                Text(
                    "No models resident",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(state.residentModels.size) { index ->
                ResidentModelCard(state.residentModels[index])
            }
        }

        item {
            state.freeStorageGb?.let { freeGb ->
                Text("Device Space: $freeGb GB free", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            }
            MemoryBudgetIndicator(state.memory, modifier = Modifier.padding(vertical = 4.dp))
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
                        !state.isEngineRunning -> "Start the Engine to see connected apps"
                        state.approvedApps.isEmpty() -> "No apps approved yet"
                        else -> state.approvedApps.joinToString(", ")
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (state.pendingAppCount > 0) {
                    Text(
                        "${state.pendingAppCount} waiting for approval — see Apps",
                        fontSize = 12.sp,
                        color = Color(0xFFEAB308),
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )
                }
            }
        }
    }
}

/**
 * The Engine ON/OFF card. Hosts attach their start/stop gesture through [modifier] and describe
 * it in [hint] ("Hold 1 second to turn on", "Click to start", ...).
 */
@Composable
fun EngineControlCard(
    isEngineRunning: Boolean,
    engineStateName: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
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
                    shape = CircleShape,
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

            Text(engineStateName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Text(
                hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
