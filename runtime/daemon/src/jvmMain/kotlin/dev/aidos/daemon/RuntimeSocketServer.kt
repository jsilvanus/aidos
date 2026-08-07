package dev.aidos.daemon

import dev.aidos.api.RuntimeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

/**
 * Socket server that exposes RuntimeClient over a Unix domain socket (RFC-0055).
 *
 * The daemon listens on a socket at a platform-specific location:
 * - DESKTOP/HEADLESS: $XDG_RUNTIME_DIR/aidos/runtime.sock or similar
 * - Serves multiple concurrent CLI and frontend connections
 * - Each connection gets its own serialized request/response channel
 *
 * In this MVP (M33), we provide a minimal implementation that:
 * 1. Starts the socket server
 * 2. Accepts connections
 * 3. Would serialize/deserialize API calls (not yet implemented)
 *
 * Full serialization is deferred to Phase 4.5 (future work).
 */
class RuntimeSocketServer(
    private val client: RuntimeClient,
    private val socketPath: Path = defaultSocketPath(),
) {
    companion object {
        private fun defaultSocketPath(): Path {
            val xdgRuntime = System.getenv("XDG_RUNTIME_DIR")
                ?: System.getProperty("java.io.tmpdir")
            return Path.of(xdgRuntime, "aidos", "runtime.sock")
        }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        // Create socket directory if needed
        Files.createDirectories(socketPath.parent)

        // Remove stale socket file if it exists
        Files.deleteIfExists(socketPath)

        // TODO(M33): Implement actual socket server
        // For now, this is a placeholder that demonstrates the structure.
        // Full implementation requires:
        // 1. ServerSocket or Ktor server on socketPath
        // 2. Request/response serialization (JSON or binary protocol)
        // 3. Connection pooling and lifecycle management
        // 4. RFC-0055 project locking and heartbeat
        //
        // See RFC-0052 for transport architecture.
        println("Socket server would listen on $socketPath")
        println("Using MockRuntimeClient for now; real implementation deferred to Phase 4.5")
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        Files.deleteIfExists(socketPath)
    }
}
