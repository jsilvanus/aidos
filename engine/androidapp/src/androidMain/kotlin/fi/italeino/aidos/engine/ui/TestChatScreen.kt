package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Test Chat Screen for interactive model testing (RFC-0103, Phase E).
 *
 * Allows users to:
 * - Send test messages to a loaded model
 * - View responses in real-time
 * - See token usage and generation metrics
 * - Test inference before fully loading a model
 *
 * This screen helps users evaluate model quality, latency, and behavior without
 * committing to loading the full model into memory.
 *
 * Note: This screen is stateful and requires the HttpModelClient to be provided
 * by the caller (or injected via DI). For now, uses a placeholder client that
 * will be wired in E.2 when navigation callback provides the client.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestChatScreen(
    modelId: String,
    modelName: String,
    onBackClick: () -> Unit,
    onSendMessage: (message: String) -> Unit = {},
    httpModelClient: HttpModelClient? = null  // Will be injected once wired
) {
    // State for this screen instance
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
                        Text(
                            "Test Chat: $modelName",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (state.messages.isNotEmpty()) {
                            Text(
                                "${state.totalTokensUsed} tokens | " +
                                        "${String.format("%.1f", state.averageTokensPerSecond)} tok/s",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
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
        ) {
            // Error message display
            if (state.error != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        state.error!!,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Chat messages area
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = false
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No messages yet.\nSend a test prompt to get started.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    items(state.messages) { message ->
                        ChatMessageBubble(message)
                    }
                }

                // Loading indicator
                if (state.isLoading) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .align(Alignment.CenterVertically)
                                    .padding(end = 8.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                "Generating response...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Input area
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = currentInput,
                    onValueChange = { currentInput = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(4.dp),
                    placeholder = { Text("Type your test message...") },
                    maxLines = 3,
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (currentInput.isNotBlank() && !state.isLoading) {
                                // Add user message
                                val userMessage = ChatMessage(
                                    role = "user",
                                    content = currentInput
                                )
                                state = state.copy(
                                    messages = state.messages + userMessage,
                                    isLoading = true,
                                    error = null
                                )

                                // Simulate API call (in real implementation, call HTTP endpoint)
                                coroutineScope.launch {
                                    try {
                                        val startTime = System.currentTimeMillis()
                                    
                                        // Use real HTTP client if available, otherwise simulate
                                        val response = if (httpModelClient != null) {
                                            try {
                                                httpModelClient.chatCompletions(
                                                    modelId = modelId,
                                                    messages = listOf(
                                                        HttpModelClient.ChatMessage(
                                                            role = "user",
                                                            content = currentInput
                                                        )
                                                    ),
                                                    temperature = 0.7f,
                                                    maxTokens = 512
                                                )
                                            } catch (e: Exception) {
                                                throw Exception("HTTP Error: ${e.message}")
                                            }
                                        } else {
                                            // Fallback simulation for preview/testing
                                            simulateModelResponse(currentInput)
                                        }
                                    
                                        val generationTime = System.currentTimeMillis() - startTime
                                    
                                        val generatedText = if (response is HttpModelClient.ChatCompletionResponse) {
                                            response.firstContent
                                        } else {
                                            response as String
                                        }
                                    
                                        val tokensUsed = if (response is HttpModelClient.ChatCompletionResponse) {
                                            response.usage.completion_tokens
                                        } else {
                                            generatedText.split(" ").size
                                        }

                                        val assistantMessage = ChatMessage(
                                            role = "assistant",
                                            content = generatedText,
                                            tokensUsed = tokensUsed,
                                            generationTimeMs = generationTime
                                        )

                                        state = state.copy(
                                            messages = state.messages + assistantMessage,
                                            isLoading = false,
                                            totalTokensUsed = state.totalTokensUsed + tokensUsed,
                                            averageTokensPerSecond = (tokensUsed.toFloat() / generationTime) * 1000
                                        )

                                        onSendMessage(currentInput)
                                    } catch (e: Exception) {
                                        state = state.copy(
                                            isLoading = false,
                                            error = "Error: ${e.message}"
                                        )
                                    }
                                }

                                currentInput = ""
                            }
                        }
                    ),
                    shape = RoundedCornerShape(8.dp)
                )

                IconButton(
                    onClick = {
                        if (currentInput.isNotBlank() && !state.isLoading) {
                            // Add user message
                            val userMessage = ChatMessage(
                                role = "user",
                                content = currentInput
                            )
                            state = state.copy(
                                messages = state.messages + userMessage,
                                isLoading = true,
                                error = null
                            )

                            // Simulate API call (in real implementation, call HTTP endpoint)
                            coroutineScope.launch {
                                try {
                                    val startTime = System.currentTimeMillis()
                                    
                                    // Use real HTTP client if available, otherwise simulate
                                    val response = if (httpModelClient != null) {
                                        try {
                                            httpModelClient.chatCompletions(
                                                modelId = modelId,
                                                messages = listOf(
                                                    HttpModelClient.ChatMessage(
                                                        role = "user",
                                                        content = currentInput
                                                    )
                                                ),
                                                temperature = 0.7f,
                                                maxTokens = 512
                                            )
                                        } catch (e: Exception) {
                                            throw Exception("HTTP Error: ${e.message}")
                                        }
                                    } else {
                                        // Fallback simulation for preview/testing
                                        simulateModelResponse(currentInput)
                                    }
                                    
                                    val generationTime = System.currentTimeMillis() - startTime
                                    
                                    val generatedText = if (response is HttpModelClient.ChatCompletionResponse) {
                                        response.firstContent
                                    } else {
                                        response as String
                                    }
                                    
                                    val tokensUsed = if (response is HttpModelClient.ChatCompletionResponse) {
                                        response.usage.completion_tokens
                                    } else {
                                        generatedText.split(" ").size
                                    }

                                    val assistantMessage = ChatMessage(
                                        role = "assistant",
                                        content = generatedText,
                                        tokensUsed = tokensUsed,
                                        generationTimeMs = generationTime
                                    )

                                    state = state.copy(
                                        messages = state.messages + assistantMessage,
                                        isLoading = false,
                                        totalTokensUsed = state.totalTokensUsed + tokensUsed,
                                        averageTokensPerSecond = (tokensUsed.toFloat() / generationTime) * 1000
                                    )

                                    onSendMessage(currentInput)
                                } catch (e: Exception) {
                                    state = state.copy(
                                        isLoading = false,
                                        error = "Error: ${e.message}"
                                    )
                                }
                            }

                            currentInput = ""
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.Bottom)
                        .padding(4.dp),
                    enabled = currentInput.isNotBlank() && !state.isLoading
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send message")
                }
            }
        }
    }
}

/**
 * Individual chat message bubble.
 *
 * User messages appear on the right with standard styling.
 * Assistant messages appear on the left and include token/speed metrics.
 */
@Composable
private fun ChatMessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (message.role == "user")
            Arrangement.End
        else
            Arrangement.Start
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

/**
 * Simulates a model response for testing.
 *
 * TODO: Replace with actual HTTP call to /v1/chat/completions endpoint.
 */
private fun simulateModelResponse(input: String): String {
    return "This is a simulated response from the model. " +
            "In the real implementation, this would call the /v1/chat/completions endpoint. " +
            "Your message was: \"$input\""
}
