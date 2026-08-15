package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Dropdown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import fi.italeino.aidos.engine.ModelStateManager
import kotlinx.coroutines.launch

/**
 * Test chat screen for interacting with loaded models (RFC-0103).
 *
 * Allows users to:
 * - Select a loaded model
 * - Send chat messages
 * - View model responses with token usage and latency
 * - Load additional models
 */
@Composable
fun TestChatScreen(
    viewModel: TestChatViewModel = viewModel(),
) {
    val modelStateManager = remember { ModelStateManager.getInstance() }
    val loadedModels by modelStateManager.loadedModels.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val error by viewModel.error.collectAsState()
    val lastLatencyMs by viewModel.lastLatencyMs.collectAsState()
    val lastTokenUsage by viewModel.lastTokenUsage.collectAsState()
    
    var userInput by remember { mutableStateOf("") }
    var showModelDropdown by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header with model selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Test Chat", style = MaterialTheme.typography.headlineSmall)
            
            // Model selector dropdown
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Button(
                    onClick = { showModelDropdown = !showModelDropdown },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(selectedModel ?: "Select Model")
                }
                
                DropdownMenu(
                    expanded = showModelDropdown,
                    onDismissRequest = { showModelDropdown = false }
                ) {
                    loadedModels.forEach { modelId ->
                        DropdownMenuItem(
                            text = { Text(modelId) },
                            onClick = {
                                viewModel.selectModel(modelId)
                                showModelDropdown = false
                            }
                        )
                    }
                    if (loadedModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No models loaded") },
                            onClick = { }
                        )
                    }
                }
            }
        }
        
        // Error message if present
        if (error != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .background(MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
        
        // Chat messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 16.dp),
            reverseLayout = false
        ) {
            items(messages) { message ->
                ChatMessageItem(message, lastLatencyMs, lastTokenUsage)
            }
        }
        
        // Input area
        if (selectedModel != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = userInput,
                    onValueChange = { userInput = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    placeholder = { Text("Type a message...") },
                    enabled = !isLoading,
                    maxLines = 3
                )
                
                Button(
                    onClick = {
                        if (userInput.isNotBlank() && selectedModel != null) {
                            viewModel.sendMessage(userInput, selectedModel!!)
                            userInput = ""
                        }
                    },
                    enabled = !isLoading && selectedModel != null && userInput.isNotBlank(),
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(4.dp)
                                .align(Alignment.CenterVertically)
                        )
                    } else {
                        Text("Send")
                    }
                }
            }
        } else {
            Text(
                "Select a model above to start chatting",
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Individual chat message display.
 */
@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    latencyMs: Long? = null,
    tokenUsage: String? = null,
) {
    val isUser = message.isUser
    val backgroundColor = if (isUser) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = if (isUser) "You" else "Model",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium
            )
            
            // Show latency and token usage for model responses
            if (!isUser && (latencyMs != null || tokenUsage != null)) {
                Text(
                    text = buildString {
                        if (latencyMs != null) {
                            append("Latency: ${latencyMs}ms")
                        }
                        if (tokenUsage != null) {
                            if (isNotEmpty()) append(" | ")
                            append(tokenUsage)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/**
 * Represents a message in the chat.
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
)
