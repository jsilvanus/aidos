package fi.italeino.aidos.engine.desktop

import dev.aidos.api.RealRuntimeClient
import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.identity.UuidV7Generator
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.datetime.Clock
import java.io.File

/**
 * A database-backed runtime client inside the desktop Engine app.
 *
 * **Why in-process and not a connection to the daemon.** Everything it needs is files: the user database at
 * `~/.aidos/user.db` and each project's own `state.db`. The daemon and this app are two processes over the
 * same SQLite files (WAL), so a grant made here is a row the daemon's tools read on their next call, with no
 * daemon running at all and no second protocol to keep in step. A daemon connection would make the Shell
 * grants screen unusable exactly when the user is setting things up. What it does not do: take the project
 * lock (that is the daemon's job while it runs Runs) or run anything.
 */
object DesktopRuntimeClient {
    fun create(home: String = System.getProperty("user.home")): RealRuntimeClient {
        val nowIso = { Clock.System.now().toString() }
        File(DesktopPaths.userDb(home)).parentFile?.mkdirs()
        val userDb = AidosStorage.openUser(DesktopPaths.userDb(home), "engine-desktop", nowIso)
        return RealRuntimeClient().apply {
            userDriver = userDb.driver
            projectDbFactory = { root -> AidosStorage.openProject(DesktopPaths.stateDb(root), "engine-desktop", nowIso).driver }
            runtimeManagedProjectsRoot = File(home, ".aidos/projects").absolutePath
            capabilityManagerFor = { d -> SqliteCapabilityManager(d, UuidV7Generator(), nowIso) }
        }
    }
}
