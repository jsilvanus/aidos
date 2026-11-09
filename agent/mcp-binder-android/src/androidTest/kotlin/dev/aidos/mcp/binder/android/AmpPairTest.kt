package dev.aidos.mcp.binder.android

import androidx.test.platform.app.InstrumentationRegistry
import dev.aidos.mcp.core.BinderMcpClient
import dev.aidos.mcp.core.McpContent
import dev.aidos.mcp.core.McpTransport
import fi.italeino.mcp.binder.android.ProviderUnavailableException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Aidos's Binder client against Amp's MCP provider, both installed on the device (install the
 * debug APK of `amp-mcp-android` first). Real processes, real Binder, real socket, real
 * signing-certificate checks in both directions.
 */
class AmpPairTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val amp = "com.agenticmessenger.mcp.host"
    private val service = "com.agenticmessenger.mcp.android.AmpMcpBinderService"

    private fun shell(cmd: String) {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private fun registration(certs: Set<String> = AndroidBinderConnector.signingCertsOf(ctx, amp)) =
        McpTransport.Binder(amp, service, certs)

    private fun client(reg: McpTransport.Binder) = BinderMcpClient(reg, AndroidBinderConnector(ctx))

    @Before
    fun freshAmp() = shell("pm clear $amp") // forget every earlier approval

    @Test
    fun aidosIsRefusedUntilTheUserApprovesThenReadsAmpTools() = runBlocking {
        withTimeout(60_000) {
            // 1. Unknown app: Amp refuses and records the request for the user.
            val refused = client(registration())
            val e = assertFailsWith<Exception> { refused.initialize() }
            assertTrue(generateSequence(e as Throwable) { it.cause }.any { it is ProviderUnavailableException && "refused" in it.message.orEmpty() }, "got: $e")
            refused.closeSuspend()

            // 2. The user approves (debug-only shortcut for tapping Allow in ApprovalActivity).
            shell("am start -n $amp/com.agenticmessenger.mcp.android.ApprovalActivity --ez approve_all_pending true")
            delay(1_500)

            // 3. Now it connects, lists Amp's chat tools and runs one.
            val approved = client(registration())
            approved.initialize()
            assertEquals(
                setOf("get_contact_status", "query_chat_messages", "send_chat_message"),
                approved.listTools().map { it.name }.toSet(),
            )
            val result = approved.callTool("get_contact_status", buildJsonObject { put("contactName", "Bob") })
            assertTrue(result.content.single() is McpContent.Text)
            approved.closeSuspend()
        }
    }

    @Test
    fun aidosRefusesAProviderWhoseKeyIsNotTheOneItPinned() = runBlocking {
        withTimeout(30_000) {
            val wrongPin = client(registration(certs = setOf("00".repeat(32))))
            val e = assertFailsWith<Exception> { wrongPin.initialize() }
            assertTrue(generateSequence(e as Throwable) { it.cause }.any { it is ProviderUnavailableException }, "got: $e")
            wrongPin.closeSuspend()
        }
    }
}
