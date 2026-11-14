package dev.aidos.bridge

import dev.aidos.broker.AuditLog
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentGranularity
import dev.aidos.intent.IntentGraphRepository
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import dev.aidos.kernel.IdGenerator
import dev.aidos.kernel.TrustLevel
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Sensitive calendar event stored in a Life project.
 *
 * Invariant: [title], [location], [notes], and [attendees] are air-gapped confidential
 * personal data and MUST NEVER be leaked across the bridge.
 */
data class CalendarEvent(
    val id: String,
    val title: String,
    val startTime: Instant,
    val endTime: Instant,
    val location: String? = null,
    val notes: String? = null,
    val attendees: List<String> = emptyList(),
)

/**
 * Personal contact record stored in a Life project.
 */
data class ContactCircleEntry(
    val handle: String,
    val name: String,
    val circles: Set<String>,
    val notes: String? = null,
)

/**
 * Todo or shopping list item stored in a Life project.
 */
data class LifeTodoItem(
    val id: String,
    val listTag: String,
    val title: String,
    val notes: String? = null,
)

/**
 * Abstraction for local air-gapped Life project state storage (calendar, contacts, lists).
 */
interface LifeProjectStore {
    fun getCalendarEvents(projectId: String): List<CalendarEvent>
    fun getContacts(projectId: String): List<ContactCircleEntry>
    fun getListItems(projectId: String, listTag: String): List<LifeTodoItem>
}

/**
 * In-memory implementation of [LifeProjectStore] for runtime evaluation and testing.
 */
class InMemoryLifeProjectStore : LifeProjectStore {
    private val events = mutableMapOf<String, MutableList<CalendarEvent>>()
    private val contacts = mutableMapOf<String, MutableList<ContactCircleEntry>>()
    private val listItems = mutableMapOf<String, MutableList<LifeTodoItem>>()

    fun addCalendarEvent(projectId: String, event: CalendarEvent) {
        events.getOrPut(projectId) { mutableListOf() }.add(event)
    }

    fun addContact(projectId: String, contact: ContactCircleEntry) {
        contacts.getOrPut(projectId) { mutableListOf() }.add(contact)
    }

    fun addListItem(projectId: String, item: LifeTodoItem) {
        listItems.getOrPut(projectId) { mutableListOf() }.add(item)
    }

    override fun getCalendarEvents(projectId: String): List<CalendarEvent> =
        events[projectId]?.toList() ?: emptyList()

    override fun getContacts(projectId: String): List<ContactCircleEntry> =
        contacts[projectId]?.toList() ?: emptyList()

    override fun getListItems(projectId: String, listTag: String): List<LifeTodoItem> =
        listItems[projectId]?.filter { it.listTag.equals(listTag, ignoreCase = true) } ?: emptyList()
}

/**
 * Audit event sink for blinded bridge invocations (RFC-0113 §4.2, RFC-0037).
 */
interface BridgeAuditLogger {
    fun logBlindedQuery(
        id: String,
        projectId: String,
        querySchema: String,
        actor: ActorRef,
        detailJson: String,
        nowIso: String,
    )
}

/**
 * In-memory audit sink that captures logged blinded query executions.
 */
class InMemoryBridgeAuditLogger : BridgeAuditLogger {
    data class Entry(
        val id: String,
        val projectId: String,
        val querySchema: String,
        val actor: ActorRef,
        val detailJson: String,
        val nowIso: String,
    )

    private val entries = mutableListOf<Entry>()

    override fun logBlindedQuery(
        id: String,
        projectId: String,
        querySchema: String,
        actor: ActorRef,
        detailJson: String,
        nowIso: String,
    ) {
        entries.add(Entry(id, projectId, querySchema, actor, detailJson, nowIso))
    }

    fun getEntries(): List<Entry> = entries.toList()
    fun count(): Int = entries.size
}

/**
 * Bridge audit logger adapter connecting to Aidos' persistent [AuditLog].
 */
