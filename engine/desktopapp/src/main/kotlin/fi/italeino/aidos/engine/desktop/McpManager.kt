package fi.italeino.aidos.engine.desktop

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.PlatformProfile
import dev.aidos.mcp.McpAdoptionService
import dev.aidos.amp.AmpToolExecutionResult
import dev.aidos.mcp.McpActivationOutcome
import dev.aidos.mcp.McpClientFactory
import dev.aidos.mcp.McpServerActivator
import dev.aidos.mcp.describe
import dev.aidos.mcp.core.McpContent
import dev.aidos.mcp.McpOperationAdoptionStore
import dev.aidos.mcp.McpSecretResolver
import dev.aidos.mcp.McpServerLoadOutcome
import dev.aidos.mcp.McpServerRegistry
import dev.aidos.mcp.McpServerStore
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.mcp.core.McpTransport
import dev.aidos.mcp.policy.McpValidationResult
import dev.aidos.mcp.policy.validateHttpEndpoint
import dev.aidos.mcp.toMcpProfile
import dev.aidos.mcp.policy.McpDescriptorHash
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths as AgentPaths
import dev.aidos.vault.ConsumerKind
import dev.aidos.vault.ConsumerRef
import dev.aidos.vault.DesktopVault
import dev.aidos.vault.SecretKind
import dev.aidos.vault.SecretRequest
import dev.aidos.vault.SecretsVault
import fi.italeino.aidos.engine.ui.McpServerUiModel
import fi.italeino.aidos.engine.ui.McpToolUiModel
import fi.italeino.aidos.engine.ui.ParsedMcpServerConfig
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * The Tools pane's real MCP broker (RFC-0031, RFC-0054, RFC-0115). Registration is user scope: the same `mcp_servers`
 * rows in `~/.aidos/user.db` that `aidos mcp add` writes and the Agent daemon loads, so a server added here is
 * visible to every Aidos project ("projects only request by name"). A catalog is fetched by really starting the
 * server (stdio) or connecting to it (HTTP) through the broker's activator. Adoption is per project; the Engine's own
 * default project ([projectId]) keeps its adoption rows in a database of its own, and an Aidos project adopts through
 * its own project database (the CLI or the Agent), not through this pane.
 *
 * Secret values pasted into a server's `env` or headers never go into `mcp_servers`: they are stored in the vault under
 * `mcp:<server>:<NAME>`, allowed only to that server, and the registration keeps the reference.
 */
