package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Provider Detail screen (RFC-0103, Phase D).
 *
 * Remote model provider configuration:
 * - API key field (masked) with validity status
 * - Enable/disable toggle
 * - Configured models list with per-model enable/disable toggles
 * - Add new model action
 *
 * Reachable from Home's Providers pane.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailScreen(providerId: String) {
    var state by remember {
        mutableStateOf(
            ProviderDetailState(
                provider = ProviderDetail(
                    id = providerId,
                    name = "OpenAI",
                    status = ProviderConfigStatus.ENABLED,
                    apiKeyValid = true,
                    apiKeyLastCheckedMs = System.currentTimeMillis() - 3_600_000,
                    isEnabled = true,
                    configuredModels = listOf(
                        ConfiguredRemoteModel("gpt-4", "GPT-4", true),
                        ConfiguredRemoteModel("gpt-4-turbo", "GPT-4 Turbo", false),
                    )
                ),
                showApiKeyField = false,
                apiKeyInput = ""
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.provider?.name ?: "Provider") },
                navigationIcon = {
                    IconButton(onClick = { /* TODO: navigate back */ }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
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
            if (state.provider != null) {
                val provider = state.provider!!

                // API Key Section
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "API Key",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (provider.apiKeyValid) {
                                    Text(
                                        "✓ Valid • checked 1h ago",
                                        fontSize = 11.sp,
                                        color = Color(0xFF22C55E)
                                    )
                                } else {
                                    Text(
                                        "Not configured",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                            TextButton(
                                onClick = {
                                    state = state.copy(showApiKeyField = !state.showApiKeyField)
                                }
                            ) {
                                Text(
                                    if (provider.apiKeyValid) "Change" else "Add",
                                    fontSize = 11.sp
                                )
                            }
                        }

                        if (state.showApiKeyField) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                BasicTextField(
                                    value = state.apiKeyInput,
                                    onValueChange = { state = state.copy(apiKeyInput = it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    decorationBox = { innerTextField ->
                                        if (state.apiKeyInput.isEmpty()) {
                                            Text(
                                                "sk-...",
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { /* TODO: Save API key */ },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Text("Save", color = Color.White)
                                }
                                OutlinedButton(
                                    onClick = {
                                        state = state.copy(
                                            showApiKeyField = false,
                                            apiKeyInput = ""
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }

                // Enable/Disable Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Provider Status",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (provider.isEnabled) "Enabled" else "Disabled",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(
                        checked = provider.isEnabled,
                        onCheckedChange = { /* TODO: Toggle provider */ }
                    )
                }

                // Configured Models Section
                Text(
                    "Configured Models",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )

                if (provider.configuredModels.isEmpty()) {
                    Text(
                        "No models configured yet",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(provider.configuredModels) { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        model.displayName,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        model.modelId,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.outline,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Switch(
                                    checked = model.isEnabled,
                                    onCheckedChange = { /* TODO: Toggle model */ }
                                )
                            }
                        }
                    }
                }

                // Add Model Button
                TextButton(
                    onClick = { /* TODO: Show add model dialog */ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+ Add Model")
                }
            }
        }
    }
}

import androidx.compose.ui.unit.height