class PersistentBridgeAuditLogger(private val auditLog: AuditLog) : BridgeAuditLogger {
    override fun logBlindedQuery(
        id: String,
        projectId: String,
        querySchema: String,
        actor: ActorRef,
        detailJson: String,
        nowIso: String,
    ) {
        auditLog.write(
            id = id,
            projectId = projectId,
            kind = "BLINDED_QUERY_EXECUTED",
            actor = actor,
            subjectRef = querySchema,
            detailJson = detailJson,
            nowIso = nowIso,
        )
    }
}

/**
 * Ingestion handler for cross-domain inward intent proposals.
 */
interface IngressTaskHandler {
    fun ingestProposal(proposal: IngressIntentProposal, actor: ActorRef): IntentNodeRecord
}

/**
 * Default implementation of [IngressTaskHandler] creating unblocked atomic tasks.
 */
class DefaultIngressTaskHandler(
    private val repository: IntentGraphRepository? = null,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
    private val nowIso: () -> String = { Clock.System.now().toString() },
) : IngressTaskHandler {
    private val inMemoryTasks = mutableMapOf<String, IntentNodeRecord>()

    override fun ingestProposal(proposal: IngressIntentProposal, actor: ActorRef): IntentNodeRecord {
        val taskId = idGen()
        val now = nowIso()
        val record = IntentNodeRecord(
            id = taskId,
            projectId = proposal.targetProjectId,
            type = IntentNodeType.ATOMIC_TASK,
            title = proposal.summary,
            description = "Ingress from channel '${proposal.sourceChannel}' by sender '${proposal.senderId}'",
            priority = 100,
            lifecycle = IntentLifecycle.ACTIVE,
            parentId = proposal.suggestedParentGoalId,
            actorKind = IntentActorKind.AGENT,
            dispatchPolicy = NodeDispatchPolicy.AUTO_WHEN_READY,
            granularity = IntentGranularity.ATOMIC,
            targetProfile = null,
            minLocality = null,
            estimatedSteps = null,
            scopePaths = emptyList(),
            dueAt = null,
            recurrenceJson = null,
            assertedStatus = DerivedStatus.NOT_STARTED,
            assertedAt = now,
            assertedByUserId = null,
            assertionNote = "Ingress intent proposal accepted into Life project",
            checkKind = null,
            checkSpec = null,
            verificationMet = null,
            verifiedByKind = null,
            verifiedById = null,
            verifiedAt = null,
            createdAt = now,
            createdByKind = actor.kind.name,
            createdById = actor.id,
            modifiedAt = now,
            modifiedByKind = actor.kind.name,
            modifiedById = actor.id,
            tags = listOf("ingress", proposal.sourceChannel),
            rowVersion = 1,
        )

        if (repository != null) {
            repository.createNode(
                projectId = proposal.targetProjectId,
                id = taskId,
                type = IntentNodeType.ATOMIC_TASK,
                title = proposal.summary,
                description = record.description,
                priority = 100,
                parentId = proposal.suggestedParentGoalId,
                actorKind = IntentActorKind.AGENT,
                dispatchPolicy = NodeDispatchPolicy.AUTO_WHEN_READY,
                granularity = IntentGranularity.ATOMIC,
                tags = record.tags,
                createdBy = actor,
            )
        }
        inMemoryTasks[taskId] = record
        return record
    }

    fun getTask(id: String): IntentNodeRecord? = inMemoryTasks[id]
}

/**
 * Result of ingesting an ingress intent proposal.
 */
data class IngressProposalResult(
    val isSuccess: Boolean,
    val task: IntentNodeRecord,
    val isUnblocked: Boolean,
)

/**
 * Dispatches blinded queries and ingress proposals safely to target projects (RFC-0113).
 *
 * Invariants:
 * 1. ZERO READ ACCESS: External callers cannot read raw database tables or private entities.
 * 2. NO LEAKAGE: Responses contain ONLY typed boolean/slot metadata; never sensitive titles or notes.
 * 3. TAINT NEUTRALITY: Query responses carry no CONFIDENTIAL or PROJECT taint.
 * 4. MONOTONIC AUDIT: All queries are logged to the audit trail with schema and querying actor.
 */
