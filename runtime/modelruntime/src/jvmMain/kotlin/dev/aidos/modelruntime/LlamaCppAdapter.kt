package dev.aidos.modelruntime

import de.kherud.llama.LlamaModel
import de.kherud.llama.args.ModelParameters
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.TokenUsage
import dev.aidos.kernel.Turn
import java.io.File

/**
 * Real llama.cpp-based inference adapter (RFC-0022, M21/M22).
 *
 * Uses llama-cpp-java JNI bindings to load and run GGUF models locally.
 * Supports constrained decoding via GBNF grammars for tool-calling
 * (RFC-0021 constrained decoding, RFC-0008 agent loop).
 *
 * ## Tool Calling Protocol (RFC-0021, M22)
 *
 * Local GGUF models without native function-calling are supported through:
 *
 * 1. **GBNF Grammar Compilation**: Tool descriptors are compiled to GBNF (GGML BNF)
 *    grammars by GbnfGrammarCompiler. This constrains the model output to valid
 *    JSON tool calls during sampling.
 *
 * 2. **Tool Call Format**: Model output is constrained to:
 *    ```
 *    {"tool": "toolName", "args": {...}}
 *    ```
 *    Multiple calls can appear in a single output, each on independent lines.
 *
 * 3. **Tool Call Parsing**: Model output is parsed by ToolCallParser to extract
 *    structured ToolCall objects with:
 *    - callId: Unique identifier (UUID-based)
 *    - toolName: Tool to invoke (validated against available tools)
 *    - arguments: JSON arguments for the tool
 *    - capabilityId: null (resolved by agent loop per RFC-0008)
 *    - rawText: Original text (retained for heuristic parsing, security audit)
 *
 * ## Design Rationale
 *
 * The constraint-based approach (vs. freeform parsing) ensures:
 * - Guaranteed well-formed JSON from the model (GBNF enforces syntax)
 * - No post-hoc schema validation needed
 * - Clear audit trail: rawText shows what the model emitted vs. what was parsed
 *
 * For M22 MVP, grammar is compiled but not yet passed to llama.cpp sampling.
 * Parsing uses heuristic pattern matching with audit trail (rawText retained).
 * Future work: Integrate compiled grammar into llama.cpp's constrained_sampling.
 *
 * Resource management:
 * - One model at a time (admission queue, RFC-0022)
 * - Context is loaded for the duration of inference
 * - Memory is freed explicitly on unload
 * - Native crashes are bounded by checkpoint recovery (RFC-0009)
 */
