package fi.italeino.aidos.engine.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.Turn
import dev.aidos.kernel.TrustLevel
import dev.aidos.modelruntime.GlobalModelRuntime
import fi.italeino.aidos.engine.ModelStateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for TestChatScreen (RFC-0103).
 *
 * Manages chat state, message history, and inference requests for model testing.
 */
class TestChatViewModel : ViewModel() {
    private val modelStateManager = ModelStateManager.getInstance()
    
    private val _selectedModel = MutableStateFlow<String?>(null)
    val selectedModel: StateFlow<String?> = _selectedModel.asStateFlow()
    
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    private val _lastLatencyMs = MutableStateFlow<Long?>(null)
    val lastLatencyMs: StateFlow<Long?> = _lastLatencyMs.asStateFlow()
    
    private val _lastTokenUsage = MutableStateFlow<String?>(null)
    val lastTokenUsage: StateFlow<String?> = _lastTokenUsage.asStateFlow()
    
    fun selectModel(modelId: String) {
        _selectedModel.value = modelId
        _error.value = null
        // Reset chat history when switching models
        _messages.value = emptyList()
    }
    
    fun sendMessage(text: String, modelId: String) {
        if (_isLoading.value || text.isBlank()) return
        
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                
                // Add user message to chat history
                _messages.value = _messages.value + ChatMessage(text, isUser = true)
                
                // Get the runtime
                val runtime = modelStateManager.getRuntime() as? GlobalModelRuntime
                if (runtime == null) {
                    _error.value = "Model runtime not available"
                    _isLoading.value = false
                    return@launch
                }
                
                // Ensure model is loaded
                val adapterResult = runtime.load(modelId)
                if (!adapterResult.isSuccess) {
                    _error.value = "Failed to load model: ${adapterResult.exceptionOrNull()?.message}"
                    _isLoading.value = false
                    return@launch
                }
                
                val adapter = adapterResult.getOrThrow()
                
                // Convert chat history to kernel Turn format
                val messages = _messages.value.map { msg ->
                    if (msg.isUser) {
                        Turn.User(
                            content = listOf(ContentBlock.Text(msg.text)),
                            trustLevel = TrustLevel.TRUSTED
                        )
                    } else {
                        Turn.Assistant(
                            text = msg.text,
                            toolCalls = emptyList()
                        )
                    }
                }
                
                // Create inference request
                val request = ModelRequest(
                    messages = messages,
                    tools = emptyList(),
                    toolChoice = ToolChoice.None,
                    maxOutputTokens = 512,
                    stopConditions = emptyList()
                )
                
                // Run inference and measure time
                val startTime = System.currentTimeMillis()
                val responseResult = adapter.invoke(request)
                val endTime = System.currentTimeMillis()
                
                _lastLatencyMs.value = endTime - startTime
                
                if (!responseResult.isSuccess) {
                    _error.value = "Inference failed: ${responseResult.exceptionOrNull()?.message}"
                    _isLoading.value = false
                    return@launch
                }
                
                val response = responseResult.getOrThrow()
                
                // Extract assistant response
                val assistantResponse = response.text ?: "No response"
                
                // Update token usage display
                val promptTokens = response.usage.inputTokens
                val completionTokens = response.usage.outputTokens
                _lastTokenUsage.value = "Tokens: ${promptTokens} in, ${completionTokens} out"
                
                // Add model response to chat
                _messages.value = _messages.value + ChatMessage(assistantResponse, isUser = false)
                
                Log.d("TestChatViewModel", "Chat completion succeeded in ${endTime - startTime}ms")
                
            } catch (e: Exception) {
                Log.e("TestChatViewModel", "Error sending message", e)
                _error.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
