package dev.aidos.http

import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.ResourceHandle
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolAvailability
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * HTTP tool for sessions (RFC-0030, RFC-0042, M4+).
 *
 * Provides HTTP operations for fetching resources, downloading files, and making network requests.
 * Mediated strictly through [EgressClient] and [EgressSecurityPolicy] to enforce destination
 * scoping, pre-DNS/post-DNS checks, and SSRF defense (blocking private ranges and 169.254.x.x).
 *
 * All operations marked as NETWORKED (require network availability) and all outbound operations
 * are UNSAFE (never auto-retried). Read operations (GET, HEAD) are PURE and cacheable.
 *
 * Operations: `http:get`, `http:post`, `http:put`, `http:delete`, `http:head`, `http:download`.
 */
object HttpTool : Tool {

    override val id = "http"
    override val version = "0.1.0"

    private val ALL_PROFILES = setOf(
        PlatformProfile.MOBILE, PlatformProfile.DESKTOP, PlatformProfile.HEADLESS_SERVER
    )
    // All HTTP operations require network availability (RFC-0030 M11)
    private val NETWORKED = ToolAvailability(ALL_PROFILES, AvailabilityTier.NETWORKED, requiresNetwork = true)

    /** Mediated egress client instance (RFC-0042: One client, no exceptions). */
    var egressClient: EgressClient = DefaultEgressClient()

    /** Optional resolver to map capability IDs to granted NetworkScope. */
    var scopeResolver: ((CapabilityId) -> CapabilityScope.Network?)? = null

