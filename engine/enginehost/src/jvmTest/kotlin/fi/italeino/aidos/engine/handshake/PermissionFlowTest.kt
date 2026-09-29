package fi.italeino.aidos.engine.handshake

import fi.italeino.aidos.engine.approval.AppApprovalStatus
import fi.italeino.aidos.engine.approval.InMemoryAppApprovalStore
import fi.italeino.aidos.engine.http.Capabilities
import fi.italeino.aidos.engine.http.EngineHttpServer
import fi.italeino.aidos.engine.http.MockModelRuntime
import fi.italeino.aidos.engine.http.ModelInfo
import fi.italeino.aidos.engine.http.TokenManager
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The SDK permission flow as Engine sees it (RFC-0103, "Trust model"), end to end on the JVM:
 * a real [HandshakeCore], [TokenManager] and [EngineHttpServer] auth pipeline over an approval
 * store. Only Binder (caller UID → package name) and the notification/PendingIntent are stubbed;
 * those live in `EngineHandshakeImpl` and can only be verified on a device.
 *
 * "Client" here is the sequence of things an SDK client does: handshake, then present the token to
 * `/v1/`. The SDK's own reaction to each status is covered in sdk/client's tests.
 */
class PermissionFlowTest {

    private val dictator = "com.dictator.android"
    private val agent = "dev.aidos.agent"

    private class Rig {
        val store = InMemoryAppApprovalStore()
        val tokens = TokenManager()
        val notified = mutableListOf<Pair<String, String>>()
        val core = HandshakeCore(
            store = store,
            tokenManager = tokens,
            boundPort = { 4242 },
            capabilities = {
                Capabilities(
                    endpoints = listOf("models", "chat.completions"),
                    models = listOf(ModelInfo(id = "test-model", kind = "llm", context_window = 2048))
                )
            },
            resolveDisplayName = { "App(" + it + ")" },
            onFirstRequest = { pkg, name -> notified += pkg to name }
        )
        val server = EngineHttpServer(
            tokens,
            MockModelRuntime(),
            isSubjectApproved = { pkg -> store.getApproval(pkg)?.status == AppApprovalStatus.APPROVED }
        )
    }

    private suspend fun ApplicationTestBuilder.statusOf(token: String?): HttpStatusCode =
        client.get("/v1/models") { token?.let { bearerAuth(it) } }.status

    @Test
    fun firstRequest_landsAsPending_notifiesOnce_andIssuesNothing() = testApplication {
        val rig = Rig()
        application { rig.server.installInto(this) }

        val first = rig.core.handshake(dictator)
        val second = rig.core.handshake(dictator)

        assertEquals(HandshakeWire.STATUS_PENDING_APPROVAL, first.status)
        assertEquals("", first.token)
        assertEquals(0, first.port)
        assertEquals("{}", first.capabilitiesJson)
        assertEquals(HandshakeWire.STATUS_PENDING_APPROVAL, second.status)

        // The user is told once, not on every retry.
        assertEquals(listOf(dictator to "App($dictator)"), rig.notified)
        // It shows up on ConnectedAppsScreen as pending, with its attempts counted.
        val record = assertNotNull(rig.store.getApproval(dictator))
        assertEquals(AppApprovalStatus.PENDING, record.status)
        assertEquals("App($dictator)", record.displayName)
        assertEquals(2, record.attemptCount)
        // No token exists to use.
        assertEquals(HttpStatusCode.Unauthorized, statusOf(null))
        assertEquals(HttpStatusCode.Unauthorized, statusOf(""))
    }

    @Test
    fun approval_unlocksATokenThatWorksOverHttp() = testApplication {
        val rig = Rig()
        application { rig.server.installInto(this) }
        rig.core.handshake(dictator)

        rig.store.approveApp(dictator)
        val reply = rig.core.handshake(dictator)

        assertEquals(HandshakeWire.STATUS_APPROVED, reply.status)
        assertEquals(4242, reply.port)
        assertEquals(HandshakeWire.API_VERSION, reply.apiVersion)
        assertTrue(reply.capabilitiesJson.contains("\"chat.completions\""), reply.capabilitiesJson)
        assertTrue(reply.capabilitiesJson.contains("\"test-model\""), reply.capabilitiesJson)
        assertEquals(HttpStatusCode.OK, statusOf(reply.token))
        // Approving did not notify again.
        assertEquals(1, rig.notified.size)
    }

