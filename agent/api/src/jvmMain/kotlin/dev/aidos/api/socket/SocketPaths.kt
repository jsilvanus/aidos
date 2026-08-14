package dev.aidos.api.socket

import java.nio.file.Path

/**
 * Default desktop socket/token locations (RFC-0052, RFC-0055, M10).
 *
 * Shared by the daemon (which binds here) and the CLI (which connects here), so "where does the
 * daemon listen" has exactly one answer instead of two definitions that can drift apart.
 */
object SocketPaths {
    fun defaultSocketPath(): Path {
        val xdgRuntime = System.getenv("XDG_RUNTIME_DIR") ?: System.getProperty("java.io.tmpdir")
        return Path.of(xdgRuntime, "aidos", "runtime.sock")
    }

    fun defaultTokenPath(socketPath: Path = defaultSocketPath()): Path =
        socketPath.resolveSibling("runtime.token")
}
