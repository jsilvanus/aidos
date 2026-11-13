package dev.aidos.daemon

import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.Permission
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.SubjectKind
import dev.aidos.kernel.UserId
import dev.aidos.mcp.core.McpTransport
import java.net.URI
import kotlinx.coroutines.runBlocking

/** Issues the RFC-0031 MCP_SERVER capability row when a server is enabled in a project. */
internal fun ensureMcpServerCapability(
    capabilityManager: SqliteCapabilityManager,
    projectId: ProjectId,
    serverId: String,
    transport: McpTransport,
) {
    val permission = when (transport) {
        is McpTransport.Stdio -> Permission.SHELL_EXEC
        is McpTransport.Http -> Permission.NETWORK_EGRESS
        is McpTransport.Binder -> Permission.APP_IPC
    }
    val existing = runBlocking {
        capabilityManager.loadForSubject(serverId).firstOrNull {
            it.permission == permission && it.subjectKind == SubjectKind.MCP_SERVER && it.revokedAt == null
        }
    }
    if (existing != null) return
    val scope = when (transport) {
        is McpTransport.Stdio -> CapabilityScope.Shell(projectId, "/", null)
        is McpTransport.Http -> {
            val uri = URI.create(transport.endpointUrl)
            val host = uri.host ?: return
            val scheme = uri.scheme?.lowercase()
            val defaultPort = if (scheme == "http") 80 else 443
            val port = if (uri.port == -1) defaultPort else uri.port
            val allowPrivate = host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1"
            CapabilityScope.Network(
                allowedHosts = listOf(host),
                allowedPorts = listOf(port),
                allowPrivateAddresses = allowPrivate,
            )
        }
        is McpTransport.Binder -> CapabilityScope.Model(
            allowedProviders = listOf("binder:${transport.packageName}"),
            allowedKinds = emptySet(),
        )
    }
    runBlocking {
        capabilityManager.grant(
            subjectId = serverId,
            subjectKind = SubjectKind.MCP_SERVER,
            permission = permission,
            scope = scope,
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("runtime"),
        )
    }
}
