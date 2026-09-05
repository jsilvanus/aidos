package fi.italeino.aidos.engine.ui

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    var state by remember(providerId) {
        mutableStateOf(
            ProviderDetailState(
                provider = loadProviderDetail(context, providerId)
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
                            onCheckedChange = { enabled ->
                                val updatedProvider = provider.copy(
                                    isEnabled = enabled,
                                    status = if (enabled) ProviderConfigStatus.ENABLED else ProviderConfigStatus.CONFIGURED_DISABLED
                                )
                                persistProviderDetail(context, providerId, updatedProvider)
                                state = state.copy(provider = updatedProvider)
                            }
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

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val sanitizedKey = state.apiKeyInput.trim()
                                        if (sanitizedKey.isEmpty()) return@Button
                                        val updatedProvider = provider.copy(
                                            apiKeyValid = true,
                                            isEnabled = true,
                                            status = ProviderConfigStatus.ENABLED,
                                        )
                                        persistProviderDetail(context, providerId, updatedProvider)
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
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.displayName, fontWeight = FontWeight.SemiBold)
                                Text("Remote model: ${model.modelId}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
                
                TextButton(
                    onClick = {
                        val nextIndex = provider.configuredModels.size + 1
                        val nextModel = ConfiguredRemoteModel(
                            modelId = "custom-model-$nextIndex",
                            displayName = "Custom Model $nextIndex",
                            isEnabled = true,
                        )
                        val updatedProvider = provider.copy(
                            configuredModels = provider.configuredModels + nextModel,
                        )
                        persistProviderDetail(context, providerId, updatedProvider)
                        state = state.copy(provider = updatedProvider)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+ Add Model")
                }
            }
        }
    }
}

private fun loadProviderDetail(context: Context, providerId: String): ProviderDetail {
    val prefs = context.getSharedPreferences("aidos_engine_provider_state", Context.MODE_PRIVATE)
    val base = ProviderDetail(
        id = providerId,
        name = providerId.replaceFirstChar { it.uppercase() },
        status = ProviderConfigStatus.ENABLED,
        apiKeyValid = true,
        isEnabled = true,
        configuredModels = listOf(
            ConfiguredRemoteModel("gpt-4o", "GPT-4o", true),
            ConfiguredRemoteModel("gpt-3.5-turbo", "GPT-3.5 Turbo", true),
        ),
    )
    val enabled = prefs.getBoolean("${providerId}:enabled", base.isEnabled)
    val apiKeyValid = prefs.getBoolean("${providerId}:api_key_valid", base.apiKeyValid)
    val storedModels = prefs.getString("${providerId}:models", null)
        ?.takeIf { it.isNotBlank() }
        ?.split(";")
        ?.mapNotNull { row ->
            val parts = row.split("|")
            if (parts.size >= 3) {
                ConfiguredRemoteModel(
                    modelId = parts[0],
                    displayName = parts[1],
                    isEnabled = parts[2].toBooleanStrictOrNull() == true,
                )
            } else null
        }
        ?: base.configuredModels

    return base.copy(
        isEnabled = enabled,
        apiKeyValid = apiKeyValid,
        status = when {
            enabled -> ProviderConfigStatus.ENABLED
            apiKeyValid -> ProviderConfigStatus.CONFIGURED_DISABLED
            else -> ProviderConfigStatus.NOT_CONFIGURED
        },
        configuredModels = storedModels,
    )
}

private fun persistProviderDetail(context: Context, providerId: String, provider: ProviderDetail) {
    val prefs = context.getSharedPreferences("aidos_engine_provider_state", Context.MODE_PRIVATE)
    prefs.edit()
        .putBoolean("${providerId}:enabled", provider.isEnabled)
        .putBoolean("${providerId}:api_key_valid", provider.apiKeyValid)
        .putString(
            "${providerId}:models",
            provider.configuredModels.joinToString(";") { model ->
                listOf(model.modelId, model.displayName, model.isEnabled.toString()).joinToString("|")
            },
        )
        .apply()
}
