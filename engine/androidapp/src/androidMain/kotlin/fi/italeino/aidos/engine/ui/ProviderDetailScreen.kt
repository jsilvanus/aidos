package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ProviderDetailState(
    val provider: ProviderDetail?,
    val showApiKeyField: Boolean = false,
    val apiKeyInput: String = "",
    val showAddModelDialog: Boolean = false,
    val newModelId: String = "",
    val newModelName: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailScreen(
    providerId: String,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var state by remember(providerId) {
        mutableStateOf(
            ProviderDetailState(
                provider = SettingsStore.getProvider(context, providerId)
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enabled", fontWeight = FontWeight.Bold)
                            Text(
                                if (provider.apiKeyValid) {
                                    if (provider.isEnabled) "Provider active" else "Provider disabled"
                                } else "Requires API key",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = provider.isEnabled,
                            enabled = provider.apiKeyValid,
                            onCheckedChange = { enabled ->
                                val updatedProvider = provider.copy(
                                    isEnabled = enabled,
                                    status = if (enabled) ProviderConfigStatus.ENABLED else ProviderConfigStatus.CONFIGURED_DISABLED
                                )
                                SettingsStore.setProvider(context, providerId, updatedProvider)
                                state = state.copy(provider = updatedProvider)
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

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
                            if (provider.apiKeyValid) {
                                TextButton(onClick = {
                                    val updatedProvider = provider.copy(
                                        apiKey = null,
                                        apiKeyValid = false,
                                        isEnabled = false,
                                        status = ProviderConfigStatus.NOT_CONFIGURED,
                                    )
                                    SettingsStore.setProvider(context, providerId, updatedProvider)
                                    state = state.copy(provider = updatedProvider, showApiKeyField = false, apiKeyInput = "")
                                }) {
                                    Text("Clear", fontSize = 11.sp)
                                }
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

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val sanitizedKey = state.apiKeyInput.trim()
                                        if (sanitizedKey.isEmpty()) return@Button
                                        val updatedProvider = provider.copy(
                                            apiKey = sanitizedKey,
                                            apiKeyValid = true,
                                            isEnabled = true,
                                            status = ProviderConfigStatus.ENABLED,
                                        )
                                        SettingsStore.setProvider(context, providerId, updatedProvider)
                                        state = state.copy(
                                            provider = updatedProvider,
                                            showApiKeyField = false,
                                            apiKeyInput = "",
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Save")
                                }
                                OutlinedButton(
                                    onClick = {
                                        state = state.copy(showApiKeyField = false, apiKeyInput = "")
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Cancel")
                                }
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
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.displayName, fontWeight = FontWeight.SemiBold)
                                Text("Remote model: ${model.modelId}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                            }
                            Switch(
                                checked = model.isEnabled,
                                onCheckedChange = { isChecked ->
                                    val updatedModels = provider.configuredModels.map { m ->
                                        if (m.modelId == model.modelId) m.copy(isEnabled = isChecked) else m
                                    }
                                    val updatedProvider = provider.copy(configuredModels = updatedModels)
                                    SettingsStore.setProvider(context, providerId, updatedProvider)
                                    state = state.copy(provider = updatedProvider)
                                }
                            )
                            IconButton(onClick = {
                                val updatedModels = provider.configuredModels.filterNot { it.modelId == model.modelId }
                                val updatedProvider = provider.copy(configuredModels = updatedModels)
                                SettingsStore.setProvider(context, providerId, updatedProvider)
                                state = state.copy(provider = updatedProvider)
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Delete model", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                
                TextButton(
                    onClick = {
                        state = state.copy(
                            showAddModelDialog = true,
                            newModelId = "",
                            newModelName = "",
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+ Add Model")
                }
            }
        }

        if (state.showAddModelDialog) {
            AlertDialog(
                onDismissRequest = { state = state.copy(showAddModelDialog = false) },
                title = { Text("Add Remote Model") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.newModelId,
                            onValueChange = { state = state.copy(newModelId = it) },
                            label = { Text("Model ID (e.g. gpt-4o)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.newModelName,
                            onValueChange = { state = state.copy(newModelName = it) },
                            label = { Text("Display Name (e.g. GPT-4o)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val id = state.newModelId.trim()
                            if (id.isNotEmpty()) {
                                val name = state.newModelName.trim().ifEmpty { id }
                                val newModel = ConfiguredRemoteModel(modelId = id, displayName = name, isEnabled = true)
                                val updatedProvider = provider.copy(
                                    configuredModels = provider.configuredModels.filterNot { it.modelId == id } + newModel
                                )
                                SettingsStore.setProvider(context, providerId, updatedProvider)
                                state = state.copy(
                                    provider = updatedProvider,
                                    showAddModelDialog = false,
                                    newModelId = "",
                                    newModelName = "",
                                )
                            }
                        },
                        enabled = state.newModelId.isNotBlank()
                    ) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { state = state.copy(showAddModelDialog = false) }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
