package dev.aidos.mcp

import dev.aidos.kernel.PlatformProfile
import dev.aidos.mcp.core.HttpMcpClient
import dev.aidos.mcp.core.McpClient
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.mcp.core.McpTransport
import dev.aidos.mcp.core.StdioMcpClient
import kotlinx.coroutines.CancellationException

fun interface McpSecretResolver {
    suspend fun resolve(reference: String): Result<CharArray>
}

class McpServerActivator(
    private val serverStore: McpServerStore,
    private val adoptionStore: McpOperationAdoptionStore,
    private val secretResolver: McpSecretResolver,
    private val clientFactory: McpClientFactory = McpClientFactory.Default,
) {
    suspend fun activate(
        projectId: String,
        serverName: String,
        deviceProfile: PlatformProfile,
    ): McpActivationOutcome {
        val load = findRegistration(serverName, deviceProfile)?.let { outcome ->
            when (outcome) {
                is McpServerLoadOutcome.Loaded -> outcome.load
                is McpServerLoadOutcome.Rejected ->
                    return McpActivationOutcome.Failed(McpActivationFailure.ServerRejected(serverName, outcome.reason))
            }
        } ?: return McpActivationOutcome.Failed(McpActivationFailure.ServerNotFound(serverName))

        val transport = load.registration.transport
        val secretRefs = when (transport) {
            is McpTransport.Stdio -> transport.secretRefs
            is McpTransport.Http -> transport.secretRefs
        }
        val resolvedSecrets = resolveSecrets(secretRefs).let { resolution ->
            when (resolution) {
                is SecretResolution.Failed ->
                    return McpActivationOutcome.Failed(McpActivationFailure.SecretUnresolved(serverName, resolution.reference))
                is SecretResolution.Ok -> resolution.resolved
            }
        }

        val client = try {
            clientFactory.create(transport, resolvedSecrets)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return McpActivationOutcome.Failed(McpActivationFailure.ConnectFailed(serverName, e.describe()))
        }

        val catalog = try {
            client.initialize()
            client.listTools()
        } catch (e: CancellationException) {
            client.closeSuspend()
            throw e
        } catch (e: Exception) {
            client.closeSuspend()
            return McpActivationOutcome.Failed(McpActivationFailure.ConnectFailed(serverName, e.describe()))
        }

        val resolution = adoptionStore.resolve(projectId, serverName, catalog)
        val registration = load.registration.copy(tools = resolution.adopted)
        val tool = McpTool(registration, client)
        return McpActivationOutcome.Activated(
            McpServerActivation(tool = tool, unadopted = resolution.unadopted, client = client),
        )
    }

    private fun findRegistration(serverName: String, deviceProfile: PlatformProfile): McpServerLoadOutcome? =
        serverStore.loadAll(deviceProfile).firstOrNull { outcome -> outcome.serverName() == serverName }

    private suspend fun resolveSecrets(secretRefs: Map<String, String>): SecretResolution {
        val resolved = LinkedHashMap<String, String>(secretRefs.size)
        for ((destinationName, reference) in secretRefs) {
            val chars = secretResolver.resolve(reference).getOrElse { return SecretResolution.Failed(reference) }
            try {
                resolved[destinationName] = String(chars)
            } finally {
                chars.fill('\u0000')
            }
        }
        return SecretResolution.Ok(resolved)
    }

    private fun Exception.describe(): String = message ?: (this::class.simpleName ?: "unknown error")
}

private fun McpServerLoadOutcome.serverName(): String = when (this) {
    is McpServerLoadOutcome.Loaded -> load.registration.serverId
    is McpServerLoadOutcome.Rejected -> serverId
}

private sealed interface SecretResolution {
    data class Ok(val resolved: Map<String, String>) : SecretResolution
    data class Failed(val reference: String) : SecretResolution
}

fun interface McpClientFactory {
    fun create(transport: McpTransport, resolvedSecrets: Map<String, String>): McpClient

    companion object {
        val Default: McpClientFactory = McpClientFactory { transport, resolvedSecrets ->
            when (transport) {
                is McpTransport.Stdio -> StdioMcpClient(
                    command = transport.command,
                    args = transport.args,
                    extraEnv = resolvedSecrets,
                )
                is McpTransport.Http -> HttpMcpClient(
                    endpointUrl = transport.endpointUrl,
                    authHeaderName = transport.authHeaderName,
                    authHeaderValue = resolvedSecrets[transport.authHeaderName] ?: resolvedSecrets.values.singleOrNull(),
                )
            }
        }
    }
}

sealed interface McpActivationOutcome {
    data class Activated(val activation: McpServerActivation) : McpActivationOutcome
    data class Failed(val failure: McpActivationFailure) : McpActivationOutcome
}

sealed interface McpActivationFailure {
    val serverName: String
    data class ServerNotFound(override val serverName: String) : McpActivationFailure
    data class ServerRejected(override val serverName: String, val reason: String) : McpActivationFailure
    data class SecretUnresolved(override val serverName: String, val reference: String) : McpActivationFailure
    data class ConnectFailed(override val serverName: String, val message: String) : McpActivationFailure
}

class McpServerActivation(
    val tool: McpTool,
    val unadopted: List<McpToolSpec>,
    private val client: McpClient,
) {
    suspend fun close() = client.closeSuspend()
}
