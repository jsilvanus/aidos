package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Model Configuration screen (RFC-0103, Phase D).
 *
 * Simple version showing default parameters like Temperature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigScreen(
    modelId: String,
    onBackClick: () -> Unit
) {
    var temperature by remember { mutableStateOf(0.7f) }
    var topP by remember { mutableStateOf(0.9f) }
    var maxTokens by remember { mutableStateOf(2048f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configure $modelId") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                "Inference Parameters",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Temperature", fontSize = 13.sp)
                    Text("%.2f".format(temperature), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Top P", fontSize = 13.sp)
                    Text("%.2f".format(topP), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = topP,
                    onValueChange = { topP = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Max Tokens", fontSize = 13.sp)
                    Text("${maxTokens.toInt()}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = maxTokens,
                    onValueChange = { maxTokens = it },
                    valueRange = 128f..8192f,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
