package dev.aidos.daemon

import dev.aidos.api.RuntimeClient
import dev.aidos.api.MockRuntimeClient

/**
 * Creates an in-process RuntimeClient implementation for the daemon.
 *
 * This is a placeholder that currently returns a mock client.
 * In a full implementation, this would instantiate the actual runtime services
 * (ProjectService, SessionService, CapabilityManager, etc.) and wire them together
 * according to RFC-0052.
 *
 * The daemon starts this runtime and serves it over a socket to CLI frontends.
 */
object RuntimeClientFactory {
    fun createRuntimeClient(): RuntimeClient {
        // TODO(M33): Replace with real implementation that:
        // - Initializes storage layer with project databases
        // - Creates and wires together kernel services
        // - Implements capability manager, session executor, event bus
        // See RFC-0052 for the architecture
        return MockRuntimeClient()
    }
}
