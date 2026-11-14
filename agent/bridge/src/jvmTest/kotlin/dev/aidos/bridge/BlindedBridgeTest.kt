package dev.aidos.bridge

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.kernel.TrustLevel
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BlindedBridgeTest {

    private val json = Json { prettyPrint = false }

    @Test
    fun testIngressProposalCreatesUnblockedTask() {
        val store = InMemoryLifeProjectStore()
        val auditLogger = InMemoryBridgeAuditLogger()
        val dispatcher = BlindedQueryDispatcher(
            stateStore = store,
            auditLogger = auditLogger,
        )

        val proposal = IngressIntentProposal(
            sourceChannel = "matrix",
            senderId = "@alice:matrix.org",
            targetProjectId = "life-project-uuid-001",
            summary = "Pick up organic oat milk from supermarket",
            suggestedParentGoalId = "goal-groceries-uuid",
        )

        val outcome = dispatcher.dispatchIngressProposal(proposal)

        assertTrue(outcome.isSuccess, "Ingress proposal should succeed")
        assertTrue(outcome.isUnblocked, "Ingress task must be unblocked")

        val task = outcome.task
        assertEquals("life-project-uuid-001", task.projectId)
        assertEquals("Pick up organic oat milk from supermarket", task.title)
        assertEquals(IntentNodeType.ATOMIC_TASK, task.type)
        assertEquals(IntentActorKind.AGENT, task.actorKind)
        assertEquals(NodeDispatchPolicy.AUTO_WHEN_READY, task.dispatchPolicy)
        assertEquals(DerivedStatus.NOT_STARTED, task.assertedStatus)
        assertEquals("goal-groceries-uuid", task.parentId)
        assertTrue(task.tags.contains("ingress"))
        assertTrue(task.tags.contains("matrix"))
        assertTrue(task.description?.contains("@alice:matrix.org") == true)
    }

    @Test
    fun testCheckAvailabilityReturnsOnlyBooleanBusyStatusAndNeverSensitiveDetails() {
        val store = InMemoryLifeProjectStore()
        val auditLogger = InMemoryBridgeAuditLogger()
        val dispatcher = BlindedQueryDispatcher(
            stateStore = store,
            auditLogger = auditLogger,
        )

        val projectId = "life-project-uuid-002"

        // Store private calendar event with sensitive health / personal data
        val secretEvent = CalendarEvent(
            id = "evt-secret-999",
            title = "Confidential Oncology Screening with Dr. Jekyll",
            startTime = Instant.parse("2026-11-14T19:00:00Z"),
            endTime = Instant.parse("2026-11-14T21:00:00Z"),
            location = "Secret Specialty Clinic 4, Helsinki",
            notes = "Patient biopsy review. Strictly private health record.",
            attendees = listOf("Dr. Jekyll", "patient@private.org"),
        )
        store.addCalendarEvent(projectId, secretEvent)

        // 1. Query during busy conflict window
        val busyQuery = BlindedQuery.CheckAvailability(
            targetProjectId = projectId,
            startTime = Instant.parse("2026-11-14T19:30:00Z"),
            endTime = Instant.parse("2026-11-14T20:30:00Z"),
        )
        val busyResult = dispatcher.dispatch(busyQuery)

        assertTrue(busyResult.response.isSuccess)
        assertTrue(busyResult.response.isBusy, "Should report busy during conflicting event")
        assertEquals(
            Instant.parse("2026-11-14T21:00:00Z"),
            busyResult.response.nextAvailableSlot,
        )

        // Verify that AvailabilityResponse contains ONLY isSuccess, isBusy, nextAvailableSlot.
        // Serialized JSON must contain zero sensitive leakages.
        val serializedJson = json.encodeToString(busyResult.response)
        assertFalse(serializedJson.contains("Confidential"), "Must not leak 'Confidential'")
        assertFalse(serializedJson.contains("Oncology"), "Must not leak 'Oncology'")
        assertFalse(serializedJson.contains("Jekyll"), "Must not leak doctor name")
        assertFalse(serializedJson.contains("Secret"), "Must not leak location")
        assertFalse(serializedJson.contains("biopsy"), "Must not leak notes")
        assertFalse(serializedJson.contains("patient@private.org"), "Must not leak attendees")

        // 2. Query during free window
        val freeQuery = BlindedQuery.CheckAvailability(
            targetProjectId = projectId,
            startTime = Instant.parse("2026-11-14T21:30:00Z"),
            endTime = Instant.parse("2026-11-14T22:30:00Z"),
        )
        val freeResult = dispatcher.dispatch(freeQuery)

        assertTrue(freeResult.response.isSuccess)
        assertFalse(freeResult.response.isBusy, "Should report not busy when slot is free")
        assertNull(freeResult.response.nextAvailableSlot)
    }

    @Test
    fun testBlindedQueriesAreTaintNeutral() {
        val store = InMemoryLifeProjectStore()
        val auditLogger = InMemoryBridgeAuditLogger()
        val dispatcher = BlindedQueryDispatcher(
            stateStore = store,
            auditLogger = auditLogger,
        )
        val projectId = "life-project-uuid-003"

        // Set up contacts and lists
        store.addContact(
            projectId,
            ContactCircleEntry(
                handle = "@bob:matrix.org",
                name = "Bob Smith",
                circles = setOf("Family", "CloseFriends"),
                notes = "Personal emergency contact & family executor",
            ),
        )

        store.addListItem(
            projectId,
            LifeTodoItem(
                id = "item-1",
                listTag = "groceries",
                title = "Oat Milk",
                notes = "Special brand: Oatly organic only",
            ),
        )

        // 1. Availability query taint neutrality
        val availResult = dispatcher.dispatch(
            BlindedQuery.CheckAvailability(
                targetProjectId = projectId,
                startTime = Instant.parse("2026-11-14T10:00:00Z"),
                endTime = Instant.parse("2026-11-14T11:00:00Z"),
            ),
        )
        assertTrue(availResult.isTaintNeutral, "CheckAvailability must be taint-neutral")
        assertEquals(TrustLevel.TRUSTED, availResult.trustLevel)
        assertEquals(EgressTaintLevel.CLEAN, availResult.egressTaint)
        assertNotEquals(TrustLevel.PROJECT, availResult.trustLevel)
        assertNotEquals(TrustLevel.UNTRUSTED, availResult.trustLevel)
        assertNotEquals(EgressTaintLevel.CONFIDENTIAL, availResult.egressTaint)
        assertNotEquals(EgressTaintLevel.PROJECT, availResult.egressTaint)

        // 2. Contact circle query
        val circleResult = dispatcher.dispatch(
            BlindedQuery.VerifyContactCircle(
                targetProjectId = projectId,
                contactHandle = "@bob:matrix.org",
                requiredCircle = "Family",
            ),
        )
        assertTrue(circleResult.isTaintNeutral, "VerifyContactCircle must be taint-neutral")
        assertTrue(circleResult.response.isMember)
        assertEquals(TrustLevel.TRUSTED, circleResult.trustLevel)
        assertEquals(EgressTaintLevel.CLEAN, circleResult.egressTaint)

        val nonMemberResult = dispatcher.dispatch(
            BlindedQuery.VerifyContactCircle(
                targetProjectId = projectId,
                contactHandle = "@bob:matrix.org",
                requiredCircle = "Coworkers",
            ),
        )
        assertFalse(nonMemberResult.response.isMember)
        assertTrue(nonMemberResult.isTaintNeutral)

        // 3. List contains query
        val listResult = dispatcher.dispatch(
            BlindedQuery.CheckListContains(
                targetProjectId = projectId,
                listTag = "groceries",
                normalizedItemName = "oat milk",
            ),
        )
        assertTrue(listResult.isTaintNeutral, "CheckListContains must be taint-neutral")
        assertTrue(listResult.response.alreadyPresent)

        val absentResult = dispatcher.dispatch(
            BlindedQuery.CheckListContains(
                targetProjectId = projectId,
                listTag = "groceries",
                normalizedItemName = "chocolate ice cream",
            ),
        )
        assertFalse(absentResult.response.alreadyPresent)
        assertTrue(absentResult.isTaintNeutral)

        // 4. Verify monotonic audit trail
        assertEquals(5, auditLogger.count(), "All 5 queries must be logged in audit trail")
        val loggedSchemas = auditLogger.getEntries().map { it.querySchema }
        assertTrue(loggedSchemas.contains("CheckAvailability"))
        assertTrue(loggedSchemas.contains("VerifyContactCircle"))
        assertTrue(loggedSchemas.contains("CheckListContains"))
    }

    @Test
    fun testEgressGatingLifeProjectMessageBlockedAndParks() {
        val coordinator = EgressGateCoordinator()

        val lifeMessage = OutboundMessageRequest(
            id = "msg-life-001",
            projectId = "life-personal-001",
            projectType = "personal",
            channel = "SMS",
            recipient = "Alice (+358 40 123 4567)",
            payload = "I can't make it to dinner at 7 due to my doctor appointment, but 8:30 works.",
            reason = "Response to incoming inquiry",
            taintLevel = "HIGH",
        )

        // 1. Intercept and park
        val parkResult = coordinator.interceptAndPark(lifeMessage)
        assertTrue(parkResult is ParkResult.Parked, "Message from Life project must be parked")
        val parked = parkResult.parkedMessage

        assertEquals("msg-life-001", parked.id)
        assertEquals(ParkedMessageStatus.PARKED, parked.status)

        val pending = coordinator.getPendingApprovals("life-personal-001")
        assertEquals(1, pending.size)
        assertEquals("msg-life-001", pending.first().id)

        // 2. Direct transmission attempt without approval MUST be blocked
        val blockedResult = coordinator.transmit(lifeMessage, grant = null)
        assertTrue(blockedResult is TransmissionResult.Blocked)
        assertTrue(
            blockedResult.reason.contains("blocked"),
            "Should explicitly report blocked status",
        )
        assertEquals(0, coordinator.getTransmittedMessages().size, "Zero bytes should leave device")
    }

    @Test
    fun testEgressGatingUserApprovalIssuesSingleUseGrantAndTransmits() {
        var currentTime = Instant.parse("2026-11-14T12:00:00Z")
        val coordinator = EgressGateCoordinator(clock = { currentTime })

        val lifeMessage = OutboundMessageRequest(
            id = "msg-life-002",
            projectId = "life-personal-002",
            projectType = "personal",
            channel = "Matrix",
            recipient = "@alice:matrix.org",
            payload = "Confirming meeting at 8:30 PM.",
        )

        coordinator.interceptAndPark(lifeMessage)

        // 1. Human user approves message
        val grant = coordinator.approve(
            messageId = "msg-life-002",
            approverUserId = "user-alice-owner",
            ttl = 60.seconds,
        )

        assertNotNull(grant)
        assertEquals("msg-life-002", grant.messageId)
        assertEquals("@alice:matrix.org", grant.recipient)
        assertEquals(EgressGateCoordinator.sha256Hex(lifeMessage.payload), grant.payloadHash)
        assertTrue(grant.signature.isNotBlank())
        assertEquals(Instant.parse("2026-11-14T12:01:00Z"), grant.expiresAt)

        val parked = coordinator.getParkedMessage("msg-life-002")
        assertEquals(ParkedMessageStatus.APPROVED, parked?.status)

        // 2. Transmit message with valid single-use declassification grant
        val transmitOutcome = coordinator.transmit(lifeMessage, grant)
        assertTrue(transmitOutcome is TransmissionResult.Transmitted)

        assertEquals("msg-life-002", transmitOutcome.messageId)
        assertEquals("@alice:matrix.org", transmitOutcome.recipient)
        assertEquals(1, coordinator.getTransmittedMessages().size)

        val updatedParked = coordinator.getParkedMessage("msg-life-002")
        assertEquals(ParkedMessageStatus.TRANSMITTED, updatedParked?.status)

        // 3. REPLAY ATTEMPT: Attempting to reuse the single-use grant must be rejected
        val replayOutcome = coordinator.transmit(lifeMessage, grant)
        assertTrue(replayOutcome is TransmissionResult.Rejected)
        assertTrue(
            replayOutcome.reason.contains("already used"),
            "Reused grant must be rejected",
        )
        assertEquals(1, coordinator.getTransmittedMessages().size, "Replay must not transmit second message")
    }

    @Test
    fun testEgressGatingRejectionPreventsTransmission() {
        val coordinator = EgressGateCoordinator()

        val lifeMessage = OutboundMessageRequest(
            id = "msg-life-003",
            projectId = "life-personal-003",
            projectType = "personal",
            channel = "SMS",
            recipient = "Alice (+358 40 123 4567)",
            payload = "Personal journal entry quote...",
        )

        coordinator.interceptAndPark(lifeMessage)

        val rejected = coordinator.reject(
            messageId = "msg-life-003",
            reason = "Do not share personal context externally.",
        )
        assertEquals(ParkedMessageStatus.REJECTED, rejected.status)
        assertEquals("Do not share personal context externally.", rejected.rejectionReason)

        assertEquals(0, coordinator.getPendingApprovals("life-personal-003").size)

        // Attempting to transmit a rejected message without grant fails
        val outcome = coordinator.transmit(lifeMessage, grant = null)
        assertTrue(outcome is TransmissionResult.Blocked)
        assertEquals(0, coordinator.getTransmittedMessages().size)
    }

    @Test
    fun testEgressGatingTamperedPayloadOrRecipientRejected() {
        val coordinator = EgressGateCoordinator()

        val originalMessage = OutboundMessageRequest(
            id = "msg-life-004",
            projectId = "life-personal-004",
            projectType = "personal",
            channel = "SMS",
            recipient = "Alice (+358 40 123 4567)",
            payload = "Meet you at 8:30.",
        )

        coordinator.interceptAndPark(originalMessage)
        val grant = coordinator.approve("msg-life-004")

        // 1. Tampered payload
        val tamperedPayloadMessage = originalMessage.copy(
            payload = "Meet you at 8:30. Also here is my credit card number: 4111...",
        )
        val payloadMismatchResult = coordinator.transmit(tamperedPayloadMessage, grant)
        assertTrue(payloadMismatchResult is TransmissionResult.Rejected)
        assertTrue(payloadMismatchResult.reason.contains("Payload hash mismatch"))

        // 2. Tampered recipient
        val tamperedRecipientMessage = originalMessage.copy(
            recipient = "Attacker (+1 555 0199)",
        )
        val recipientMismatchResult = coordinator.transmit(tamperedRecipientMessage, grant)
        assertTrue(recipientMismatchResult is TransmissionResult.Rejected)
        assertTrue(recipientMismatchResult.reason.contains("Recipient mismatch"))

        // 3. Tampered signature
        val tamperedSignatureGrant = grant.copy(signature = grant.signature.reversed())
        val sigMismatchResult = coordinator.transmit(originalMessage, tamperedSignatureGrant)
        assertTrue(sigMismatchResult is TransmissionResult.Rejected)
        assertTrue(sigMismatchResult.reason.contains("Invalid cryptographic signature"))
    }

    @Test
    fun testEgressGatingExpiredGrantRejected() {
        var currentTime = Instant.parse("2026-11-14T12:00:00Z")
        val coordinator = EgressGateCoordinator(clock = { currentTime })

        val lifeMessage = OutboundMessageRequest(
            id = "msg-life-005",
            projectId = "life-personal-005",
            projectType = "personal",
            channel = "SMS",
            recipient = "Alice (+358 40 123 4567)",
            payload = "Meet you at 8:30.",
        )

        coordinator.interceptAndPark(lifeMessage)
        val grant = coordinator.approve("msg-life-005", ttl = 10.seconds)

        // Advance clock past expiration
        currentTime = Instant.parse("2026-11-14T12:00:15Z")

        val expiredResult = coordinator.transmit(lifeMessage, grant)
        assertTrue(expiredResult is TransmissionResult.Rejected)
        assertTrue(expiredResult.reason.contains("Grant expired"))
        assertEquals(0, coordinator.getTransmittedMessages().size)
    }

    @Test
    fun testNonLifeProjectAllowsDirectEgress() {
        val coordinator = EgressGateCoordinator()

        val codingMessage = OutboundMessageRequest(
            id = "msg-coding-001",
            projectId = "coding-repo-001",
            projectType = "coding",
            channel = "Matrix",
            recipient = "@dev-team:matrix.org",
            payload = "CI build passed for PR #42.",
        )

        val parkResult = coordinator.interceptAndPark(codingMessage)
        assertTrue(parkResult is ParkResult.AllowedDirectly)

        val transmitResult = coordinator.transmit(codingMessage, grant = null)
        assertTrue(transmitResult is TransmissionResult.Transmitted)
        assertEquals(1, coordinator.getTransmittedMessages().size)
    }
}
