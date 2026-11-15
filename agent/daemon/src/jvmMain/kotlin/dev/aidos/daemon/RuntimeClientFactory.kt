package dev.aidos.daemon

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.api.JvmProjectLocker
import dev.aidos.api.ProjectLifecyclePort
import dev.aidos.api.RuntimeClient
import dev.aidos.api.RealRuntimeClient
import dev.aidos.executor.ProjectLifecycle
import dev.aidos.kernel.ProjectId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import dev.aidos.vault.DesktopVault
import dev.aidos.vault.Redactor
import kotlinx.datetime.Clock

/**
 * Creates an in-process RuntimeClient implementation for the daemon (RFC-0052, M9+).
 *
 * The RealRuntimeClient replaces the MockRuntimeClient for actual runtime use.
 *
 * Phase 4 integrations:
 * - Storage layer: persistent project storage (done -- user.db + per-project state.db, RFC-0010,
 *   RFC-0040) and RFC-0055 per-project locking.
 * - Sessions: persisted to the project's own `sessions` table (done); `sessions.send()` creates a
 *   real, durable `runs`/`tasks` row via [SqliteRunExecutor] (RFC-0008, RFC-0009 -- the
 *   AgentLoop<->executor bridge, PIPELINE.md) and drives it inline through [RuntimeCompositionRoot]
 *   -- a real `CapabilityManager`/`ToolBroker` (with `FilesystemTool`/`GitTool` registered) and a
 *   real `InferenceRouter`. The Anthropic key comes from `ANTHROPIC_API_KEY` for now, not the
 *   vault -- see [RuntimeCompositionRoot]'s own doc comment for why. Tool calls are still denied
 *   (`capability.missing`): nothing resolves a capability for a model-emitted `ToolCall` yet.
 * - Capability Manager: permission enforcement
 * - Git Tool: real diff operations
 * - Knowledge Service: semantic search
 *
 * The daemon starts this runtime and serves it over a socket to CLI frontends (RFC-0055).
 */
object RuntimeClientFactory {
    private const val RUNTIME_VERSION = "0.1.0-alpha"