    @Test
    fun denial_isSticky_andUndoReturnsToPending() = testApplication {
        val rig = Rig()
        application { rig.server.installInto(this) }
        rig.core.handshake(dictator)

        rig.store.denyApp(dictator)
        repeat(2) { assertEquals(HandshakeWire.STATUS_DENIED, rig.core.handshake(dictator).status) }
        assertEquals("", rig.core.handshake(dictator).token)

        rig.store.undoDenyApp(dictator)
        assertEquals(HandshakeWire.STATUS_PENDING_APPROVAL, rig.core.handshake(dictator).status)
        // Denying and retrying never re-notified.
        assertEquals(1, rig.notified.size)
    }

    @Test
    fun revoking_cutsALiveSession_andTheNextHandshakeIsPending() = testApplication {
        val rig = Rig()
        application { rig.server.installInto(this) }
        rig.core.handshake(dictator)
        rig.store.approveApp(dictator)
        val live = rig.core.handshake(dictator)
        assertEquals(HttpStatusCode.OK, statusOf(live.token))

        rig.store.revokeApproval(dictator)

        // Immediately, not when the token expires: the SDK sees 401 and re-handshakes.
        assertEquals(HttpStatusCode.Unauthorized, statusOf(live.token))
        assertEquals(HandshakeWire.STATUS_PENDING_APPROVAL, rig.core.handshake(dictator).status)
    }

    @Test
    fun denyingAnApprovedApp_alsoCutsItsLiveSession() = testApplication {
        val rig = Rig()
        application { rig.server.installInto(this) }
        rig.core.handshake(dictator)
        rig.store.approveApp(dictator)
        val live = rig.core.handshake(dictator)

        rig.store.denyApp(dictator)

        assertEquals(HttpStatusCode.Unauthorized, statusOf(live.token))
        assertEquals(HandshakeWire.STATUS_DENIED, rig.core.handshake(dictator).status)
    }

    @Test
    fun twoApprovedApps_holdIndependentTokens() = testApplication {
        val rig = Rig()
        application { rig.server.installInto(this) }
        for (pkg in listOf(dictator, agent)) {
            rig.core.handshake(pkg)
            rig.store.approveApp(pkg)
        }

        val dictatorSession = rig.core.handshake(dictator)
        val agentSession = rig.core.handshake(agent)

        assertNotEquals(dictatorSession.token, agentSession.token)
        // The second app's handshake must not evict the first's session.
        assertEquals(HttpStatusCode.OK, statusOf(dictatorSession.token))
        assertEquals(HttpStatusCode.OK, statusOf(agentSession.token))

        // Revoking one leaves the other alone.
        rig.store.revokeApproval(dictator)
        assertEquals(HttpStatusCode.Unauthorized, statusOf(dictatorSession.token))
        assertEquals(HttpStatusCode.OK, statusOf(agentSession.token))
    }

    @Test
    fun rehandshake_replacesThatAppsOwnPreviousToken() = testApplication {
        val rig = Rig()
        application { rig.server.installInto(this) }
        rig.core.handshake(dictator)
        rig.store.approveApp(dictator)

        val before = rig.core.handshake(dictator)
        val after = rig.core.handshake(dictator)

        // RFC-0103: a client that reconnects gets a fresh token; the stale one stops working.
        assertNotEquals(before.token, after.token)
        assertEquals(HttpStatusCode.Unauthorized, statusOf(before.token))
        assertEquals(HttpStatusCode.OK, statusOf(after.token))
    }

    @Test
    fun anUnresolvableCaller_isDenied_notRecorded() = testApplication {
        val rig = Rig()
        application { rig.server.installInto(this) }

        val reply = rig.core.handshake(null)

        assertEquals(HandshakeWire.STATUS_DENIED, reply.status)
        assertEquals(emptyList(), rig.store.listAllApprovals())
        assertTrue(rig.notified.isEmpty())
    }

    @Test
    fun enginesOwnToken_isNotSubjectToAppApproval() = testApplication {
        val rig = Rig()
        application { rig.server.installInto(this) }
        val own = rig.tokens.generateNewToken()

        // Test Chat inside Engine's own process needs no approval row.
        assertEquals(HttpStatusCode.OK, statusOf(own.token))

        // And an app's handshake does not evict it.
        rig.core.handshake(dictator)
        rig.store.approveApp(dictator)
        rig.core.handshake(dictator)
        assertEquals(HttpStatusCode.OK, statusOf(own.token))
    }

    @Test
    fun approvedButServerNotBound_failsTheHandshakeInsteadOfHandingOutAPortOfZero() = testApplication {
        val rig = Rig()
        val unbound = HandshakeCore(rig.store, rig.tokens, boundPort = { null }, capabilities = { Capabilities(emptyList(), emptyList()) })
        unbound.handshake(dictator)
        rig.store.approveApp(dictator)

        val failure = runCatching { unbound.handshake(dictator) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, "expected IllegalStateException, got $failure")
    }
}
