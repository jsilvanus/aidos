package dev.aidos.mcp.binder.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.aidos.mcp.core.McpTransport

/**
 * Representation of an installed third-party app exposing an MCP server over Binder.
 */
data class DiscoveredMcpProvider(
    val packageName: String,
    val serviceClass: String,
    val appName: String,
    val serviceName: String,
    val signingCertSha256: Set<String>,
) {
    /**
     * Converts the discovered provider into an [McpTransport.Binder] registration descriptor,
     * pinning the signing certificates observed at discovery time.
     */
    fun toTransport(): McpTransport.Binder =
        McpTransport.Binder(
            packageName = packageName,
            serviceClass = serviceClass,
            signingCertSha256 = signingCertSha256,
        )
}

/**
 * Discovers installed Android applications that advertise an MCP server over Binder
 * by handling the standard intent action [ACTION_MCP_BINDER_SERVER].
 */
object InstalledMcpDiscovery {

    /** Standard action that MCP provider services declare in their manifest intent filters. */
    const val ACTION_MCP_BINDER_SERVER: String = "fi.italeino.mcp.action.BINDER_SERVER"

    /**
     * Queries the Android system for all exported services matching [ACTION_MCP_BINDER_SERVER].
     *
     * Reads each service's package, component, human-readable app label, and SHA-256 signing
     * certificate digests to prepare an adopted or adoptable [DiscoveredMcpProvider].
     *
     * Requires the `<queries>` element in AndroidManifest on Android 11+ (API 30+).
     */
    fun discoverProviders(context: Context): List<DiscoveredMcpProvider> {
        val pm = context.packageManager
        val intent = Intent(ACTION_MCP_BINDER_SERVER)

        val flags = PackageManager.GET_META_DATA
        val resolveList = pm.queryIntentServices(intent, flags)

        return resolveList.mapNotNull { resolveInfo ->
            val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
            if (!serviceInfo.exported) return@mapNotNull null // Non-exported services cannot be bound by other apps

            val pkg = serviceInfo.packageName
            val serviceClass = serviceInfo.name
            val appLabel = serviceInfo.applicationInfo?.let { it.loadLabel(pm).toString() } ?: pkg
            val serviceLabel = serviceInfo.loadLabel(pm).toString()
            val certs = AndroidBinderConnector.signingCertsOf(context, pkg)

            DiscoveredMcpProvider(
                packageName = pkg,
                serviceClass = serviceClass,
                appName = appLabel,
                serviceName = serviceLabel,
                signingCertSha256 = certs,
            )
        }
    }
}
