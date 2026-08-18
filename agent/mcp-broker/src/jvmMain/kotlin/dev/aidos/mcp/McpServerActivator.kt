package dev.aidos.mcp

import dev.aidos.kernel.PlatformProfile
import dev.aidos.mcp.core.HttpMcpClient
import dev.aidos.mcp.core.McpClient
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.mcp.core.McpTransport
import dev.aidos.mcp.core.StdioMcpClient
import kotlinx.coroutines.CancellationException

/**
 * Resolves a vault reference (RFC-0035) to its value.
 *
 * Deliberately *not* `dev.aidos.vault.SecretsVault`. This module needs exactly one capability out
 * of that subsystem, and depending on the whole of it would mean `mcp-broker` stops compiling
 * whenever anything unrelated in `:vault` does — which is not hypothetical: `:vault` also carries
 * `AnthropicAdapter`, and a kernel change to model outputs is enough to break it. The composition
 * root, which already holds a vault, passes `vault::resolve`.
 *
 * The returned array is the caller's to zero, per `SecretsVault.resolve`'s own contract.
 */
fun interface McpSecretResolver {
    suspend fun resolve(reference: String): Result<CharArray>
}

/**
 * Turns a stored `mcp_servers` registration into a broker-ready [McpTool] (RFC-0031, M18): the
 * connect → catalog → adoption → tool sequence that [McpServerStore] (load only, no connection)
 * and [McpOperationAdoptionStore] (adoption bookkeeping only) each stop short of.
 *
 * **Ordering matters and is deliberate** (RFC-0031, "Lifecycle: lazy start, idle stop"; D30):
 * the registration is looked up and every `secretRefs` entry is resolved through the vault
 * *before* [clientFactory] is ever called. [clientFactory] — which is what actually spawns a
 * stdio subprocess or opens an HTTP client — is the first thing in this class that connects to
 * anything. Nothing above it in [activate] does.
 *
 * Not wired into `agent/daemon`'s composition root by this class (out of scope for this change;
 * the caller owns choosing *when* to call [activate], which is what makes activation lazy rather
 * than eager).
 */
class McpServerActivator(
    private val serverStore: McpServerStore,
    private val adoptionStore: McpOperationAdoptionStore,
    private val secretResolver: McpSecretResolver,
    private val clientFactory: McpClientFactory = McpClientFactory.Default,
) {

    /**
     * Activates `serverName` for `projectId` on `deviceProfile`.
     *
     * Never throws for an ordinary failure — an unknown server, a rejected row, an unresolved
     * secret, or a connect/handshake failure are all reported as [McpActivationOutcome.Failed]
     * with a [McpActivationFailure] naming what went wrong, never as a thrown exception a caller
     * must remember to catch. Only truly unexpected conditions (e.g. structured coroutine
     * cancellation) propagate.
     *
     * On success, the returned [McpServerActivation] carries only *adopted* operations (D31) —
     * unadopted ones are reported separately on [McpServerActivation.unadopted], never offered to
     * the model and never causing this call to fail. An empty adopted catalog is success, not
     * [McpActivationOutcome.Failed] — RFC-0031: "An unreachable catalog is a state, not a
     * failure," and the same reasoning applies to a catalog that is reachable but wholly
     * unadopted.
     */
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

        // First connecting act: nothing above this line spawns a process or opens a connection.
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
            client.close()
            throw e
        } catch (e: Exception) {
            client.close()
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

    /**
     * Resolves every `secretRefs` entry through the vault. Stops at the first failure — naming
     * the *reference* that failed, never a value, resolved or otherwise (RFC-0031: "the value
     * never enters ... an error message"). [CharArray]s the vault returns are zeroed immediately
     * after the one-time conversion to [String] that [StdioMcpClient]'s `extraEnv` /
     * [HttpMcpClient]'s `authHeaderValue` parameters force — this class never holds a resolved
     * value any longer than that.
     */
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

    /** Never includes [Exception.message] verbatim if it could echo a resolved secret; transport errors here are protocol/IO failures, not vault failures, so message text is safe to surface. */
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

/**
 * Constructs the live [McpClient] for a registration's transport. Injected so tests can supply a
 * fake that neither spawns a process nor opens a socket; [Default] is the real stdio/HTTP
 * construction [McpServerActivator] uses outside tests.
 *
 * [resolvedSecrets] is keyed exactly as [McpTransport.secretRefs] is: destination name (env var
 * for stdio, header name for HTTP) → resolved value.
 */
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
                    // A registration with no secretRefs (a public/unauthenticated endpoint) has
                    // nothing to key by name; a registration with exactly one carries it under
                    // its own destination name, which is `authHeaderName` by construction
                    // (McpServerStore derives authHeaderName from the single secretRefs key).
                    authHeaderValue = resolvedSecrets[transport.authHeaderName] ?: resolvedSecrets.values.singleOrNull(),
                )
            }
        }
    }
}

/**
 * The outcome of [McpServerActivator.activate]: either a ready [McpServerActivation] or a typed
 * [McpActivationFailure]. Never a thrown exception for an ordinary failure mode.
 */
sealed interface McpActivationOutcome {
    data class Activated(val activation: McpServerActivation) : McpActivationOutcome
    data class Failed(val failure: McpActivationFailure) : McpActivationOutcome
}

/** Why [McpServerActivator.activate] did not produce an [McpServerActivation]. Every case names [serverName]; none carries a resolved secret value. */
sealed interface McpActivationFailure {
    val serverName: String

    /** No `mcp_servers` row by this name (RFC-0054, user scope). */
    data class ServerNotFound(override val serverName: String) : McpActivationFailure

    /** [McpServerStore] rejected the row itself (malformed JSON, disallowed endpoint, ...); [reason] is what it reported. */
    data class ServerRejected(override val serverName: String, val reason: String) : McpActivationFailure

    /** [reference] is the vault reference (RFC-0035) that failed to resolve — never the secret it names, resolved or otherwise. */
    data class SecretUnresolved(override val serverName: String, val reference: String) : McpActivationFailure

    /** The client failed to construct, `initialize()`, or `listTools()`. [message] is a transport/protocol error, never vault content. */
    data class ConnectFailed(override val serverName: String, val message: String) : McpActivationFailure
}

/**
 * A ready-to-use [tool] holding only *adopted* operations, the operations withheld as
 * [unadopted] so a caller can report what was withheld (D31), and the live [client] underneath —
 * exposed only so [close] can release it. `McpTool` itself has no close/release path of its own.
 */
class McpServerActivation(
    val tool: McpTool,
    val unadopted: List<McpToolSpec>,
    private val client: McpClient,
) : AutoCloseable {
    override fun close() = client.close()
}