    override fun operations(): List<ToolDescriptor> = listOf(
        // Read operations (PURE, cacheable, retryable)
        ToolDescriptor(
            name = "http:get",
            title = "HTTP GET",
            description = "Fetch a resource using HTTP GET. Returns response body and status code.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray {
                    add(JsonPrimitive("url"))
                })
                put("properties", buildJsonObject {
                    put("url", buildJsonObject { put("type", "string") })
                    put("headers", buildJsonObject { put("type", "object") })
                    put("timeout", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 300) })
                })
            },
            effect = EffectKind.Read,
            requiredPermission = Permission.NETWORK_EGRESS,
            recoveryClass = RecoveryClass.PURE,
            availability = NETWORKED,
            resultGuidance = "Response body is in the content; check status code in headers for errors.",
        ),

        ToolDescriptor(
            name = "http:head",
            title = "HTTP HEAD",
            description = "Check if a resource exists using HTTP HEAD (headers only, no body).",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray {
                    add(JsonPrimitive("url"))
                })
                put("properties", buildJsonObject {
                    put("url", buildJsonObject { put("type", "string") })
                    put("timeout", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 300) })
                })
            },
            effect = EffectKind.Read,
            requiredPermission = Permission.NETWORK_EGRESS,
            recoveryClass = RecoveryClass.PURE,
            availability = NETWORKED,
            resultGuidance = "Returns HTTP status and headers; body is empty by design.",
        ),

        // Mutate operation for downloads (writes to project)
        ToolDescriptor(
            name = "http:download",
            title = "HTTP Download",
            description = "Download a file from a URL and save it to the project. Returns preview of what will be written.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray {
                    add(JsonPrimitive("url"))
                    add(JsonPrimitive("path"))
                })
                put("properties", buildJsonObject {
                    put("url", buildJsonObject { put("type", "string") })
                    put("path", buildJsonObject { put("type", "string") })
                    put("timeout", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 300) })
                })
            },
            effect = EffectKind.Mutate(dev.aidos.kernel.MutationScope.IN_PROJECT),
            requiredPermission = Permission.NETWORK_EGRESS,
            recoveryClass = RecoveryClass.IDEMPOTENT,
            availability = NETWORKED,
            resultGuidance = "Preview shows file size and path; actual download happens on approval.",
        ),

        // Egress operations (UNSAFE, never auto-retried per RFC-0030)
        ToolDescriptor(
            name = "http:post",
            title = "HTTP POST",
            description = "Send data using HTTP POST. Cannot be auto-retried after failure (UNSAFE).",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray {
                    add(JsonPrimitive("url"))
                })
                put("properties", buildJsonObject {
                    put("url", buildJsonObject { put("type", "string") })
                    put("headers", buildJsonObject { put("type", "object") })
                    put("body", buildJsonObject { put("type", "string") })
                    put("timeout", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 300) })
                })
            },
            effect = EffectKind.Egress("remote"),
            requiredPermission = Permission.NETWORK_EGRESS,
            recoveryClass = RecoveryClass.UNSAFE,
            availability = NETWORKED,
            resultGuidance = "Response body returned; POST cannot be retried on failure per RFC-0030.",
        ),

        ToolDescriptor(
            name = "http:put",
            title = "HTTP PUT",
            description = "Send data using HTTP PUT to update a resource. Cannot be auto-retried after failure (UNSAFE).",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray {
                    add(JsonPrimitive("url"))
                })
                put("properties", buildJsonObject {
                    put("url", buildJsonObject { put("type", "string") })
                    put("headers", buildJsonObject { put("type", "object") })
                    put("body", buildJsonObject { put("type", "string") })
                    put("timeout", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 300) })
                })
            },
            effect = EffectKind.Egress("remote"),
            requiredPermission = Permission.NETWORK_EGRESS,
            recoveryClass = RecoveryClass.UNSAFE,
            availability = NETWORKED,
            resultGuidance = "Response body returned; PUT cannot be retried on failure per RFC-0030.",
        ),

        ToolDescriptor(
            name = "http:patch",
            title = "HTTP PATCH",
            description = "Send a partial update using HTTP PATCH. Cannot be auto-retried after failure (UNSAFE).",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray {
                    add(JsonPrimitive("url"))
                })
                put("properties", buildJsonObject {
                    put("url", buildJsonObject { put("type", "string") })
                    put("headers", buildJsonObject { put("type", "object") })
                    put("body", buildJsonObject { put("type", "string") })
                    put("timeout", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 300) })
                })
            },
            effect = EffectKind.Egress("remote"),
            requiredPermission = Permission.NETWORK_EGRESS,
            recoveryClass = RecoveryClass.UNSAFE,
            availability = NETWORKED,
            resultGuidance = "Response body returned; PATCH cannot be retried on failure per RFC-0030.",
        ),

        ToolDescriptor(
            name = "http:delete",
            title = "HTTP DELETE",
            description = "Delete a resource using HTTP DELETE. Cannot be auto-retried after failure (UNSAFE).",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray {
                    add(JsonPrimitive("url"))
                })
                put("properties", buildJsonObject {
                    put("url", buildJsonObject { put("type", "string") })
                    put("headers", buildJsonObject { put("type", "object") })
                    put("timeout", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 300) })
                })
            },
            effect = EffectKind.Egress("remote"),
            requiredPermission = Permission.NETWORK_EGRESS,
            recoveryClass = RecoveryClass.UNSAFE,
            availability = NETWORKED,
            resultGuidance = "Response body returned; DELETE cannot be retried on failure per RFC-0030.",
        ),
    )

    override suspend fun execute(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject,
    ): ToolCallResult {
        val capId = handle.capabilityId
        val scope = scopeResolver?.invoke(capId)

        return when (operation) {
            "http:get" -> runCatching { httpGet(capId, scope, arguments) }.fold({ ok(it) }, { err(operation, it) })
            "http:head" -> runCatching { httpHead(capId, scope, arguments) }.fold({ ok(it) }, { err(operation, it) })
            "http:post" -> runCatching { httpPost(capId, scope, arguments) }.fold({ ok(it) }, { err(operation, it) })
            "http:put" -> runCatching { httpPut(capId, scope, arguments) }.fold({ ok(it) }, { err(operation, it) })
            "http:patch" -> runCatching { httpPatch(capId, scope, arguments) }.fold({ ok(it) }, { err(operation, it) })
            "http:delete" -> runCatching { httpDelete(capId, scope, arguments) }.fold({ ok(it) }, { err(operation, it) })
            "http:download" -> runCatching { httpDownload(capId, scope, arguments) }.fold({ ok(it) }, { err(operation, it) })
            else -> err(operation, IllegalArgumentException("unknown operation: $operation"))
        }
    }

    override suspend fun preview(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject,
    ): Result<Preview> =
        when (operation) {
            "http:download" -> runCatching { previewDownload(arguments) }
            else -> Result.failure(UnsupportedOperationException("no preview for $operation"))
        }

    override suspend fun cancel(operationId: String) = Unit

    // ── HTTP Operations Mediated Through EgressClient (RFC-0042) ───────────────

    private suspend fun httpGet(
        capabilityId: CapabilityId,
        scope: CapabilityScope.Network?,
        arguments: JsonObject
    ): List<ContentBlock> {
        val url = (arguments["url"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("url parameter required")
        val timeoutSeconds = ((arguments["timeout"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 30).toLong()
        val headers = parseHeaders(arguments["headers"])

        val response = egressClient.request(
            EgressRequest(
                capabilityId = capabilityId,
                url = url,
                method = "GET",
                headers = headers,
                timeoutSeconds = timeoutSeconds
            ),
            scope = scope
        ).getOrThrow()

        return listOf(
            ContentBlock.Text("HTTP ${response.statusCode}\n\n${response.bodyString}"),
        )
    }

    private suspend fun httpHead(
        capabilityId: CapabilityId,
        scope: CapabilityScope.Network?,
        arguments: JsonObject
    ): List<ContentBlock> {
        val url = (arguments["url"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("url parameter required")
        val timeoutSeconds = ((arguments["timeout"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 30).toLong()

        val response = egressClient.request(
            EgressRequest(
                capabilityId = capabilityId,
                url = url,
                method = "HEAD",
                timeoutSeconds = timeoutSeconds
            ),
            scope = scope
        ).getOrThrow()

        val headerText = response.headers.entries.joinToString("\n") { (k, v) ->
            "$k: ${v.joinToString(", ")}"
        }
        return listOf(
            ContentBlock.Text("HTTP ${response.statusCode}\n\n$headerText"),
        )
    }

    private suspend fun httpPost(
        capabilityId: CapabilityId,
        scope: CapabilityScope.Network?,
        arguments: JsonObject
    ): List<ContentBlock> {
        val url = (arguments["url"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("url parameter required")
        val body = (arguments["body"] as? JsonPrimitive)?.content ?: ""
        val timeoutSeconds = ((arguments["timeout"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 30).toLong()
        val headers = parseHeaders(arguments["headers"])

        val response = egressClient.request(
            EgressRequest(
                capabilityId = capabilityId,
                url = url,
                method = "POST",
                headers = headers,
                body = body.toByteArray(Charsets.UTF_8),
                timeoutSeconds = timeoutSeconds
            ),
            scope = scope
        ).getOrThrow()

        return listOf(
            ContentBlock.Text("HTTP ${response.statusCode}\n\n${response.bodyString}"),
        )
    }

    private suspend fun httpPut(
        capabilityId: CapabilityId,
        scope: CapabilityScope.Network?,
        arguments: JsonObject
    ): List<ContentBlock> {
        val url = (arguments["url"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("url parameter required")
        val body = (arguments["body"] as? JsonPrimitive)?.content ?: ""
        val timeoutSeconds = ((arguments["timeout"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 30).toLong()
        val headers = parseHeaders(arguments["headers"])

        val response = egressClient.request(
            EgressRequest(
                capabilityId = capabilityId,
                url = url,
                method = "PUT",
                headers = headers,
                body = body.toByteArray(Charsets.UTF_8),
                timeoutSeconds = timeoutSeconds
            ),
            scope = scope
        ).getOrThrow()

        return listOf(
            ContentBlock.Text("HTTP ${response.statusCode}\n\n${response.bodyString}"),
        )
    }

    private suspend fun httpPatch(
        capabilityId: CapabilityId,
        scope: CapabilityScope.Network?,
        arguments: JsonObject
    ): List<ContentBlock> {
        val url = (arguments["url"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("url parameter required")
        val body = (arguments["body"] as? JsonPrimitive)?.content ?: ""
        val timeoutSeconds = ((arguments["timeout"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 30).toLong()
        val headers = parseHeaders(arguments["headers"])

        val response = egressClient.request(
            EgressRequest(
                capabilityId = capabilityId,
                url = url,
                method = "PATCH",
                headers = headers,
                body = body.toByteArray(Charsets.UTF_8),
                timeoutSeconds = timeoutSeconds
            ),
            scope = scope
        ).getOrThrow()

        return listOf(
            ContentBlock.Text("HTTP ${response.statusCode}\n\n${response.bodyString}"),
        )
    }

    private suspend fun httpDelete(
        capabilityId: CapabilityId,
        scope: CapabilityScope.Network?,
        arguments: JsonObject
    ): List<ContentBlock> {
        val url = (arguments["url"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("url parameter required")
        val timeoutSeconds = ((arguments["timeout"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 30).toLong()
        val headers = parseHeaders(arguments["headers"])

        val response = egressClient.request(
            EgressRequest(
                capabilityId = capabilityId,
                url = url,
                method = "DELETE",
                headers = headers,
                timeoutSeconds = timeoutSeconds
            ),
            scope = scope
        ).getOrThrow()

        return listOf(
            ContentBlock.Text("HTTP ${response.statusCode}\n\n${response.bodyString}"),
        )
    }

    private suspend fun httpDownload(
        capabilityId: CapabilityId,
        scope: CapabilityScope.Network?,
        arguments: JsonObject
    ): List<ContentBlock> {
        val url = (arguments["url"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("url parameter required")
        val path = (arguments["path"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("path parameter required")
        val timeoutSeconds = ((arguments["timeout"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 30).toLong()

        val response = egressClient.request(
            EgressRequest(
                capabilityId = capabilityId,
                url = url,
                method = "GET",
                timeoutSeconds = timeoutSeconds
            ),
            scope = scope
        ).getOrThrow()

        if (response.statusCode !in 200..299) {
            throw IllegalStateException("HTTP ${response.statusCode}: ${response.bodyString}")
        }

        val contentLength = response.headers["content-length"]?.firstOrNull()?.toLongOrNull() ?: response.body.size.toLong()
        return listOf(
            ContentBlock.Text("Will download ${formatBytes(contentLength)} to '$path' from $url"),
        )
    }

    private fun previewDownload(arguments: JsonObject): Preview {
        val url = (arguments["url"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("url parameter required")
        val path = (arguments["path"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("path parameter required")

        // For preview, just show what would be written without actually fetching
        return Preview.Description(
            text = "Will save $path from $url when approved"
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun parseHeaders(headersArg: Any?): Map<String, String> {
        if (headersArg == null) return emptyMap()
        return try {
            val obj = headersArg as? JsonObject ?: return emptyMap()
            obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: "" }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }

    private fun ok(content: List<ContentBlock>): ToolCallResult =
        ToolCallResult(
            callId = "",
            outcome = ToolOutcome.Ok,
            content = content,
            trustLevel = TrustLevel.UNTRUSTED, // Network response is untrusted (RFC-0027)
        )

    private fun err(operation: String, error: Throwable): ToolCallResult {
        val isSecurity = error is SecurityException || error.cause is SecurityException
        return ToolCallResult(
            callId = "",
            outcome = ToolOutcome.Failed(
                dev.aidos.kernel.AidosError(
                    code = if (isSecurity) "http.security_violation" else "http.error",
                    errorClass = if (isSecurity) dev.aidos.kernel.ErrorClass.DENIED else dev.aidos.kernel.ErrorClass.TRANSIENT,
                    message = "${error.javaClass.simpleName}: ${error.message ?: "unknown"}"
                )
            ),
            content = listOf(ContentBlock.Text(error.message ?: "unknown error")),
            trustLevel = TrustLevel.TRUSTED,
        )
    }
}
