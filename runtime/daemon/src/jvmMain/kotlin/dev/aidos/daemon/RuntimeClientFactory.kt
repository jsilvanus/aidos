package dev.aidos.daemon

import dev.aidos.api.JvmProjectLocker
import dev.aidos.api.RuntimeClient
import dev.aidos.api.RealRuntimeClient
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.datetime.Clock

/**
 * Creates an in-process RuntimeClient implementation for the daemon (RFC-0052, M9+).
 *
 * The RealRuntimeClient replaces the MockRuntimeClient for actual runtime use.
 *
 * Phase 4 integrations:
 * - Storage layer: persistent project storage (done -- user.db + per-project state.db, RFC-0010,
 *   RFC-0040) and RFC-0055 per-project locking. Sessions remain in-memory pending the
 *   AgentLoop<->Executor bridge (PIPELINE.md: held as its own item, not built here).
 * - Capability Manager: permission enforcement
 * - Git Tool: real diff operations
 * - Knowledge Service: semantic search
 * - Executor: real run execution
 *
 * The daemon starts this runtime and serves it over a socket to CLI frontends (RFC-0055).
 */
object RuntimeClientFactory {
    private const val RUNTIME_VERSION = "0.1.0-alpha"

    /**
     * [home] defaults to the real user home directory; tests override it with a temp directory
     * so they don't read or write `~/.aidos` on the machine actually running them.
     */
    fun createRuntimeClient(home: String = System.getProperty("user.home")): RuntimeClient {
        val nowIso = { Clock.System.now().toString() }
        val userDb = AidosStorage.openUser(DesktopPaths.userDb(home), RUNTIME_VERSION, nowIso)

        return RealRuntimeClient().apply {
            userDriver = userDb.driver
            projectDbFactory = { projectRoot ->
                AidosStorage.openProject(DesktopPaths.stateDb(projectRoot), RUNTIME_VERSION, nowIso).driver
            }
            projectLocker = JvmProjectLocker()
            runtimeManagedProjectsRoot = "$home/.aidos/projects"
        }
    }
}