    /**
     * [home] defaults to the real user home directory; tests override it with a temp directory
     * so they don't read or write `~/.aidos` on the machine actually running them.
     */
    fun createRuntimeClient(
        home: String = System.getProperty("user.home"),
        installCrashHandler: Boolean = true,
        platform: PlatformAdapter = DesktopPlatformAdapter(home = home),
    ): RuntimeClient {
        val nowIso = { Clock.System.now().toString() }
        val userDb = AidosStorage.openUser(DesktopPaths.userDb(home), RUNTIME_VERSION, nowIso)
        // RFC-0035: the vault is opened once per daemon. A vault that cannot be opened (e.g. the
        // keystore refuses) is reported and the daemon carries on without it, falling back to
        // environment variables, rather than refusing to start.
        val opened = runCatching { DesktopVault.open(home) }
            .onFailure { DaemonLog.err("aidos: secrets vault unavailable: ${it.message}") }
            .getOrNull()
        opened?.weakProtectionNotice?.let { DaemonLog.err("aidos: secrets vault: $it") }
        val daemonRedactor = Redactor().also { r -> opened?.vault?.registerAllWith(r) }
        opened?.vault?.let { DaemonLog.redactor = daemonRedactor }
        val structuredLogger = StructuredLogger(
            redactor = daemonRedactor,
            platformProfile = platform.profile,
            runtimeVersion = RUNTIME_VERSION,
            homeDir = home,
        )
        if (installCrashHandler) {
            val existing = Thread.getDefaultUncaughtExceptionHandler()
            if (existing !is CrashHandler) {
                Thread.setDefaultUncaughtExceptionHandler(
                    CrashHandler(
                        driver = userDb.driver,
                        platformProfile = platform.profile,
                        runtimeVersion = RUNTIME_VERSION,
                        homeDir = home,
                        previousHandler = existing,
                        redactor = daemonRedactor,
                        recentLogsProvider = { structuredLogger.getRecentLogs(200) },
                    )
                )
            }
        }
        val deviceIdentityStore = dev.aidos.identity.DeviceIdentityStore(userDb.driver, dev.aidos.identity.UuidV7Generator(), nowIso)
        val deviceIdentity = deviceIdentityStore.getOrRegister(
            platformProfile = platform.profile,
            defaultDisplayName = platform.profile.name.lowercase().replaceFirstChar { it.uppercase() },
        )

        return RealRuntimeClient().apply {
            // RFC-0018 / RFC-0033: grants made through the API (the CLI's `grant`) land in the project's capabilities table.
            capabilityManagerFor = { d -> dev.aidos.capability.SqliteCapabilityManager(d, dev.aidos.identity.UuidV7Generator(), nowIso) }
            userDriver = userDb.driver
            this.deviceIdentity = deviceIdentity
            this.runtimeProfile = platform.profile
            this.availabilityEvaluator = { _, projectPath ->
                val toml = projectPath?.let { java.io.File(it, "aidos.toml") }
                    ?.takeIf { it.isFile }
                    ?.readText()
                    ?: ""
                val reqs = dev.aidos.settings.ProjectRequirementsParser.parse(toml)
                dev.aidos.settings.AvailabilityEvaluator.evaluate(
                    requirements = reqs,
                    profile = platform.profile,
                    networkAvailable = platform.networkAvailable(),
                    sidecarAvailable = platform.sidecarAvailable(),
                )
            }
            projectDbFactory = { projectRoot ->
                AidosStorage.openProject(DesktopPaths.stateDb(projectRoot), RUNTIME_VERSION, nowIso).driver
            }
            projectLocker = JvmProjectLocker()
            runtimeManagedProjectsRoot = "$home/.aidos/projects"
            structuredLogger.info("runtime.started") {
                field("profile", platform.profile.name)
                field("version", RUNTIME_VERSION)
            }
            val compositionRoot = RuntimeCompositionRoot(
                anthropicApiKey = { System.getenv("ANTHROPIC_API_KEY")?.toCharArray() },
                userDriver = userDb.driver,
                vault = opened?.vault,
                mcpSecretResolver = opened?.vault?.let { VaultMcpSecretResolver(it) } ?: RuntimeCompositionRoot.envMcpSecretResolver,
                platform = platform,
            )
            runExecutor = SqliteRunExecutor(
                nowIso = nowIso,
                compositionRoot = compositionRoot,
                admission = StoragePressureAdmission(),
            )
            // RFC-0008 step 8d: the same composition (router, remote adapters resolved from the
            // same ANTHROPIC_API_KEY) resolves a parked Run as drove it, so the adapter named at
            // park time is the one `approve-run`/`deny-run` actually resumes with.
            effectApprovalGateway = SqliteEffectApprovalGateway(compositionRoot, nowIso = nowIso)
            diffService = dev.aidos.git.JGitDiffService()
            projectLifecycle = object : ProjectLifecyclePort {
                override suspend fun archiveProject(projectDriver: SqlDriver, projectId: String): Result<Unit> =
                    ProjectLifecycle(projectDriver, nowIso).archiveProject(ProjectId(projectId))

                override suspend fun deleteProject(projectDriver: SqlDriver, projectId: String): Result<Unit> =
                    ProjectLifecycle(projectDriver, nowIso).deleteProject(ProjectId(projectId))
            }
            val reconciler = GitRunReconciler(nowIso = nowIso, deviceId = deviceIdentity.deviceId.value)
            onProjectCreate = { projectPath -> ensureAidosGitignore(projectPath) }
            onProjectOpen = { projectId, driver ->
                reconciler.reconcileOnOpen(driver, ProjectId(projectId))
            }
            onSessionArchive = { sessionId, _, projectPath ->
                if (projectPath != null) {
                    val gitDir = java.io.File(projectPath, ".git")
                    if (gitDir.exists()) {
                        runCatching {
                            org.eclipse.jgit.api.Git.open(java.io.File(projectPath)).use { git ->
                                dev.aidos.worker.TreelessWorker(git.repository).pruneWorkerRef(sessionId)
                            }
                        }
                    }
                }
            }
        }
    }

    /** RFC-0054: Ensure `.aidos/` is listed in `<projectPath>/.gitignore` on project creation. */
    internal fun ensureAidosGitignore(projectPath: String) {
        val dir = java.io.File(projectPath)
        if (!dir.exists()) return
        val gitignore = java.io.File(dir, ".gitignore")
        val existingLines = if (gitignore.exists()) gitignore.readLines() else emptyList()
        if (existingLines.any { it.trim() == ".aidos/" || it.trim() == ".aidos" }) return
        val prefix = if (gitignore.exists() && gitignore.readText().let { it.isNotEmpty() && !it.endsWith("\n") }) "\n" else ""
        gitignore.appendText("$prefix.aidos/\n")
    }
}
