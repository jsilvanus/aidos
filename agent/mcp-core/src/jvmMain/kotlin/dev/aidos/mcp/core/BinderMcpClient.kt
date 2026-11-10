package dev.aidos.mcp.core

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.serialization.json.JsonObject

/**
 * Opens an SDK [Transport] to the app named by an [McpTransport.Binder] registration.
 *
 * This is the seam that keeps `mcp-core` free of Android. The Android layer implements it (binding
 * the service, verifying the provider's package and signing certificate against
 * [McpTransport.Binder.signingCertSha256], and handing back the transport); everything else is
 * ordinary SDK client code. An implementation **must** refuse a provider that does not match the
 * pinned certificate, and must throw rather than return a transport it did not verify.
 */
fun interface McpBinderConnector {
    suspend fun connect(transport: McpTransport.Binder): Transport
}

/**
 * Binder MCP client. Like the other clients, constructing it connects to nothing (D30): the
 * service is bound only when the SDK starts the transport, i.e. on first use.
 */
class BinderMcpClient(
    transport: McpTransport.Binder,
    connector: McpBinderConnector,
    requestTimeoutMillis: Long = 30_000,
) : McpClient, McpTaskClient {
    private val sdkClient = Client(
        clientInfo = Implementation(name = "aidos-mcp-core", version = "0.1.0"),
    )
    private val delegate = SdkMcpClient(sdkClient, ConnectOnStartTransport(transport, connector), requestTimeoutMillis)

    override suspend fun initialize(): McpServerInfo = delegate.initialize()
    override suspend fun listTools(): List<McpToolSpec> = delegate.listTools()
    override suspend fun callTool(name: String, arguments: JsonObject): McpCallResult =
        delegate.callTool(name, arguments)

    override val notifications = delegate.notifications
    override suspend fun supportsTasks(): Boolean = delegate.supportsTasks()
    override suspend fun callToolAsTask(name: String, arguments: JsonObject, ttlMillis: Long?): McpToolExecution =
        delegate.callToolAsTask(name, arguments, ttlMillis)
    override suspend fun getTask(taskId: String): McpTaskSnapshot = delegate.getTask(taskId)
    override suspend fun getTaskResult(taskId: String): McpCallResult = delegate.getTaskResult(taskId)
    override suspend fun cancelTask(taskId: String): McpCancelOutcome = delegate.cancelTask(taskId)

    override fun close() = delegate.close()
    override suspend fun closeSuspend() = delegate.closeSuspend()
}

/**
 * The SDK wants a transport object up front, but binding a service is suspending and must not
 * happen at construction. This forwards to the real transport, which [McpBinderConnector]
 * creates inside [start].
 */
internal class ConnectOnStartTransport(
    private val registration: McpTransport.Binder,
    private val connector: McpBinderConnector,
) : AbstractTransport() {
    @Volatile private var inner: Transport? = null

    override suspend fun start() {
        val real = connector.connect(registration)
        inner = real
        real.onMessage { _onMessage(it) }
        real.onError { _onError(it) }
        real.onClose { invokeOnCloseCallback() }
        real.start()
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        val real = checkNotNull(inner) { "binder transport is not started" }
        real.send(message, options)
    }

    override suspend fun close() {
        inner?.close()
    }
}
