# RFC-0103: Model Loading and Test Chat Implementation

## Overview

This implementation adds model loading capability and test chat functionality to Aidos Engine, enabling users to load installed models into memory and test them with a simple chat interface.

## Components Implemented

### 1. **ModelStateManager** (RFC-0103, RFC-0022)
- **Location:** `engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ModelStateManager.kt`
- **Purpose:** Singleton managing model loading state and providing access to GlobalModelRuntime
- **Key Features:**
  - Tracks currently loaded models via `loadedModels` StateFlow (reactive UI updates)
  - Exposes loading progress via `loadingModel` StateFlow
  - Error handling via `loadError` StateFlow
  - Serialized loading through a Mutex (RFC-0022: only one model loads at a time)
  - Public methods: `loadModel(modelId)`, `unloadModel(modelId)`, `getLoadedModels()`, `getRuntime()`

### 2. **EngineHttpServer** (RFC-0103)
- **Location:** `engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/http/EngineHttpServer.kt`
- **Purpose:** Ktor-based HTTP server providing OpenAI-compatible endpoints
- **Features:**
  - Binds to 127.0.0.1 (loopback, local-only access)
  - Uses ephemeral port assignment (port 0)
  - POST `/v1/chat/completions` - OpenAI-compatible chat endpoint
  - ****** authentication
  - Automatic model loading on first chat request
  - Error handling with OpenAI-format error responses
  - Latency measurement and token usage reporting
  - Graceful shutdown support

### 3. **OpenAI DTOs** (RFC-0103)
- **Location:** `engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/http/OpenAiDtos.kt`
- **Includes:**
  - `OpenAiChatCompletionRequest` - Request format with messages, max_tokens, temperature, etc.
  - `OpenAiMessage` - Message format (role, content, tool_calls, etc.)
  - `OpenAiChatCompletionResponse` - Response format with choices and usage
  - `OpenAiToolCall` / `OpenAiFunction` - Tool calling support
  - `OpenAiUsage` - Token usage statistics
  - `OpenAiError` / `OpenAiErrorResponse` - Error handling

### 4. **TestChatScreen** (RFC-0103)
- **Location:** `engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/TestChatScreen.kt`
- **Purpose:** Compose UI for interactive model testing
- **Features:**
  - Model selector dropdown (populated from loaded models)
  - Chat message history with alternating user/model styling
  - Text input with send button
  - Loading indicator during inference
  - Latency and token usage display for model responses
  - Error message display
  - Scroll-to-latest-message behavior

### 5. **TestChatViewModel** (RFC-0103)
- **Location:** `engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/TestChatViewModel.kt`
- **Purpose:** ViewModel managing chat state and inference logic
- **Features:**
  - `selectModel(modelId)` - Model selection with chat history reset
  - `sendMessage(text, modelId)` - Send user message and get model response
  - Message history tracking with `ChatMessage` data class
  - Loading state, error handling, latency tracking
  - Token usage calculation and display
  - Integration with ModelStateManager for model loading
  - Conversion between OpenAI and kernel message formats

### 6. **Enhanced UI Components**

#### StatusPane (HomeScreen.kt)
- Displays currently loaded models
- Shows model loading status
- Displays loading errors
- "Test Chat" button to navigate to test chat screen
- "Unload" buttons per model
- Loading indicators with model name

#### ModelDetailScreen.kt
- "Load into Memory" button
- Model load state tracking (disabled when loading/loaded)
- "Unload from Memory" button when model is loaded
- Error display for load failures

### 7. **Navigation**
- **Routes.kt:** Added `TestChat` route
- **NavHost.kt:** Added navigation to TestChatScreen
- **HomeScreen.kt:** Added `onTestChat` callback from StatusPane

## Integration with Existing Infrastructure

### GlobalModelRuntime (RFC-0022)
- ModelStateManager wraps GlobalModelRuntime
- Leverages existing admission queue (serialized loading)
- Uses GlobalModelRuntimeFactory.create() for runtime initialization
- Respects digest verification and model validation

### Kernel Types (RFC-0020, RFC-0021)
- Uses kernel's Turn sealed interface (System, User, Assistant, ToolResult)
- Converts OpenAI message format to kernel format
- Leverages ModelAdapter.invoke(ModelRequest) for inference
- Handles TokenUsage and StopReason from kernel responses

## Dependencies Added

- `io.ktor:ktor-server-core:2.3.12` - HTTP server core
- `io.ktor:ktor-server-netty:2.3.12` - Netty engine for HTTP
- `io.ktor:ktor-server-cors:2.3.12` - CORS support
- `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3` - JSON serialization

## Future Work / TODOs

1. **EngineService Integration**
   - Wire EngineHttpServer into EngineService lifecycle (onCreate/onDestroy)
   - Expose HTTP server port/token via Binder handshake
   - Start foreground service with notification

2. **Approval-Based Access Control**
   - Wire ApprovalManager to HTTP endpoints
   - Require app approval for chat completions

3. **Additional Endpoints**
   - POST `/v1/embeddings` - Embedding inference
   - POST `/v1/audio/transcriptions` - Speech-to-text (Phase C)
   - GET `/v1/models` - List loaded and available models
   - POST `/models/load` - Explicit model loading endpoint
   - POST `/models/unload` - Explicit model unloading

4. **UI Enhancements**
   - Context window selector in TestChat
   - Temperature/sampling parameter controls
   - Model metadata display (size, context, quantization)
   - Download progress UI

5. **Error Handling**
   - Distinguish network errors vs inference errors
   - Add retry logic for transient failures
   - Better error messages for common issues

6. **Performance**
   - Model preloading strategies (based on cookbook verdicts)
   - Inference caching
   - Memory pressure monitoring

## Testing

### Manual Testing
1. Launch Aidos Engine app
2. Navigate to Home screen > Cookbook pane
3. Select a model and navigate to ModelDetailScreen
4. Click "Load into Memory"
5. Verify model appears in Status pane
6. Click "Test Chat with Loaded Model"
7. Send chat messages and verify responses

### Unit Test Coverage (Future)
- ModelStateManager loading/unloading
- HTTP server endpoint validation
- Request/response serialization round-trips
- Error handling scenarios

## References

- RFC-0103: Aidos Engine - Model loading and inference infrastructure
- RFC-0022: Cookbook - Model fit computation
- RFC-0020/0021: Kernel message types and tool calling
