package dev.aidos.mcp

import dev.aidos.kernel.PlatformProfile
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.mcp.core.McpTransport

/**
 * Service driving the user-facing MCP server adoption flow (RFC-0031).
 *
 * Allows users to inspect registered servers, query their catalogs on demand,
 * and adopt or unadopt tool operations per project before runs offer them to the model.
 */
class McpAdoptionService(
    private val serverStore: McpServerStore,
    private val adoptionStore: McpOperationAdoptionStore,
    private val secretResolver: McpSecretResolver = McpSecretResolver { Result.failure(IllegalStateException("No secret resolver")) },
    private val clientFactory: McpClientFactory = McpClientFactory.Default,
) {
    fun listConfiguredServers(deviceProfile: PlatformProfile): List<McpServerRegistrationSummary> {
        return serverStore.loadAll(deviceProfile).map { outcome ->
            when (outcome) {
                is McpServerLoadOutcome.Loaded -> McpServerRegistrationSummary(
                    serverId = outcome.load.registration.serverId,
                    transportKind = when (outcome.load.registration.transport) {
                        is McpTransport.Stdio -> "stdio"
                        is McpTransport.Http -> "http"
                        is McpTransport.Binder -> "binder"
                    },
                    status = "configured",
                )
                is McpServerLoadOutcome.Rejected -> McpServerRegistrationSummary(
                    serverId = outcome.serverId,
                    transportKind = "unknown",
                    status = "rejected: ${outcome.reason}",
                )
            }
        }
    }

    suspend fun fetchCatalog(
        projectId: String,
        serverName: String,
        deviceProfile: PlatformProfile,
    ): Result<McpServerCatalog> {
        val activator = McpServerActivator(serverStore, adoptionStore, secretResolver, clientFactory)
        return when (val outcome = activator.activate(projectId, serverName, deviceProfile)) {
            is McpActivationOutcome.Activated -> {
                val adoptedNames = outcome.activation.tool.operations().map { it.name.removePrefix("$serverName:") }.toSet()
                val client = outcome.activation.client
                try {
                    val allTools = outcome.activation.catalog
                    Result.success(
                        McpServerCatalog(
                            serverId = serverName,
                            tools = allTools.map { spec ->
                                McpToolAdoptionStatus(
                                    spec = spec,
                                    isAdopted = spec.name in adoptedNames,
                                )
                            }
                        )
                    )
                } finally {
                    client.closeSuspend()
                }
            }
            is McpActivationOutcome.Failed -> Result.failure(IllegalStateException(outcome.failure.describe()))
        }
    }

    fun listAdopted(projectId: String, serverName: String): List<McpToolSpec> =
        adoptionStore.adoptedCatalog(projectId, serverName)

    fun adopt(projectId: String, serverName: String, spec: McpToolSpec, nowIso: String) {
        adoptionStore.recordAdoption(projectId, serverName, spec, nowIso)
    }

    fun unadopt(projectId: String, serverName: String, operationName: String) {
        adoptionStore.unadopt(projectId, serverName, operationName)
    }
}

data class McpServerRegistrationSummary(
    val serverId: String,
    val transportKind: String,
    val status: String,
)

data class McpServerCatalog(
    val serverId: String,
    val tools: List<McpToolAdoptionStatus>,
)

data class McpToolAdoptionStatus(
    val spec: McpToolSpec,
    val isAdopted: Boolean,
)
