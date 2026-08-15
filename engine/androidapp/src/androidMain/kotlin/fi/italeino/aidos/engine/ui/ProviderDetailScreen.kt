package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailScreen(
    providerId: String,
    onBackClick: () -> Unit
) {
    // Sample data
    var state by remember {
        mutableStateOf(
            ProviderDetailState(
                provider = ProviderDetail(
                    id = providerId,
                    name = providerId.replaceFirstChar { it.uppercase() },
                    status = ProviderConfigStatus.ENABLED,
                    apiKeyValid = true,
                    isEnabled = true,
                    configuredModels = listOf(
                        ConfiguredRemoteModel("gpt-4o", "GPT-4o", true),
                        ConfiguredRemoteModel("gpt-3.5-turbo", "GPT-3.5 Turbo", true)
                    )
                )
            )
        )
    }

    val provider = state.provider ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(provider.name) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Configuration Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enabled", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Switch(
                            checked = provider.isEnabled,
                            onCheckedChange = { /* TODO */ }
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("API Key", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            if (provider.apiKeyValid) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Valid",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    "Configured",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 4.dp, end = 8.dp)
                                )
                            }
                            TextButton(onClick = { state = state.copy(showApiKeyField = !state.showApiKeyField) }) {
                                Text(if (provider.apiKeyValid) "Change" else "Add", fontSize = 11.sp)
                            }
                        }

                        if (state.showApiKeyField) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(6.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
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
                                            Text("Enter API Key", color = MaterialTheme.colorScheme.outline, fontSize = 14.sp)
                                        }
                                        innerTextField()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Models section
            Text("Available Models", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                provider.configuredModels.forEach { model ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.displayName, fontWeight = FontWeight.SemiBold)
                                Text("Remote model: ${model.modelId}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
                
                TextButton(
                    onClick = { /* TODO */ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+ Add Model")
                }
            }
        }
    }
}
