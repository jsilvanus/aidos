package fi.italeino.aidos.engine.http

import dev.aidos.kernel.AidosError
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectBroker
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonElement

/**
 * Android implementation of EffectBroker (RFC-0030).
 *
 * Handles tool calls that reach out to the network or system APIs.
 * Currently implements http:get for Hugging Face discovery.
 */
class AndroidEffectBroker(private val httpClient: HttpClient) : EffectBroker {
    
    override fun register(tool: Tool) {
        // Not used in MVP
    }

    override fun descriptorsFor(
        subjectId: String,
        profile: PlatformProfile,
        networkAvailable: Boolean
    ): List<ToolDescriptor> = emptyList()

    override suspend fun invoke(
        subjectId: String,
        call: ToolCall,
        runTaint: TrustLevel
    ): ToolCallResult {
        return when (call.toolName) {
            "http:get" -> handleHttpGet(call)
            else -> ToolCallResult(
                callId = call.callId,
                outcome = ToolOutcome.Failed(AidosError("tool_not_found", ErrorClass.INVALID_INPUT, "Tool ${call.toolName} not found")),
                content = listOf(ContentBlock.Text("Tool ${call.toolName} not found")),
                trustLevel = TrustLevel.UNTRUSTED
            )
        }
    }

    private suspend fun handleHttpGet(call: ToolCall): ToolCallResult {
        val url = call.arguments["url"]?.jsonPrimitive?.content ?: return ToolCallResult(
            callId = call.callId,
            outcome = ToolOutcome.Failed(AidosError("missing_url", ErrorClass.INVALID_INPUT, "Missing 'url' argument")),
            content = listOf(ContentBlock.Text("Missing 'url' argument")),
            trustLevel = TrustLevel.UNTRUSTED
        )

        return try {
            val response = httpClient.get(url)
            val body = response.bodyAsText()
            ToolCallResult(
                callId = call.callId,
                outcome = ToolOutcome.Ok,
                content = listOf(ContentBlock.Text("HTTP ${response.status.value}\n\n$body")),
                trustLevel = TrustLevel.UNTRUSTED
            )
        } catch (e: Exception) {
            ToolCallResult(
                callId = call.callId,
                outcome = ToolOutcome.Failed(AidosError("network_error", ErrorClass.UNAVAILABLE, e.message ?: "Unknown error")),
                content = listOf(ContentBlock.Text("Network error: ${e.message}")),
                trustLevel = TrustLevel.UNTRUSTED
            )
        }
    }

    override suspend fun preview(subjectId: String, call: ToolCall): Result<Preview> =
        Result.failure(UnsupportedOperationException("Preview not implemented"))

    override suspend fun cancel(callId: String) {
        // Not implemented
    }
}
