package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reusable UI components for Aidos Engine (RFC-0103, Phase D).
 *
 * Material 3 components styled with the warm technical theme:
 * - 8px rounded corners throughout
 * - High-density technical information display
 * - Clear semantic color coding for status and fit verdicts
 */

// ============================================================================
// Theme Extensions
// ============================================================================

@Composable
fun statusColorForVerdict(verdict: ModelFitVerdict): Color = when (verdict) {
    ModelFitVerdict.RUNS_WELL -> Color(0xFF22C55E)      // Green
    ModelFitVerdict.RUNS_TIGHT -> Color(0xFFEAB308)    // Amber
    ModelFitVerdict.EXCEEDS_CONTEXT -> Color(0xFFF97316) // Orange
    ModelFitVerdict.WILL_NOT_FIT -> Color(0xFFEF4444)   // Red
}

// ============================================================================
// Status Chip
// ============================================================================

@Composable
fun FitVerdictChip(verdict: ModelFitVerdict) {
    val backgroundColor = statusColorForVerdict(verdict)
    val label = when (verdict) {
        ModelFitVerdict.RUNS_WELL -> "Perfect"
        ModelFitVerdict.RUNS_TIGHT -> "Tight"
        ModelFitVerdict.EXCEEDS_CONTEXT -> "Exceeds CTX"
        ModelFitVerdict.WILL_NOT_FIT -> "Won't Fit"
    }
    
    Box(
        modifier = Modifier
            .background(backgroundColor.copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp))
            .border(1.dp, backgroundColor, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = backgroundColor,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ============================================================================
// Memory Indicator
// ============================================================================

@Composable
fun MemoryBudgetIndicator(
    budget: MemoryBudget,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Memory: ${budget.usedMB} MB / ${budget.totalMB} MB",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${(budget.percentUsed * 100).toInt()}%",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
        LinearProgressIndicator(
            progress = budget.percentUsed,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surface
        )
    }
}

// ============================================================================
// Resident Model Card
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResidentModelCard(
    model: ResidentModel,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = model.quantization,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            if (model.connectedApp != null) {
                Text(
                    text = "Loaded 2m ago • ${model.connectedApp}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

// ============================================================================
// Cookbook Model Card
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookbookModelCard(
    model: CookbookModel,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(8.dp),
        onClick = onTap
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${model.quantization} • ${model.kind} • ${model.sizeMB} MB",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline,
                        fontFamily = FontFamily.Monospace
                    )
                }
                FitVerdictChip(model.fitVerdict)
            }
            
            if (model.tokensPerSecond != null || model.estimatedVramMB != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (model.tokensPerSecond != null) {
                        Text(
                            text = "~${model.tokensPerSecond.toInt()} t/s",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.outline,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (model.estimatedVramMB != null) {
                        Text(
                            text = "${model.estimatedVramMB} MB VRAM",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.outline,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// Provider Status Row
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderStatusRow(
    provider: RemoteProvider,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(8.dp),
        onClick = onTap
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
                    text = provider.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                val statusLabel = when (provider.status) {
                    ProviderConfigStatus.NOT_CONFIGURED -> "not configured"
                    ProviderConfigStatus.CONFIGURED_DISABLED -> "configured · disabled"
                    ProviderConfigStatus.ENABLED -> "enabled"
                }
                Text(
                    text = statusLabel,
                    fontSize = 11.sp,
                    color = when (provider.status) {
                        ProviderConfigStatus.ENABLED -> Color(0xFF22C55E)
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
            }
        }
    }
}

// ============================================================================
// Search Bar
// ============================================================================

@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search models...",
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.CenterStart),
            singleLine = true,
            decorationBox = { innerTextField ->
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                innerTextField()
            }
        )
    }
}

// ============================================================================
// Context Fit Table
// ============================================================================

@Composable
fun ContextFitTable(
    rows: List<ContextFitRow>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "Fit by Context Length",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        LazyColumn {
            items(rows) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${row.contextLength}K",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(48.dp)
                    )
                    FitVerdictChip(row.verdict)
                    Text(
                        text = "~${row.estimatedMemoryMB} MB",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ============================================================================
// License Acceptance Card
// ============================================================================

@Composable
fun LicenseAcceptanceCard(
    licenseName: String,
    licenseText: String,
    accepted: Boolean,
    onAcceptedChange: (Boolean) -> Unit,
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
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "License: $licenseName",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(6.dp)
                    )
                    .padding(8.dp)
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = licenseText.take(500) + if (licenseText.length > 500) "..." else "",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val checkboxSize = 20.dp
                Box(
                    modifier = Modifier
                        .size(checkboxSize)
                        .background(
                            if (accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                        .clickable { onAcceptedChange(!accepted) },
                    contentAlignment = Alignment.Center
                ) {
                    if (accepted) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = "I accept this license",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

// ============================================================================
// Model Loading Progress Card (Phase E)
// ============================================================================

@Composable
fun ModelLoadingProgressCard(
    loadingState: ModelLoadingState,
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
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Loading to Memory",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${loadingState.loadProgress}%",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            
            LinearProgressIndicator(
                progress = loadingState.loadProgress / 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.tertiary,
            )
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Memory: ${loadingState.estimatedMemoryMB} MB",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                if (loadingState.loadTimeMs != null) {
                    Text(
                        text = "${loadingState.loadTimeMs}ms",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

// ============================================================================
// Download Progress Card
// ============================================================================

@Composable
fun DownloadProgressCard(
    download: DownloadProgress,
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
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = download.modelName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${download.progressPercent}%",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            
            LinearProgressIndicator(
                progress = download.progressPercent / 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.secondary
            )
            
            if (download.speedMBps != null || download.etaSeconds != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (download.speedMBps != null) {
                        Text(
                            text = "%.1f MB/s".format(download.speedMBps),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (download.etaSeconds != null) {
                        Text(
                            text = "ETA ${download.etaSeconds / 60}m",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// Utility Extensions
// ============================================================================

// None currently.
