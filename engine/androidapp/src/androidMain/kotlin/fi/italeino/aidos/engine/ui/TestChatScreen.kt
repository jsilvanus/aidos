package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.background
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
import kotlinx.coroutines.launch
import fi.italeino.aidos.engine.http.HttpModelClient
import fi.italeino.aidos.engine.http.ChatMessage as ApiChatMessage
import fi.italeino.aidos.engine.http.ChatCompletionResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestChatScreen(
    modelId: String,
    modelName: String,
    onBackClick: () -> Unit,
    onSendMessage: (message: String) -> Unit = {},
    httpModelClient: HttpModelClient? = null
) {
    var state by remember {
        mutableStateOf(
            TestChatState(
                modelId = modelId,
                modelName = modelName,
            )
        )
    }

    var currentInput by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(modelName, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Test Chat", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = currentInput,
                        onValueChange = { currentInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        placeholder = { Text("Type a message...") },
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Send,
                            keyboardType = KeyboardType.Text
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (currentInput.isNotBlank() && !state.isLoading) {
                                    // Handle send logic
                                }
                            }
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    IconButton(
                        onClick = {
                            if (currentInput.isNotBlank() && !state.isLoading) {
                                val messageText = currentInput
                                currentInput = ""
                                
                                val userMessage = UiChatMessage(
                                    role = "user",
                                    content = messageText
                                )
                                state = state.copy(
                                    messages = state.messages + userMessage,
                                    isLoading = true,
                                    error = null
                                )

                                coroutineScope.launch {
                                    try {
                                        val startTime = System.currentTimeMillis()
                                        
                                        val generatedText: String
                                        val tokensUsed: Int
                                        
                                        if (httpModelClient != null) {
                                            val response = httpModelClient.chatCompletions(
                                                modelId = modelId,
                                                messages = listOf(ApiChatMessage(role = "user", content = messageText))
                                            )
                                            generatedText = response.choices.firstOrNull()?.message?.content ?: ""
                                            tokensUsed = response.usage.completion_tokens
                                        } else {
                                            generatedText = simulateModelResponse(messageText)
                                            tokensUsed = generatedText.split(" ").size
                                        }
                                        
                                        val generationTime = System.currentTimeMillis() - startTime
                                        
                                        val assistantMessage = UiChatMessage(
                                            role = "assistant",
                                            content = generatedText,
                                            tokensUsed = tokensUsed,
                                            generationTimeMs = generationTime
                                        )
                                        
                                        state = state.copy(
                                            messages = state.messages + assistantMessage,
                                            isLoading = false,
                                            totalTokensUsed = state.totalTokensUsed + tokensUsed
                                        )
                                    } catch (e: Exception) {
                                        state = state.copy(
                                            isLoading = false,
                                            error = e.message ?: "Failed to get response"
                                        )
                                    }
                                }
                            }
                        },
                        enabled = currentInput.isNotBlank() && !state.isLoading
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.error != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        state.error!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(8.dp),
                        fontSize = 12.sp
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                reverseLayout = false,
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(state.messages) { message ->
                    ChatMessageBubble(message)
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(message: UiChatMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(horizontal = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (message.role == "user")
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    message.content,
                    fontSize = 14.sp,
                    color = if (message.role == "user")
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (message.role == "assistant" && message.tokensUsed != null) {
                    Text(
                        "${message.tokensUsed} tokens | ${message.generationTimeMs?.let { "${it}ms" } ?: "—"}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                        fontWeight = FontWeight.Light
                    )
                }
            }
        }
    }
}

private fun simulateModelResponse(input: String): String {
    return "This is a simulated response from the model. Your message was: \"$input\""
}
