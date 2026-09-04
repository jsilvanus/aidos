package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aidos.kernel.Turn
import fi.italeino.aidos.engine.inference.InferenceTester
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestChatScreen(
    modelId: String,
    modelName: String,
    onBackClick: () -> Unit,
    inferenceTester: InferenceTester?,
) {
    var state by remember(modelId) {
        mutableStateOf(
            TestChatState(
                modelId = modelId,
                modelName = modelName,
            )
        )
    }

    var currentInput by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    fun sendMessage() {
        if (currentInput.isBlank() || state.isLoading || inferenceTester == null) return

        val messageText = currentInput.trim()
        currentInput = ""
        val userMessage = UiChatMessage(role = "user", content = messageText)
        val assistantMessage = UiChatMessage(role = "assistant", content = "")
        state = state.copy(
            messages = state.messages + userMessage + assistantMessage,
            isLoading = true,
            error = null,
        )

        coroutineScope.launch {
            // The UI contains a temporary empty assistant bubble. Exclude that bubble and let the
            // history below provide the turns already sent before this request.
            val turns = state.messages.dropLast(2).map { message ->
                when (message.role) {
                    "assistant" -> Turn.Assistant(message.content, emptyList())
                    else -> InferenceTester.userTurn(message.content)
                }
            } + InferenceTester.userTurn(messageText)

            val result = inferenceTester.run(
                modelId = modelId,
                messages = turns,
                maxOutputTokens = 512,
                onDelta = { delta ->
                    state = state.copy(
                        messages = state.messages.mapIndexed { index, message ->
                            if (index == state.messages.lastIndex && message.role == "assistant") {
                                message.copy(content = message.content + delta)
                            } else message
                        }
                    )
                },
            )

            result.fold(
                onSuccess = { metrics ->
                    state = state.copy(
                        messages = state.messages.mapIndexed { index, message ->
                            if (index == state.messages.lastIndex) {
                                message.copy(
                                    content = metrics.text,
                                    tokensUsed = metrics.outputTokens,
                                    generationTimeMs = metrics.generationMillis,
                                )
                            } else message
                        },
                        isLoading = false,
                        totalTokensUsed = state.totalTokensUsed + metrics.outputTokens,
                        averageTokensPerSecond = metrics.tokensPerSecond?.toFloat() ?: 0f,
                    )
                },
                onFailure = { error ->
                    state = state.copy(
                        isLoading = false,
                        error = error.message ?: "Inference failed",
                    )
                },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(modelName, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Internal inference tester",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.navigationBarsPadding(),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    if (inferenceTester == null) {
                        Text(
                            "Engine runtime is not available",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = currentInput,
                            onValueChange = { currentInput = it },
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            placeholder = { Text("Type a message...") },
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Send,
                                keyboardType = KeyboardType.Text,
                            ),
                            keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                            enabled = !state.isLoading && inferenceTester != null,
                            shape = RoundedCornerShape(8.dp),
                        )
                        IconButton(
                            onClick = { sendMessage() },
                            enabled = currentInput.isNotBlank() && !state.isLoading && inferenceTester != null,
                        ) {
                            if (state.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Default.Send, contentDescription = "Send")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.error != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        state.error!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(8.dp),
                        fontSize = 12.sp,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    ChatMessageBubble(message)
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(message: UiChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f).padding(horizontal = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (message.role == "user") {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    message.content.ifEmpty { if (message.role == "assistant") "…" else "" },
                    fontSize = 14.sp,
                    color = if (message.role == "user") {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                if (message.role == "assistant" && message.tokensUsed != null) {
                    Text(
                        "${message.tokensUsed} tokens | ${message.generationTimeMs ?: 0}ms",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                        fontWeight = FontWeight.Light,
                    )
                }
            }
        }
    }
}
