package dev.aidos.daemon

import dev.aidos.http.EgressClient
import dev.aidos.http.EgressRecord
import dev.aidos.http.EgressRequest
import dev.aidos.http.EgressResponse
import dev.aidos.kernel.CapabilityScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
        assertEquals("api.anthropic.com", seenScope?.allowedHosts?.single())
    }
}