class McpManager(
    private val userDriver: () -> SqlDriver,
    private val projectDriver: () -> SqlDriver,
    private val vault: () -> SecretsVault,
    private val clientFactory: McpClientFactory = McpClientFactory.Default,
    val projectId: String = ENGINE_PROJECT_ID,
    private val profile: PlatformProfile = PlatformProfile.DESKTOP,
    private val nowIso: () -> String = { Instant.now().toString() },
) {
    private val catalogCache = java.util.concurrent.ConcurrentHashMap<String, List<McpToolSpec>>()

    private val resolver = object : McpSecretResolver {
        override suspend fun resolve(reference: String): Result<CharArray> = resolveFor("engine", reference)
        override suspend fun resolveFor(serverId: String, reference: String): Result<CharArray> {
            val v = runCatching { vault() }.getOrElse { return Result.failure(it) }
            val entry = v.getByName(reference).getOrNull()
            if (entry != null) return v.resolveFor(entry.id, SecretRequest(ConsumerRef(ConsumerKind.MCP_SERVER, serverId)))
            (System.getenv(reference) ?: System.getenv(reference.uppercase()))?.let { return Result.success(it.toCharArray()) }
            return Result.failure(NoSuchElementException("secrets.not_found: $reference"))
        }
    }

    private fun adoptionStore() = McpOperationAdoptionStore(projectDriver())
    private fun service() = McpAdoptionService(McpServerStore(userDriver()), adoptionStore(), resolver, clientFactory)

    /** Every registered server, with how many of its operations the default project has adopted. */
    fun servers(): List<McpServerUiModel> {
        val adoption = adoptionStore()
        return McpServerStore(userDriver()).loadAll(profile).map { outcome ->
            when (outcome) {
                is McpServerLoadOutcome.Rejected -> McpServerUiModel(outcome.serverId, outcome.serverId, "unknown", status = "Rejected: ${outcome.reason}")
                is McpServerLoadOutcome.Loaded -> {
                    val reg = outcome.load.registration
                    val adopted = adoption.adoptedCatalog(projectId, reg.serverId).size
                    val known = catalogCache[reg.serverId]?.size ?: adopted
                    when (val t = reg.transport) {
                        is McpTransport.Stdio -> McpServerUiModel(reg.serverId, reg.serverId, "stdio", command = t.command, args = t.args, profiles = outcome.load.profiles.map { it.name }.toSet(), secretRefs = t.secretRefs, toolCount = known, adoptedCount = adopted)
                        is McpTransport.Http -> McpServerUiModel(reg.serverId, reg.serverId, "http", endpointUrl = t.endpointUrl, profiles = outcome.load.profiles.map { it.name }.toSet(), secretRefs = t.secretRefs, toolCount = known, adoptedCount = adopted)
                        is McpTransport.Binder -> McpServerUiModel(reg.serverId, reg.serverId, "binder", packageName = t.packageName, serviceClass = t.serviceClass, signingCertSha256 = t.signingCertSha256, profiles = outcome.load.profiles.map { it.name }.toSet(), toolCount = known, adoptedCount = adopted)
                    }
                }
            }
        }
    }

    /** Registers (or updates) a server. Secret values go to the vault, which must be unlocked when there are any. */
    suspend fun register(config: ParsedMcpServerConfig) {
        require(config.name.isNotBlank()) { "a server needs a name" }
        val profiles = config.profiles.map { PlatformProfile.valueOf(it.uppercase()) }.toSet().ifEmpty { setOf(profile) }
        val refs = if (config.transport.equals("binder", true)) emptyMap() else storeSecretsAfterChecks(config)
        val transport = when (config.transport.lowercase()) {
            "stdio" -> McpTransport.Stdio(requireNotNull(config.command) { "a stdio server needs a command" }, config.args, refs)
            "http" -> {
                val url = requireNotNull(config.endpointUrl) { "an http server needs a url" }
                // The same policy the broker applies when it loads the row: HTTPS only, plain HTTP only to loopback.
                (validateHttpEndpoint(url, profile.toMcpProfile()) as? McpValidationResult.Rejected)?.let { throw IllegalArgumentException(it.reason) }
                McpTransport.Http(url, secretRefs = refs)
            }
            "binder" -> McpTransport.Binder(requireNotNull(config.packageName), requireNotNull(config.serviceClass), config.signingCertSha256)
            else -> throw IllegalArgumentException("unknown transport ${config.transport}")
        }
        McpServerRegistry(userDriver()).upsert(config.name, transport, profiles, autoRestart = false, nowIso = nowIso())
        catalogCache.remove(config.name)
    }

    /** Refuses an invalid or insecure server before any secret reaches the vault. */
    private suspend fun storeSecretsAfterChecks(config: ParsedMcpServerConfig): Map<String, String> {
        when (config.transport.lowercase()) {
            "stdio" -> requireNotNull(config.command) { "a stdio server needs a command" }
            "http" -> {
                val url = requireNotNull(config.endpointUrl) { "an http server needs a url" }
                (validateHttpEndpoint(url, profile.toMcpProfile()) as? McpValidationResult.Rejected)?.let { throw IllegalArgumentException(it.reason) }
            }
            else -> Unit
        }
        return storeSecrets(config)
    }

    private suspend fun storeSecrets(config: ParsedMcpServerConfig): Map<String, String> {
        val entries = config.envOrHeaders.filterValues { it.isNotBlank() }
        if (entries.isEmpty()) return emptyMap()
        val v = vault()
        return entries.mapValues { (key, value) ->
            val name = "mcp:${config.name}:$key"
            val existing = v.getByName(name).getOrNull()
            val chars = value.toCharArray()
            val id = if (existing != null) { v.rotate(existing.id, chars); existing.id } else v.store(name, SecretKind.TOKEN, chars)
            v.setAllowedConsumers(id, listOf(ConsumerRef(ConsumerKind.MCP_SERVER, config.name)))
            name
        }
    }

    fun delete(serverId: String) {
        McpServerRegistry(userDriver()).delete(serverId)
        catalogCache.remove(serverId)
    }

    /** Really starts or connects to the server and reads its tool catalog, with what the default project has adopted. */
    suspend fun catalog(serverId: String): List<McpToolUiModel> {
        val result = service().fetchCatalog(projectId, serverId, profile).getOrThrow()
        catalogCache[serverId] = result.tools.map { it.spec }
        val adoption = adoptionStore()
        val adoptedAt = adoption.adoptedCatalog(projectId, serverId).associateBy { it.name }
        return result.tools.map { t ->
            McpToolUiModel(
                name = t.spec.name,
                serverId = serverId,
                description = t.spec.description,
                inputSchemaJson = t.spec.inputSchema.toString(),
                isAdopted = t.isAdopted,
                descriptorHash = McpDescriptorHash.hash(t.spec),
                adoptedAt = if (t.isAdopted && t.spec.name in adoptedAt) nowIso() else null,
            )
        }
    }

    /** An operation the Engine's default project has adopted, with the server it belongs to. */
    class AdoptedTool(val serverId: String, val spec: McpToolSpec)

    /** Every adopted operation of every registered server that is available on this device. */
    fun adoptedTools(): List<AdoptedTool> {
        val adoption = adoptionStore()
        return McpServerStore(userDriver()).loadAll(profile)
            .filterIsInstance<McpServerLoadOutcome.Loaded>()
            .filter { profile in it.load.profiles }
            .flatMap { loaded -> adoption.adoptedCatalog(projectId, loaded.load.registration.serverId).map { AdoptedTool(loaded.load.registration.serverId, it) } }
    }

    /**
     * Calls one adopted operation: connects to (or starts) the server through the broker's activator, calls it, and closes
     * the connection. Only an adopted operation can be called, whatever name is asked for. A failure comes back as an
     * error result for the model to read, never as an exception.
     */
    suspend fun callTool(serverId: String, toolName: String, arguments: kotlinx.serialization.json.JsonObject): AmpToolExecutionResult {
        if (adoptionStore().adoptedCatalog(projectId, serverId).none { it.name == toolName }) {
            return AmpToolExecutionResult("'$toolName' is not an adopted operation of $serverId.", isError = true)
        }
        val activator = McpServerActivator(McpServerStore(userDriver()), adoptionStore(), resolver, clientFactory)
        return when (val outcome = activator.activate(projectId, serverId, profile)) {
            is McpActivationOutcome.Failed -> AmpToolExecutionResult("Could not reach $serverId: ${outcome.failure.describe()}", isError = true)
            is McpActivationOutcome.Activated -> {
                val client = outcome.activation.client
                try {
                    val r = client.callTool(toolName, arguments)
                    AmpToolExecutionResult(r.content.joinToString("\n") { (it as? McpContent.Text)?.text ?: "[non-text content]" }, isError = r.isError)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AmpToolExecutionResult("$serverId failed: ${e.message ?: e::class.simpleName}", isError = true)
                } finally {
                    client.closeSuspend()
                }
            }
        }
    }

    fun adopt(serverId: String, toolName: String) {
        val spec = catalogCache[serverId]?.firstOrNull { it.name == toolName } ?: throw IllegalStateException("Refresh the catalog of $serverId first.")
        service().adopt(projectId, serverId, spec, nowIso())
    }

    fun unadopt(serverId: String, toolName: String) = service().unadopt(projectId, serverId, toolName)

    companion object {
        /** The id the Agents pane also uses for the Engine's default project. */
        const val ENGINE_PROJECT_ID = "engine-default"

        /** The real thing: `~/.aidos/user.db`, a project database of the Engine's own, and the user's vault. */
        fun forDesktop(
            home: String = System.getProperty("user.home"),
            engineStateDir: File = DesktopPaths.stateDir,
            vault: () -> SecretsVault = { DesktopVault.open(home).vault },
            clientFactory: McpClientFactory = McpClientFactory.Default,
        ): McpManager {
            val nowIso = { Instant.now().toString() }
            val user by lazy { AidosStorage.openUser(AgentPaths.userDb(home).also { File(it).parentFile.mkdirs() }, RUNTIME_VERSION, nowIso).driver }
            val project by lazy {
                engineStateDir.mkdirs()
                val driver = AidosStorage.openProject(File(engineStateDir, "mcp-adoption.db").absolutePath, RUNTIME_VERSION, nowIso).driver
                // Adoption rows belong to a project row; the Engine's default project is created here on first use.
                val now = nowIso()
                driver.execute(
                    identifier = null,
                    sql = "INSERT OR IGNORE INTO projects (id, name, root_path, created_at, updated_at, state_updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    parameters = 6,
                ) {
                    bindString(0, ENGINE_PROJECT_ID); bindString(1, "Engine default project"); bindString(2, engineStateDir.absolutePath)
                    bindString(3, now); bindString(4, now); bindString(5, now)
                }
                driver
            }
            return McpManager({ user }, { project }, vault, clientFactory)
        }

        private const val RUNTIME_VERSION = "0.1.0-alpha"
    }
}
