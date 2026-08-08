package dev.aidos.daemon

import dev.aidos.api.RuntimeClient
import dev.aidos.api.RealRuntimeClient

/**
 * Creates an in-process RuntimeClient implementation for the daemon (RFC-0052, M9+).
 *
 * The RealRuntimeClient replaces the MockRuntimeClient for actual runtime use. For MVP,
 * it maintains in-memory storage for projects and sessions with the same semantics as the mock,
 * but is structured to integrate with persistent services as they become available:
 *
 * Phase 4 integrations:
 * - Storage layer: persistent project and session storage
 * - Capability Manager: permission enforcement
 * - Git Tool: real diff operations
 * - Knowledge Service: semantic search
 * - Executor: real run execution
 *
 * The daemon starts this runtime and serves it over a socket to CLI frontends (RFC-0055).
 */
object RuntimeClientFactory {
    fun createRuntimeClient(): RuntimeClient {
        return RealRuntimeClient()
    }
}
