package dev.aidos.mcp

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.PlatformProfile
import dev.aidos.mcp.core.McpTransport
import java.net.URI
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * User-scope write path for `mcp_servers` (RFC-0031).
 *
 * Registration rows live in user.db and are written only through explicit user action. Projects
 * request by name; they never carry command/endpoint details.
 */
class McpServerRegistry(
    private val userDriver: SqlDriver,
) {
    fun upsert(
        serverId: String,
        transport: McpTransport,
        profiles: Set<PlatformProfile>,
        autoRestart: Boolean,
        nowIso: String,
    ) {
        require(serverId.isNotBlank()) { "serverId must not be blank" }
        require(profiles.isNotEmpty()) { "profiles must not be empty" }
        when (transport) {
            is McpTransport.Stdio -> require(PlatformProfile.MOBILE !in profiles) {
                "stdio transport is not supported on MOBILE profile"
            }
            is McpTransport.Binder -> require(profiles == setOf(PlatformProfile.MOBILE)) {
                "binder transport is only supported on MOBILE profile"
            }
            is McpTransport.Http -> Unit
        }
        val row = transportRow(transport)
        userDriver.execute(
            identifier = null,
            sql = "INSERT INTO mcp_servers (" +
                "name, transport, command, args_json, endpoint_url, profiles_json, secret_refs_json, " +
                "binder_package, binder_service, binder_certs_json, auto_restart, registered_at" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(name) DO UPDATE SET " +
                "transport = excluded.transport, " +
                "command = excluded.command, " +
                "args_json = excluded.args_json, " +
                "endpoint_url = excluded.endpoint_url, " +
                "profiles_json = excluded.profiles_json, " +
                "secret_refs_json = excluded.secret_refs_json, " +
                "binder_package = excluded.binder_package, " +
                "binder_service = excluded.binder_service, " +
                "binder_certs_json = excluded.binder_certs_json, " +
                "auto_restart = excluded.auto_restart",
            parameters = 12,
        ) {
            bindString(0, serverId)
            bindString(1, row.transport)
            bindString(2, row.command)
            bindString(3, row.argsJson)
            bindString(4, row.endpointUrl)
            bindString(5, encodeProfiles(profiles))
            bindString(6, row.secretRefsJson)
            bindString(7, row.binderPackage)
            bindString(8, row.binderService)
            bindString(9, row.binderCertsJson)
            bindLong(10, if (autoRestart) 1L else 0L)
            bindString(11, nowIso)
        }
    }

    fun delete(serverId: String) {
        userDriver.execute(
            identifier = null,
            sql = "DELETE FROM mcp_servers WHERE name = ?",
            parameters = 1,
        ) { bindString(0, serverId) }
    }

    private fun encodeProfiles(profiles: Set<PlatformProfile>): String =
        buildJsonArray { profiles.forEach { add(JsonPrimitive(it.name)) } }.toString()

    private fun encodeSecretRefs(secretRefs: Map<String, String>): String =
        buildJsonObject { secretRefs.forEach { (k, v) -> put(k, v) } }.toString()

    private fun validateHttpEndpointUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotEmpty()) { "HTTP MCP endpoint URL must not be blank" }
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed HTTP MCP endpoint URL: ${e.message}", e)
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "Unsupported MCP endpoint scheme '$scheme': only http and https are allowed"
        }
        require(!uri.isOpaque && !uri.host.isNullOrBlank()) {
            "HTTP MCP endpoint URL must specify a non-blank host"
        }
        require(uri.port == -1 || uri.port in 1..65535) {
            "HTTP MCP endpoint URL port out of range: ${uri.port}"
        }
        return trimmed
    }

    private fun transportRow(transport: McpTransport): TransportRow = when (transport) {
        is McpTransport.Stdio -> {
            require(transport.command.isNotBlank()) { "stdio transport command must not be blank" }
            TransportRow(
                transport = "stdio",
                command = transport.command,
                argsJson = buildJsonArray { transport.args.forEach { add(JsonPrimitive(it)) } }.toString(),
                endpointUrl = null,
                secretRefsJson = encodeSecretRefs(transport.secretRefs),
            )
        }
        is McpTransport.Http -> TransportRow(
            transport = "http",
            command = null,
            argsJson = "[]",
            endpointUrl = validateHttpEndpointUrl(transport.endpointUrl),
            secretRefsJson = encodeSecretRefs(transport.secretRefs),
        )
        is McpTransport.Binder -> {
            val normalizedCerts = transport.signingCertSha256.mapTo(linkedSetOf()) { raw ->
                val cert = raw.trim().lowercase()
                require(cert.length == 64 && cert.all { it in '0'..'9' || it in 'a'..'f' }) {
                    "Invalid SHA-256 certificate pin: '$raw'"
                }
                cert
            }
            TransportRow(
                transport = "binder",
                command = null,
                argsJson = "[]",
                endpointUrl = null,
                secretRefsJson = "{}",
                binderPackage = transport.packageName.trim(),
                binderService = transport.serviceClass.trim(),
                binderCertsJson = buildJsonArray { normalizedCerts.forEach { add(JsonPrimitive(it)) } }.toString(),
            )
        }
    }

    private data class TransportRow(
        val transport: String,
        val command: String?,
        val argsJson: String,
        val endpointUrl: String?,
        val secretRefsJson: String,
        val binderPackage: String? = null,
        val binderService: String? = null,
        val binderCertsJson: String? = null,
    )
}
