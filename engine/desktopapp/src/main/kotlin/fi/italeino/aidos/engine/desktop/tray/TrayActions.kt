package fi.italeino.aidos.engine.desktop.tray

import fi.italeino.aidos.engine.desktop.EngineAttach
import fi.italeino.aidos.engine.desktop.RunningEngine
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * What the tray menu does to the Engine service (RFC-0115, D59). Start launches this same application headless;
 * stop asks the running Engine through the loopback admin API; the window is the normal application started
 * without arguments, which attaches to the running Engine instead of starting its own.
 */
class TrayActions(
    private val running: () -> RunningEngine? = { EngineAttach.runningEngine(selfPid = -1) },
    private val launch: (List<String>) -> Unit = ::launchSelf,
) {
    fun engineRunning(): Boolean = running() != null

    /** Starts the headless Engine unless one is already running. True when a new one was launched. */
    fun startEngine(): Boolean {
        if (running() != null) return false
        launch(listOf("--daemon"))
        return true
    }

    /** Asks the running Engine to stop. False when none is running or it refused. */
    fun stopEngine(): Boolean {
        val e = running() ?: return false
        val r = runCatching {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(
                HttpRequest.newBuilder(URI(e.baseUrl + "/v1/admin/shutdown")).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer ${e.token}").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()
        }.getOrNull()
        return r == 202
    }

    fun openWindow() = launch(emptyList())

    companion object {
        /** The command that starts this application again: the running JVM, its class path, and the main class. */
        fun selfCommand(args: List<String>): List<String> {
            val java = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "javaw.exe" else "java").path
            return listOf(java, "-cp", System.getProperty("java.class.path"), "fi.italeino.aidos.engine.desktop.MainKt") + args
        }

        fun launchSelf(args: List<String>) {
            ProcessBuilder(selfCommand(args)).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }
    }
}
