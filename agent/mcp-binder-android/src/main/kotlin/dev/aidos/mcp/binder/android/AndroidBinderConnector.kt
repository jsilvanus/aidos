package dev.aidos.mcp.binder.android

import android.content.ComponentName
import android.content.Context
import dev.aidos.mcp.core.McpBinderConnector
import dev.aidos.mcp.core.McpTransport
import fi.italeino.mcp.binder.TransportLimits
import fi.italeino.mcp.binder.android.McpBinderClient
import fi.italeino.mcp.binder.android.ProviderTrust
import fi.italeino.mcp.binder.android.SigningCerts
import io.modelcontextprotocol.kotlin.sdk.shared.Transport

/**
 * Connects an `McpTransport.Binder` registration to the provider app on this device.
 *
 * The registration's certificate pin is enforced here, not trusted: [McpBinderClient] refuses a
 * provider whose package or signing certificate does not match, before binding and again once
 * connected, so a malicious app claiming the same package or component name is never talked to.
 * Failures arrive as `java.io.IOException` subtypes, which `McpServerActivator` reports as a
 * connect failure.
 *
 * Install with `McpClientFactory.withBinder(AndroidBinderConnector(context))`.
 */
class AndroidBinderConnector(
    private val context: Context,
    private val limits: TransportLimits = TransportLimits(),
    private val connectTimeoutMillis: Long = 10_000,
) : McpBinderConnector {
    override suspend fun connect(transport: McpTransport.Binder): Transport =
        McpBinderClient.connect(
            context = context,
            provider = ComponentName(transport.packageName, transport.serviceClass),
            trust = ProviderTrust.pinned(transport.packageName, transport.signingCertSha256),
            limits = limits,
            connectTimeoutMillis = connectTimeoutMillis,
        )

    companion object {
        /**
         * Lower-case SHA-256 digests of the certificates an installed [packageName] is signed with,
         * for building the pin when the user adopts a provider. Empty if it is not installed. Showing
         * these to the user (or comparing them to a known publisher key) is the adoption step; this
         * only reads them.
         */
        fun signingCertsOf(context: Context, packageName: String): Set<String> =
            SigningCerts.of(context.packageManager, packageName)
    }
}
