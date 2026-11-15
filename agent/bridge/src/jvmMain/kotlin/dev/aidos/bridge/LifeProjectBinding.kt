package dev.aidos.bridge

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.broker.AuditLog
import dev.aidos.intent.IntentGraphRepository
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.kernel.ActorRef
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes

/**
 * Resolves the state database of a project the bridge is asked about (RFC-0113 §6).
 *
 * The bridge never keeps a driver of its own: the daemon owns project databases, and a project
 * that is not open or does not exist simply yields `null`, which the callers report as a failed
 * query rather than guessing (a missing project must never read as an empty calendar).
 */
fun interface ProjectDriverResolver {
    fun driverFor(projectId: String): SqlDriver?
}

/**
 * [LifeProjectStore] backed by the target project's real Intent Graph (RFC-0110), so a blinded
 * query is answered from what the project actually holds, not from a test double.
 *
 * Storage convention (documented in RFC-0113 §4.2, evaluated here and nowhere else):
 *
 * | Kind          | Intent node                                                                  |
 * |---------------|------------------------------------------------------------------------------|
 * | Calendar event| tag `calendar`; `dueAt` is the start; optional tag `duration:<minutes>` (60) |
 * | Contact       | tag `contact`; tag `handle:<handle>`; one tag `circle:<name>` per circle      |
 * | List item     | tag `list:<listTag>`; the node title is the item                              |
 *
 * Archived nodes are ignored, and so are list items already marked DONE. Titles, descriptions and
 * notes are read into memory only so the evaluator can compare them; they never reach a response
 * type, which has no field for them.
 */
class IntentGraphLifeProjectStore(
    private val drivers: ProjectDriverResolver,
    private val defaultEventDuration: kotlin.time.Duration = 60.minutes,
) : LifeProjectStore {

    private fun nodes(projectId: String): List<IntentNodeRecord> {
        // Not open means not answerable. Reading it as "no events" would turn into "you are free".
        val driver = drivers.driverFor(projectId)
            ?: throw IllegalStateException("Target project '$projectId' is not available.")
        return IntentGraphRepository(driver, idGen = { error("read-only") }, nowIso = { Clock.System.now().toString() })
            .listNodes(projectId, includeArchived = false)
            .filter { it.lifecycle == IntentLifecycle.ACTIVE }
    }

    override fun getCalendarEvents(projectId: String): List<CalendarEvent> =
        nodes(projectId).filter { "calendar" in it.tags }.mapNotNull { node ->
            val start = node.dueAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null
            val minutes = node.tags.firstOrNull { it.startsWith("duration:") }
                ?.removePrefix("duration:")?.toLongOrNull()?.takeIf { it > 0 }
            val length = minutes?.minutes ?: defaultEventDuration
            CalendarEvent(
                id = node.id,
                title = node.title,
                startTime = start,
                endTime = start + length,
                notes = node.description,
            )
        }

    override fun getContacts(projectId: String): List<ContactCircleEntry> =
        nodes(projectId).filter { "contact" in it.tags }.mapNotNull { node ->
            val handle = node.tags.firstOrNull { it.startsWith("handle:") }?.removePrefix("handle:")
                ?: return@mapNotNull null
            ContactCircleEntry(
                handle = handle,
                name = node.title,
                circles = node.tags.filter { it.startsWith("circle:") }.map { it.removePrefix("circle:") }.toSet(),
                notes = node.description,
            )
        }

    override fun getListItems(projectId: String, listTag: String): List<LifeTodoItem> =
        nodes(projectId)
            .filter { node -> node.tags.any { it.equals("list:$listTag", ignoreCase = true) } }
            .filter { it.assertedStatus != dev.aidos.intent.DerivedStatus.DONE }
            .map { LifeTodoItem(id = it.id, listTag = listTag, title = it.title, notes = it.description) }
}

/**
 * Writes `BLINDED_QUERY_EXECUTED` rows into the audit log of the **queried** project (RFC-0113
 * §4.2, "Monotonic Audit"): the Life project's owner sees who asked it what, in its own history.
 */
class ProjectAuditBridgeLogger(
    private val drivers: ProjectDriverResolver,
    private val deviceId: String,
) : BridgeAuditLogger {
    override fun logBlindedQuery(
        id: String,
        projectId: String,
        querySchema: String,
        actor: ActorRef,
        detailJson: String,
        nowIso: String,
    ) {
        val driver = drivers.driverFor(projectId) ?: return
        PersistentBridgeAuditLogger(AuditLog(driver, deviceId))
            .logBlindedQuery(id, projectId, querySchema, actor, detailJson, nowIso)
    }
}

/**
 * Ingests proposals into the target project's Intent Graph. The node id is derived from the
 * proposal, so a crash-and-retry of the (never-silently-retried, but re-drivable) tool call lands on
 * the same node instead of duplicating it.
 */
class ProjectIngressTaskHandler(
    private val drivers: ProjectDriverResolver,
    private val nowIso: () -> String = { Clock.System.now().toString() },
) : IngressTaskHandler {
    override fun ingestProposal(proposal: IngressIntentProposal, actor: ActorRef): IntentNodeRecord {
        val driver = drivers.driverFor(proposal.targetProjectId)
            ?: throw IllegalStateException("Target project '${proposal.targetProjectId}' is not available.")
        val repository = IntentGraphRepository(driver, idGen = { error("id is supplied") }, nowIso = nowIso)
        val id = deterministicNodeId(proposal)
        repository.getNode(proposal.targetProjectId, id)?.let { return it }
        // Manual start: text from outside must never start an agent run by itself (RFC-0113 §5).
        val handler = DefaultIngressTaskHandler(
            repository = repository,
            idGen = { id },
            nowIso = nowIso,
            dispatchPolicy = NodeDispatchPolicy.MANUAL_START,
        )
        handler.ingestProposal(proposal, actor)
        return repository.getNode(proposal.targetProjectId, id)!!
    }

    companion object {
        fun deterministicNodeId(proposal: IngressIntentProposal): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(
                listOf(proposal.targetProjectId, proposal.sourceChannel, proposal.senderId, proposal.summary)
                    .joinToString("\u0000").toByteArray(Charsets.UTF_8),
            )
            return "ingress-" + digest.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * The databases of the projects a daemon has opened, filled in as projects open. A project that was
 * never opened has no entry, and a blinded query against it fails instead of answering.
 */
class RegisteredProjectDrivers : ProjectDriverResolver {
    private val drivers = java.util.concurrent.ConcurrentHashMap<String, SqlDriver>()

    fun register(projectId: String, driver: SqlDriver) {
        drivers[projectId] = driver
    }

    override fun driverFor(projectId: String): SqlDriver? = drivers[projectId]
}
