package dev.aidos.mcp

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.PlatformProfile
import dev.aidos.mcp.core.McpTransport
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
        val row = transportRow(transport)
        userDriver.execute(
            identifier = null,
            sql = "INSERT OR REPLACE INTO mcp_servers (" +
                "name, transport, command, args_json, endpoint_url, profiles_json, secret_refs_json, " +
                "binder_package, binder_service, binder_certs_json, auto_restart, registered_at" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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

    private fun transportRow(transport: McpTransport): TransportRow = when (transport) {
        is McpTransport.Stdio -> TransportRow(
            transport = "stdio",
            command = transport.command,
            argsJson = buildJsonArray { transport.args.forEach { add(JsonPrimitive(it)) } }.toString(),
            endpointUrl = null,
            secretRefsJson = encodeSecretRefs(transport.secretRefs),
        )
        is McpTransport.Http -> TransportRow(
            transport = "http",
            command = null,
            argsJson = "[]",
            endpointUrl = transport.endpointUrl,
            secretRefsJson = encodeSecretRefs(transport.secretRefs),
        )
        is McpTransport.Binder -> TransportRow(
            transport = "binder",
            command = null,
            argsJson = "[]",
            endpointUrl = null,
            secretRefsJson = "{}",
            binderPackage = transport.packageName,
            binderService = transport.serviceClass,
            binderCertsJson = buildJsonArray { transport.signingCertSha256.forEach { add(JsonPrimitive(it)) } }.toString(),
        )
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
