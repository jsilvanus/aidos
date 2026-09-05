package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings screen (RFC-0103, Phase D).
 *
 * Intentionally minimal: Hugging Face token entry/status/clear only.
 * No account, no sync, no per-app trust configuration (trust model is signature-only
 * and not user-configurable).
 *
 * The natural home for provider credentials generally if Aidos Engine later
 * executes remote-provider calls (RFC-0103, "Remote providers through Aidos Engine").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("aidos_engine_ui_state", Context.MODE_PRIVATE) }
    var state by remember {
        mutableStateOf(
            SettingsState(
                hfTokenStatus = HfTokenStatus(
                    isConfigured = !prefs.getString("hf_token", null).isNullOrBlank(),
                    lastValidatedMs = prefs.getLong("hf_token_validated_ms", 0L).takeIf { it > 0 }
                )
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
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
            Text(
                "Hugging Face Token",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
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
                                "Status",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (state.hfTokenStatus.isConfigured) {
                                Text(
                                    "✓ Configured · checked ${formatMinutesAgo(state.hfTokenStatus.lastValidatedMs)}",
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
                    }

                    if (state.successMessage != null) {
                        Text(
                            "✓ ${state.successMessage}",
                            fontSize = 11.sp,
                            color = Color(0xFF22C55E),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    if (state.errorMessage != null) {
                        Text(
                            "✗ ${state.errorMessage}",
                            fontSize = 11.sp,
                            color = Color(0xFFEF4444),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    if (!state.showTokenInput) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    state = state.copy(showTokenInput = true, tokenInput = "")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    if (state.hfTokenStatus.isConfigured) "Change" else "Add",
                                    fontSize = 11.sp
                                )
                            }
                            if (state.hfTokenStatus.isConfigured) {
                                TextButton(
                                    onClick = {
                                        prefs.edit().remove("hf_token").remove("hf_token_validated_ms").apply()
                                        state = state.copy(
                                            hfTokenStatus = HfTokenStatus(false),
                                            successMessage = "Token cleared"
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Clear", fontSize = 11.sp)
                                }
                            }
                        }
                    } else {
                        // Token input field
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
                                value = state.tokenInput,
                                onValueChange = { state = state.copy(tokenInput = it) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                decorationBox = { innerTextField ->
                                    if (state.tokenInput.isEmpty()) {
                                        Text(
                                            "hf_...",
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
                                onClick = {
                                    val token = state.tokenInput.trim()
                                    if (token.isEmpty()) {
                                        state = state.copy(
                                            errorMessage = "Token cannot be empty",
                                            successMessage = null,
                                        )
                                        return@Button
                                    }
                                    if (!token.startsWith("hf_") || token.length < 12) {
                                        state = state.copy(
                                            errorMessage = "Token must look like a Hugging Face access token",
                                            successMessage = null,
                                        )
                                        return@Button
                                    }
                                    prefs.edit()
                                        .putString("hf_token", token)
                                        .putLong("hf_token_validated_ms", System.currentTimeMillis())
                                        .apply()

                                    state = state.copy(
                                        showTokenInput = false,
                                        tokenInput = "",
                                        hfTokenStatus = HfTokenStatus(
                                            isConfigured = true,
                                            lastValidatedMs = System.currentTimeMillis(),
                                        ),
                                        successMessage = "Token saved",
                                        errorMessage = null,
                                    )
                                },
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
                                        showTokenInput = false,
                                        tokenInput = "",
                                        successMessage = null,
                                        errorMessage = null
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

            Text(
                "Learn more about Hugging Face tokens",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private fun formatMinutesAgo(ms: Long?): String {
    if (ms == null) return "never"
    val minutesAgo = (System.currentTimeMillis() - ms) / 60_000
    return when {
        minutesAgo < 60 -> "${minutesAgo}m ago"
        minutesAgo < 1440 -> "${minutesAgo / 60}h ago"
        else -> "${minutesAgo / 1440}d ago"
    }
}

