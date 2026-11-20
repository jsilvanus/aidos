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

    /** True when the Engine's or the Agent daemon's vault is waiting for its passphrase (a machine-wide service starts locked, D56). */
    fun vaultLocked(): Boolean {
        val engine = running()?.let { e -> runCatching { kotlinx.coroutines.runBlocking { fi.italeino.aidos.engine.control.HttpVaultControl(e.baseUrl, e.token).status().locked } }.getOrDefault(false) } ?: false
        val agent = agentClient()?.let { c -> runCatching { c.vaultStatus().second }.getOrDefault(false) } ?: false
        return engine || agent
    }

    /**
     * Unlocks the vault of whichever of the Engine and the Agent daemon is running (they share one vault, so one
     * passphrase). Returns null on success, else the reason to show the user. A service whose vault uses the OS
     * keystore has nothing to unlock and is not a problem.
     */
    fun unlockVault(passphrase: CharArray): String? {
        val engine = running()
        val agent = agentClient()
        if (engine == null && agent == null) { passphrase.fill('\u0000'); return "Neither the Engine nor the Agent daemon is running." }
        val problems = mutableListOf<String>()
        fun note(who: String, message: String?) { if (message != null && !message.contains("nothing to unlock")) problems += "$who: $message" }
        try {
            engine?.let { e ->
                try {
                    kotlinx.coroutines.runBlocking { fi.italeino.aidos.engine.control.HttpVaultControl(e.baseUrl, e.token).unlock(passphrase.copyOf()) }
                } catch (ex: fi.italeino.aidos.engine.control.EngineControlException) { note("Engine", ex.message ?: "refused") }
                catch (ex: Exception) { note("Engine", "could not be reached") }
            }
            agent?.let { c ->
                try { c.vaultUnlock(passphrase.copyOf()) }
                catch (ex: Exception) { note("Agent daemon", ex.message?.substringAfter(": ") ?: "refused") }
            }
        } finally {
            passphrase.fill('\u0000')
        }
        return problems.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun agentClient(): dev.aidos.cli.SocketRuntimeClient? = runCatching {
        val socket = dev.aidos.api.socket.SocketPaths.defaultSocketPath()
        val token = dev.aidos.api.socket.SocketPaths.defaultTokenPath(socket)
        if (java.nio.file.Files.exists(socket) && java.nio.file.Files.isRegularFile(token)) dev.aidos.cli.SocketRuntimeClient(socket, token, interactive = true) else null
    }.getOrNull()

    companion object {
        /** The command that starts this application again: the packaged launcher when bundled, else the running JVM. */
        fun selfCommand(args: List<String>): List<String> {
            val bundled = System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile }
                ?: ProcessHandle.current().info().command().orElse(null)?.takeIf {
                    val name = File(it).name.lowercase()
                    name != "java" && name != "java.exe" && name != "javaw" && name != "javaw.exe" && File(it).isFile
                }
            if (bundled != null) return listOf(bundled) + args
            val java = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "javaw.exe" else "java").path
            return listOf(java, "-cp", System.getProperty("java.class.path"), "fi.italeino.aidos.engine.desktop.MainKt") + args
        }

        fun launchSelf(args: List<String>) {
            ProcessBuilder(selfCommand(args)).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }
    }
}
