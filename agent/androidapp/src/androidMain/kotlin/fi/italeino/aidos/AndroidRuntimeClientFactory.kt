package fi.italeino.aidos

import android.content.Context
import dev.aidos.api.RealRuntimeClient
import dev.aidos.storage.AndroidPaths
import dev.aidos.storage.AndroidStorage
import kotlinx.datetime.Clock

/**
 * Creates an in-process, SQLite-persisted [RealRuntimeClient] for the Android application
 * (RFC-0010, RFC-0040, RFC-0050, PIPELINE.md §2.6 & §5).
 *
 * Backs user registry, projects, sessions, runs, and tasks with real SQLite databases located
 * in the Android app-private storage root [Context.filesDir] via [app.cash.sqldelight:android-driver].
 * Sessions, runs, and tasks hydrate from disk so state survives process eviction and restarts.
 */
object AndroidRuntimeClientFactory {
    private const val RUNTIME_VERSION = "0.1.0-alpha"

    fun createRuntimeClient(context: Context): RealRuntimeClient {
        val nowIso = { Clock.System.now().toString() }
        val filesDir = context.filesDir
        val userDbPath = AndroidPaths.userDb(filesDir)
        val userDb = AndroidStorage.openUser(context, userDbPath, RUNTIME_VERSION, nowIso)

        return RealRuntimeClient().apply {
            this.userDriver = userDb.driver
            this.projectDbFactory = { projectRoot ->
                val stateDbPath = AndroidPaths.stateDb(projectRoot)
                AndroidStorage.openProject(context, stateDbPath, RUNTIME_VERSION, nowIso).driver
            }
            // ProjectLocker on Android is kept deferred per PIPELINE.md §2.6 & §5:
            // "Do not invent an Android ProjectLocker implementation without testing the real-device file-lock semantics;
            // keep that seam deferred if the platform behavior remains unverified."
            this.projectLocker = null
            this.runtimeManagedProjectsRoot = AndroidPaths.projectsDir(filesDir)
        }
    }
}
