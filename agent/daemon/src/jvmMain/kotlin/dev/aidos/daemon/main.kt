package dev.aidos.daemon

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * The Aidos daemon (RFC-0052, RFC-0055).
 *
 * **Usage**
 * ```
 * aidos-daemon [--socket-path /path/to/socket]
 * ```
 *
 * The daemon:
 * 1. Initializes the Aidos runtime (kernel services, storage, project registry)
 * 2. Mints a connection token frontends must present to connect (RFC-0052 Authentication)
 * 3. Listens on a Unix domain socket for frontend connections (M10; [RuntimeSocketServer])
 * 4. Routes CLI requests to the runtime via RuntimeClient
 *
 * Frontends (CLI today; a desktop GUI or editor plugin would be a future consumer of the same
 * socket) connect to the daemon socket and communicate through the RuntimeClient interface
 * (RFC-0052). This separates concerns:
 * - The daemon owns resources (filesystem, models, background execution)
 * - Frontends are stateless clients
 * - A user can run multiple frontends against one daemon
 *
 * `RuntimeClientFactory` wires a real `RealRuntimeClient`, not a mock (M9/M10). Per-project
 * locking (RFC-0055's advisory file lock, already built at M7 as `ProjectLock`) is acquired when
 * a project is opened, not once at daemon startup — a daemon has no single project until a
 * client asks for one.
 */

object AidosDaemon {
    suspend fun run(socketPath: String? = null) {
        println("Aidos daemon starting (RFC-0052, RFC-0055)")

        // Create runtime client
        val client = RuntimeClientFactory.createRuntimeClient()
        println("✓ Runtime initialized")

        // Create socket server
        val server = if (socketPath != null) {
            RuntimeSocketServer(client, Path.of(socketPath))
        } else {
            RuntimeSocketServer(client)
        }

        // Start the socket server
        server.start()
        println("✓ Socket server listening")

        // Verify daemon is working by doing a quick test
        val version = client.runtime.version()
        println("✓ Runtime version: ${version.version} (API ${version.apiVersion}, profile ${version.profile})")

        println("\nDaemon ready. Frontends can connect to socket.")
        println("Press Ctrl+C to stop.")

        // SIGINT/SIGTERM (Ctrl+C, `kill`, a test harness's Process.destroy()) run JVM shutdown
        // hooks before the process exits regardless of what the main thread is doing -- this is
        // what actually releases the socket and token files, not the sleep loop below.
        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            runBlocking { server.stop() }
        })

        // Keep the daemon process alive; shutdown is driven by the hook above.
        while (true) {
            Thread.sleep(1000)
        }
    }
}

// Main entry point
fun main(args: Array<String>) = runBlocking {
    var socketPath: String? = null

    // Parse command line arguments
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--socket-path" -> {
                if (i + 1 < args.size) {
                    socketPath = args[i + 1]
                    i += 2
                } else {
                    System.err.println("Error: --socket-path requires an argument")
                    System.exit(1)
                }
            }
            "--help" -> {
                println("""
                    Aidos daemon - runtime server for CLI and frontend connections
                    
                    Usage: aidos-daemon [options]
                    
                    Options:
                      --socket-path PATH   Path to socket (default: ${'$'}XDG_RUNTIME_DIR/aidos/runtime.sock)
                      --help               Show this help message
                """.trimIndent())
                System.exit(0)
            }
            else -> {
                System.err.println("Unknown option: ${args[i]}")
                System.exit(1)
            }
        }
    }

    AidosDaemon.run(socketPath)
}