class BlindedQueryDispatcher(
    private val stateStore: LifeProjectStore,
    private val ingressHandler: IngressTaskHandler = DefaultIngressTaskHandler(),
    private val auditLogger: BridgeAuditLogger = InMemoryBridgeAuditLogger(),
    private val idGenerator: IdGenerator = object : IdGenerator {
        override fun next(): String = UUID.randomUUID().toString()
    },
    private val clock: () -> Instant = { Clock.System.now() },
) {
    /**
     * Dispatches a strongly-typed blinded query to the target Life project.
     *
     * Evaluates the query locally against the project's air-gapped state and returns a
     * taint-neutral result.
     */
    fun <R : BlindedResponse> dispatch(
        query: BlindedQuery<R>,
        actor: ActorRef = ActorRef(ActorKind.SESSION, "blinded-query-session"),
    ): BlindedQueryResult<R> {
        val schemaName = query::class.simpleName ?: "BlindedQuery"
        val auditId = idGenerator.next()
        val nowIso = clock().toString()

        auditLogger.logBlindedQuery(
            id = auditId,
            projectId = query.targetProjectId,
            querySchema = schemaName,
            actor = actor,
            detailJson = """{"schema":"$schemaName","targetProjectId":"${query.targetProjectId}"}""",
            nowIso = nowIso,
        )

        val response: BlindedResponse = when (query) {
            is BlindedQuery.CheckAvailability -> evaluateAvailability(query)
            is BlindedQuery.VerifyContactCircle -> evaluateContactCircle(query)
            is BlindedQuery.CheckListContains -> evaluateListContains(query)
        }

        @Suppress("UNCHECKED_CAST")
        return BlindedQueryResult(
            response = response as R,
            trustLevel = TrustLevel.TRUSTED,
            egressTaint = EgressTaintLevel.CLEAN,
            auditEventLogged = true,
        )
    }

    /**
     * Dispatches an incoming message intent proposal inward into the Life project.
     *
     * Safely constructs an unblocked atomic task without requiring remote egress.
     */
    fun dispatchIngressProposal(
        proposal: IngressIntentProposal,
        actor: ActorRef = ActorRef(ActorKind.WORKER, "inbound-messaging-worker"),
    ): IngressProposalResult {
        val createdTask = ingressHandler.ingestProposal(proposal, actor)
        // An unblocked task has AUTO_WHEN_READY dispatch policy and NOT_STARTED asserted status
        // with no unmet dependencies.
        val isUnblocked = createdTask.dispatchPolicy == NodeDispatchPolicy.AUTO_WHEN_READY &&
            createdTask.assertedStatus == DerivedStatus.NOT_STARTED

        return IngressProposalResult(
            isSuccess = true,
            task = createdTask,
            isUnblocked = isUnblocked,
        )
    }

    private fun evaluateAvailability(query: BlindedQuery.CheckAvailability): AvailabilityResponse {
        val events = stateStore.getCalendarEvents(query.targetProjectId)
        val conflicts = events.filter { event ->
            event.startTime < query.endTime && event.endTime > query.startTime
        }
        val isBusy = conflicts.isNotEmpty()
        val nextSlot = if (isBusy) {
            conflicts.maxOf { it.endTime }
        } else {
            null
        }

        // Return ONLY boolean busy state and next available slot.
        // Strips any sensitive titles, notes, locations, attendees.
        return AvailabilityResponse(
            isSuccess = true,
            isBusy = isBusy,
            nextAvailableSlot = nextSlot,
        )
    }

    private fun evaluateContactCircle(query: BlindedQuery.VerifyContactCircle): ContactCircleResponse {
        val contacts = stateStore.getContacts(query.targetProjectId)
        val matchingContact = contacts.find { it.handle.equals(query.contactHandle.trim(), ignoreCase = true) }
        val isMember = matchingContact?.circles?.any { it.equals(query.requiredCircle.trim(), ignoreCase = true) } == true

        return ContactCircleResponse(
            isSuccess = true,
            isMember = isMember,
        )
    }

    private fun evaluateListContains(query: BlindedQuery.CheckListContains): ListContainsResponse {
        val items = stateStore.getListItems(query.targetProjectId, query.listTag)
        val targetNormalized = query.normalizedItemName.trim().lowercase()
        val alreadyPresent = items.any { it.title.trim().lowercase() == targetNormalized }

        return ListContainsResponse(
            isSuccess = true,
            alreadyPresent = alreadyPresent,
        )
    }
}
