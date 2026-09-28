package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings content (RFC-0103, Phase D): Hugging Face token entry/status/clear, and restoring
 * removed model suggestions. No scaffold, so Android shows it as a screen and desktop in a dialog.
 *
 * [state] is hoisted; UI transitions go through [onStateChange], while [onSaveToken] and
 * [onClearToken] are the host's persistence hooks, called before the state update that reports
 * success. [tokenStorageNote] lets a host say where (and how safely) the token is kept.
 */
@Composable
fun SettingsContent(
    state: SettingsState,
    dismissedSuggestionCount: Int,
    onStateChange: (SettingsState) -> Unit,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit,
    onRestoreSuggestions: () -> Unit,
    modifier: Modifier = Modifier,
    tokenStorageNote: String? = null,
) {
    Column(
        modifier = modifier,
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

                tokenStorageNote?.let {
                    Text(
                        it,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
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
                                onStateChange(state.copy(showTokenInput = true, tokenInput = ""))
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
                                    onClearToken()
                                    onStateChange(
                                        state.copy(
                                            hfTokenStatus = HfTokenStatus(false),
                                            successMessage = "Token cleared"
                                        )
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
                            onValueChange = { onStateChange(state.copy(tokenInput = it)) },
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
                                val validationError = validateHfToken(token)
                                if (validationError != null) {
                                    onStateChange(state.copy(errorMessage = validationError, successMessage = null))
                                    return@Button
                                }
                                onSaveToken(token)
                                onStateChange(
                                    state.copy(
                                        showTokenInput = false,
                                        tokenInput = "",
                                        hfTokenStatus = HfTokenStatus(
                                            isConfigured = true,
                                            lastValidatedMs = System.currentTimeMillis(),
                                        ),
                                        successMessage = "Token saved",
                                        errorMessage = null,
                                    )
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
                                onStateChange(
                                    state.copy(
                                        showTokenInput = false,
                                        tokenInput = "",
                                        successMessage = null,
                                        errorMessage = null
                                    )
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
            "Suggested Models",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp)
        )

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    if (dismissedSuggestionCount == 0) "All suggestions are shown on the Models screen."
                    else "$dismissedSuggestionCount removed suggestion" +
                        (if (dismissedSuggestionCount == 1) "" else "s") + " hidden from the Models screen.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onRestoreSuggestions,
                    enabled = dismissedSuggestionCount > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("Restore suggestions")
                }
            }
        }
    }
}

/** Null when [token] looks like a Hugging Face access token, else the message to show. */
fun validateHfToken(token: String): String? = when {
    token.isEmpty() -> "Token cannot be empty"
    !token.startsWith("hf_") || token.length < 12 -> "Token must look like a Hugging Face access token"
    else -> null
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
