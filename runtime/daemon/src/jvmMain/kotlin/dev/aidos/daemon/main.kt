package dev.aidos.daemon

import dev.aidos.cli.AidosCli
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

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
 * 2. Acquires a project lock (RFC-0055) to prevent concurrent daemon instances
 * 3. Listens on a Unix domain socket for frontend connections
 * 4. Routes CLI and UI requests to the runtime via RuntimeClient
 *
 * Frontends (CLI, web UI, IDE plugins) connect to the daemon socket and communicate
 * through the RuntimeClient interface (RFC-0052). This separates concerns:
 * - The daemon owns resources (filesystem, models, background execution)
 * - Frontends are stateless clients
 * - A user can run multiple frontends against one daemon
 *
 * In this MVP (M33):
 * - The daemon starts and prints status
 * - Socket server structure is in place (full serialization deferred to Phase 4.5)
 * - Uses MockRuntimeClient for demonstration
 */

object AidosDaemon {
    suspend fun run(socketPath: String? = null) {
        println("Aidos daemon starting (M33, RFC-0052, RFC-0055)")

        // Create runtime client
        val client = RuntimeClientFactory.createRuntimeClient()
        println("✓ Runtime initialized")

        // Create socket server
        val server = if (socketPath != null) {
            RuntimeSocketServer(client, Path.of(socketPath))
        } else {
            RuntimeSocketServer(client)
        }

        // TODO(M33 Phase 4.5): Implement project locking per RFC-0055
        // - Acquire OS advisory lock on <project-root>/.aidos/instance.lock
        // - Heartbeat every 30 seconds
        // - Detect stale locks (>3 min) and report

        // Start the socket server
        server.start()
        println("✓ Socket server listening")

        // Verify daemon is working by doing a quick test
        val version = client.runtime.version()
        println("✓ Runtime version: ${version.version} (API ${version.apiVersion}, profile ${version.profile})")

        println("\nDaemon ready. Frontends can connect to socket.")
        println("Press Ctrl+C to stop.")

        // Keep daemon running
        try {
            while (true) {
                Thread.sleep(1000)
            }
        } catch (e: InterruptedException) {
            println("\nShutting down...")
            server.stop()
            println("✓ Daemon stopped")
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