class LlamaCppAdapter(
    override val modelId: String,
    private val modelFile: File,
    private val metadata: GgufMetadata,
    contextSize: Int = 2048,
    threads: Int = 4,
) : ModelAdapter {
    override val providerId = "llama.cpp"
    override val modelVersion = "local-gguf"
    override val contextWindow = contextSize
    override val isLocal = true

    private val model: LlamaModel = loadModel(modelFile, contextSize, threads)

    /**
     * Load GGUF model using llama-cpp-java binding.
     *
     * Parameters are optimized for mid-range devices:
     * - Context size: configurable (default 2048 for memory efficiency)
     * - Threads: number of CPU threads for inference
     * - GPU layers: 0 by default (CPU only; GPU support in future)
     * - Use mmap: enabled for efficient file loading
     * - Use mlock: disabled to prevent app being killed under memory pressure
     */
    private fun loadModel(
        modelPath: File,
        contextSize: Int,
        threads: Int,
    ): LlamaModel {
        val params = ModelParameters()
            .setNCtx(contextSize)          // Context window size
            .setNThreads(threads)          // CPU threads for inference
            .setNGpuLayers(0)              // No GPU for now (M22+)
            .setNBatch(512)                // Batch size for token generation
            .setLogitsAll(false)           // Don't compute logits for all tokens
            .setUseMmap(true)              // Use memory-mapped file I/O
            .setUseMlock(false)            // Don't lock memory (avoid killing app)

        return try {
            LlamaModel(modelPath.absolutePath, params)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to load model $modelId from ${modelPath.absolutePath}", e)
        }
    }

    /**
     * Check if model supports native tool calling.
     * llama.cpp uses constrained decoding (GBNF) for tool calling,
     * not native function calling support in the model.
     */
    override fun supportsNativeToolCalls(): Boolean = false

    /**
     * Run inference with the loaded model (RFC-0021, RFC-0022).
     *
     * Supports:
     * - Text generation from input turns (system/user/assistant)
     * - Constrained decoding via GBNF grammar for tool-calling
     * - Stop criteria (max tokens, stop sequences)
     * - Token counting and usage reporting
     *
     * Thread-safe via the global admission queue (RFC-0022).
     */
    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        return try {
            // Convert turns to prompt text
            val prompt = formatPrompt(request.messages)

            // If tools are provided, compile GBNF grammar for constrained decoding (RFC-0021)
            if (request.tools.isNotEmpty()) {
                compileToolGrammar(request.tools)
            }

            // Generate response using llama-java API
            // The generateToken method returns a completion handle with hasNext()/next()
            val output = buildString {
                val completionHandle = model.generateToken(prompt)
                var tokenCount = 0
                
                while (completionHandle.hasNext() && tokenCount < request.maxOutputTokens) {
                    val token = completionHandle.next()
                    append(token)
                    tokenCount++
                    
                    if (shouldStop(toString(), request.stopConditions)) break
                }
            }

            // Extract tool calls from output if tools were requested
            val toolCalls = if (request.tools.isNotEmpty()) {
                extractToolCalls(output, request.tools)
            } else {
                emptyList()
            }

            // Determine stop reason
            val stopReason = when {
                estimateTokens(output) >= request.maxOutputTokens -> StopReason.MAX_TOKENS
                shouldStop(output, request.stopConditions) -> StopReason.STOP_SEQUENCE
                else -> StopReason.END_TURN
            }

            // Token counting (approximate; llama-cpp-java doesn't expose exact counts yet)
            val usage = TokenUsage(
                inputTokens = estimateTokens(prompt),
                outputTokens = estimateTokens(output),
            )

            Result.success(
                ModelResponse(
                    text = output,
                    toolCalls = toolCalls,
                    stopReason = stopReason,
                    usage = usage,
                    modelId = modelId,
                    modelVersion = modelVersion,
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Convert turn list to a prompt string (RFC-0025 prompt construction).
     *
     * Format:
     * 1. System turn (if present)
     * 2. Then for each turn: role + content
     * 3. Add "Assistant:" prompt to continue
     */
    private fun formatPrompt(turns: List<Turn>): String {
        val prompt = StringBuilder()

        for (turn in turns) {
            when (turn) {
                is Turn.System -> {
                    prompt.append(turn.content).append("\n\n")
                }
                is Turn.User -> {
                    prompt.append("User: ")
                    for (block in turn.content) {
                        when (block) {
                            is ContentBlock.Text -> prompt.append(block.text)
                            is ContentBlock.Image -> prompt.append("[Image: ${block.mimeType}]")
                        }
                    }
                    prompt.append("\n")
                }
                is Turn.Assistant -> {
                    prompt.append("Assistant: ")
                    turn.text?.let { prompt.append(it) }
                    prompt.append("\n")
                }
                is Turn.ToolResult -> {
                    prompt.append("Tool result: ")
                    for (block in turn.result.content) {
                        when (block) {
                            is ContentBlock.Text -> prompt.append(block.text)
                            is ContentBlock.Image -> prompt.append("[Image: ${block.mimeType}]")
                        }
                    }
                    prompt.append("\n")
                }
            }
        }

        prompt.append("Assistant:")
        return prompt.toString()
    }

    /**
     * Compile tool definitions to GBNF grammar for constrained decoding (RFC-0021, M22).
     *
     * GBNF (GGML BNF) is llama.cpp's grammar format for enforcing structured output.
     * This creates a grammar that validates tool calls match the schema.
     *
     * The compiled grammar constrains the model to generate valid JSON tool calls
     * with the structure: {"tool": "toolName", "args": {...}}
     *
     * See GbnfGrammarCompiler for the implementation.
     */
    private fun compileToolGrammar(tools: List<dev.aidos.kernel.ToolDescriptor>): String? {
        return GbnfGrammarCompiler.compile(tools)
    }

    /**
     * Extract tool calls from model output (RFC-0021, M22).
     *
     * Parses the output text to find tool calls with the expected format:
     * {"tool": "toolName", "args": {...}}
     *
     * When constrained decoding is used, the output is guaranteed to be well-formed
     * and parsing is straightforward. When not constrained, rawText is retained
     * to preserve the original output for audit purposes (security-relevant when
     * parsing was heuristic — RFC-0021).
     *
     * See ToolCallParser for the implementation.
     */
    private fun extractToolCalls(
        output: String,
        tools: List<dev.aidos.kernel.ToolDescriptor>,
    ): List<ToolCall> {
        // For M22, assume output was not constrained by GBNF grammar yet
        // (grammar compilation happens above, but llama.cpp integration is future work).
        // Mark parsing as heuristic so rawText is retained for audit trail.
        return ToolCallParser.parse(output, tools, isConstrained = false)
    }

    /**
     * Check if output contains a stop sequence.
     */
    private fun shouldStop(output: String, stopSequences: List<String>): Boolean {
        return stopSequences.any { output.contains(it) }
    }

    /**
     * Estimate token count (rough approximation).
     * llama-cpp-java doesn't expose exact token counts in M21.
     * Token count ≈ word count * 1.3 (typical for English).
     */
    private fun estimateTokens(text: String): Int {
        return (text.split("\\s+".toRegex()).size * 1.3).toInt()
    }

    /**
     * Clean up resources (free model from memory).
     * Called by GlobalModelRuntime when unloading the model.
     */
    fun close() {
        try {
            model.close()
        } catch (e: Exception) {
            // Log but don't throw; cleanup should be best-effort
            System.err.println("Error closing llama.cpp model: ${e.message}")
        }
    }
}
