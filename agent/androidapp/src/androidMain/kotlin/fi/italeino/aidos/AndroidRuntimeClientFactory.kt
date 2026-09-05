package fi.italeino.aidos

import android.content.Context
import dev.aidos.api.RealRuntimeClient
import dev.aidos.storage.AndroidAidosStorage
import kotlinx.datetime.Clock
import java.io.File

/**
 * Process-scoped Android RuntimeClient composition root.
 *
 * The activity and foreground service deliberately share this instance: recreating a
 * RealRuntimeClient in either component would recreate the in-memory session/event state and would
 * make the persistence seams appear wired while the UI was still talking to a different client.
 * Project locking stays unset until Android file-lock semantics have an instrumentation test.
 */
object AndroidRuntimeClientFactory {
    @Volatile
    private var instance: RealRuntimeClient? = null

    fun get(context: Context): RealRuntimeClient {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    private fun create(context: Context): RealRuntimeClient {
        val nowIso = { Clock.System.now().toString() }
        val userDriver = AndroidAidosStorage.openUser(context, nowIso)
        val projectsRoot = File(context.filesDir, "projects").apply { mkdirs() }

        return RealRuntimeClient().apply {
            this.userDriver = userDriver
            projectDbFactory = { projectRoot ->
                AndroidAidosStorage.openProject(context, projectRoot, nowIso)
            }
            runtimeManagedProjectsRoot = projectsRoot.absolutePath
        }
    }
}
