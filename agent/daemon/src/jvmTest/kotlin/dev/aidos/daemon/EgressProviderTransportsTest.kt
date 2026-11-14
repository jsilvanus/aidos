package dev.aidos.daemon

import dev.aidos.http.EgressClient
import dev.aidos.http.EgressRecord
import dev.aidos.http.EgressRequest
import dev.aidos.http.EgressResponse
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EgressProviderTransportsTest {
    @Test
    fun `transport posts through egress client with project context`() = runTest {
        var seenRequest: EgressRequest? = null
        var seenScope: CapabilityScope.Network? = null
        val fake = object : EgressClient {
            override suspend fun request(request: EgressRequest, scope: CapabilityScope.Network?): Result<EgressResponse> {
                seenRequest = request
                seenScope = scope
                return Result.success(
                    EgressResponse(
                        statusCode = 200,
                        headers = emptyMap(),
                        body = """{"ok":true}""".toByteArray(),
                        record = EgressRecord(
                            capabilityId = request.capabilityId.value,
                            destinationHost = "api.example.com",
                            resolvedAddressClass = dev.aidos.http.AddressClass.PUBLIC,
                            method = "POST",
                            requestBytes = request.body?.size?.toLong() ?: 0,
                            responseBytes = 11,
                            payloadHash = "h",
                            durationMs = 1,
                            outcome = dev.aidos.http.EgressOutcome.SUCCESS,
                        )
                    )
                )
            }

            override fun getAuditRecords(): List<EgressRecord> = emptyList()
        }

        val transport = EgressJsonTransport(
            egressClient = fake,
            scope = anthropicScope(),
            projectId = "proj-1",
        )
        val body = transport.postJson("https://api.anthropic.com/v1/messages", mapOf("k" to "v"), """{"a":1}""")
        assertEquals("""{"ok":true}""", body)
        assertEquals("proj-1", seenRequest?.projectId)
        assertEquals(DEFAULT_PROVIDER_CAPABILITY_ID, seenRequest?.capabilityId?.value)
        assertNull(seenRequest?.attemptId)
        assertEquals("REQUIRES_APPROVAL", seenRequest?.contentSensitivity)
        assertEquals("UNTRUSTED", seenRequest?.runTaint)
        assertEquals("api.anthropic.com", seenScope?.allowedHosts?.single())
    }

    @Test
    fun `transport respects constructor and per-call context overrides`() = runTest {
        var seenRequest: EgressRequest? = null
        val fake = object : EgressClient {
            override suspend fun request(request: EgressRequest, scope: CapabilityScope.Network?): Result<EgressResponse> {
                seenRequest = request
                return Result.success(
                    EgressResponse(
                        statusCode = 200,
                        headers = emptyMap(),
                        body = """{"ok":true}""".toByteArray(),
                        record = EgressRecord(
                            capabilityId = request.capabilityId.value,
                            destinationHost = "api.openai.com",
                            resolvedAddressClass = dev.aidos.http.AddressClass.PUBLIC,
                            method = "POST",
                            requestBytes = request.body?.size?.toLong() ?: 0,
                            responseBytes = 11,
                            payloadHash = "h",
                            durationMs = 1,
                            outcome = dev.aidos.http.EgressOutcome.SUCCESS,
                        ),
                    ),
                )
            }

            override fun getAuditRecords(): List<EgressRecord> = emptyList()
        }

        val transport = EgressJsonTransport(
            egressClient = fake,
            scope = openAiScope("https://api.openai.com/v1"),
            projectId = "proj-2",
            attemptId = "att-ctor",
            sensitivity = "INTERNAL",
            runTaint = "TRUSTED",
            capabilityId = CapabilityId("openai-egress"),
            timeoutSeconds = 45L,
            costUnits = 7L,
        )

        // 1. Constructor overrides apply by default
        transport.postJson("https://api.openai.com/v1/chat/completions", emptyMap(), "{}")
        assertEquals("openai-egress", seenRequest?.capabilityId?.value)
        assertEquals("proj-2", seenRequest?.projectId)
        assertEquals("att-ctor", seenRequest?.attemptId)
        assertEquals("INTERNAL", seenRequest?.contentSensitivity)
        assertEquals("TRUSTED", seenRequest?.runTaint)
        assertEquals(45L, seenRequest?.timeoutSeconds)
        assertEquals(7L, seenRequest?.costUnits)

        // 2. Coroutine context override updates only supplied fields and inherits the rest
        withContext(
            EgressTransportContextElement(
                EgressTransportContext(
                    attemptId = "att-coroutine",
                    runTaint = "TAINTED",
                    sensitivity = "PUBLIC",
                ),
            ),
        ) {
            transport.postJson("https://api.openai.com/v1/chat/completions", emptyMap(), "{}")
        }
        assertEquals("openai-egress", seenRequest?.capabilityId?.value)
        assertEquals("proj-2", seenRequest?.projectId)
        assertEquals("att-coroutine", seenRequest?.attemptId)
        assertEquals("PUBLIC", seenRequest?.contentSensitivity)
        assertEquals("TAINTED", seenRequest?.runTaint)

        // 3. Explicit per-call context takes precedence
        transport.postJson(
            url = "https://api.openai.com/v1/chat/completions",
            headers = emptyMap(),
            bodyJson = "{}",
            context = EgressTransportContext(
                capabilityId = CapabilityId("anthropic-egress"),
                attemptId = "att-explicit",
                costUnits = 99L,
            ),
        )
        assertEquals("anthropic-egress", seenRequest?.capabilityId?.value)
        assertEquals("att-explicit", seenRequest?.attemptId)
        assertEquals(99L, seenRequest?.costUnits)
    }
}
