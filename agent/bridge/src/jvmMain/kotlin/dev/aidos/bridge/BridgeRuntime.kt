package dev.aidos.bridge

import dev.aidos.settings.EgressPolicy
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * A [ProjectPolicyResolver] the daemon fills in as it opens projects: a `personal`/`life` project
 * is always air-gapped (RFC-0047), anything else follows the `routing.remote_egress` the daemon
 * resolved for it (`never` and `ask` both gate).
 */
class RegisteredProjectPolicyResolver : ProjectPolicyResolver {
    private val policies = ConcurrentHashMap<String, EgressPolicy>()

    fun register(projectId: String, policy: EgressPolicy) {
        policies[projectId] = policy
    }

    override fun isEgressAirGapped(projectId: String, projectType: String): Boolean =
        DefaultProjectPolicyResolver(policies).isEgressAirGapped(projectId, projectType)
}

/**
 * The one runtime object behind RFC-0113: the daemon builds it once and every project's
 * `message:send` and `bridge:*` tools share it.
 *
 * - [coordinator] is the egress gate: parks, approves and transmits (§4.3).
 * - [dispatcher] answers blinded queries and takes ingress proposals (§4.1, §4.2).
 * - [transport] is the messenger adapter; `null` means no channel is configured and nothing is sent.
 *
 * The human's decision never travels through the model. A parked tool call is approved or denied by
 * a person through the approval path (CLI `approve-run`, or the Human Egress Gate Card), and only
 * that path calls [recordHumanApproval], which issues the grant the send then consumes.
 */
class BridgeRuntime(
    val policies: RegisteredProjectPolicyResolver = RegisteredProjectPolicyResolver(),
    val coordinator: EgressGateCoordinator = EgressGateCoordinator(policyResolver = policies),
    val dispatcher: BlindedQueryDispatcher,
    val transport: OutboundMessageTransport? = null,
    private val clock: () -> Instant = { Clock.System.now() },
    /** Side-channel limit (RFC-0113 §2.2): at most this many blinded queries per actor per window. */
    private val maxQueriesPerWindow: Int = 30,
    private val queryWindow: Duration = 60.seconds,
    private val grantTtl: Duration = 60.seconds,
    /** Disclosure bound (RFC-0113 §4.2 point 4): blinded queries one target project answers in [budgetWindow], all actors together. */
    private val maxQueriesPerProjectPerDay: Int = 500,
    private val budgetWindow: Duration = 24.hours,
) {
    private val approvedGrants = ConcurrentHashMap<String, EgressGrant>()
    // Both guarded by queryTimes' monitor.
    private val queryTimes = HashMap<String, ArrayDeque<Instant>>()
    private val budgetTimes = HashMap<String, ArrayDeque<Instant>>()

    /** Why a blinded query is not admitted. */
    enum class QueryRefusal { RATE_LIMITED, BUDGET_EXHAUSTED }

    /** True while [actorId] is within the blinded-query rate limit; records the query when it is. */
    fun admitQuery(actorId: String): Boolean = admitQuery(actorId, targetProjectId = null) == null

    /**
     * Admits one blinded query, or says why not. Two limits apply (RFC-0113 §4.2): the rate limit
     * per actor, and the target project's daily budget, which every actor shares, so a new session
     * does not start a new count. A refused query counts against neither.
     */
    fun admitQuery(actorId: String, targetProjectId: String?): QueryRefusal? = synchronized(queryTimes) {
        val now = clock()
        queryTimes.values.forEach { times -> while (times.isNotEmpty() && now - times.first() > queryWindow) times.removeFirst() }
        queryTimes.values.removeIf { it.isEmpty() }
        budgetTimes.values.forEach { times -> while (times.isNotEmpty() && now - times.first() > budgetWindow) times.removeFirst() }
        budgetTimes.values.removeIf { it.isEmpty() }

        if ((queryTimes[actorId]?.size ?: 0) >= maxQueriesPerWindow) return QueryRefusal.RATE_LIMITED
        if (targetProjectId != null && (budgetTimes[targetProjectId]?.size ?: 0) >= maxQueriesPerProjectPerDay) {
            return QueryRefusal.BUDGET_EXHAUSTED
        }
        queryTimes.getOrPut(actorId) { ArrayDeque() }.addLast(now)
        if (targetProjectId != null) budgetTimes.getOrPut(targetProjectId) { ArrayDeque() }.addLast(now)
        null
    }

    /**
     * Builds the outbound request for a `message:send` call's arguments. The id is a hash of the
     * whole request, so the approval, the grant and the later send agree on what "this message" is
     * without any identifier the model supplies.
     */
    fun requestFor(projectId: String, projectType: String, arguments: JsonObject, runId: String? = null): OutboundMessageRequest {
        val channel = arguments.getValue("channel").jsonPrimitive.content
        val recipient = arguments.getValue("recipient").jsonPrimitive.content
        val payload = arguments.getValue("payload").jsonPrimitive.content
        val reason = arguments["reason"]?.jsonPrimitive?.content
        return OutboundMessageRequest(
            id = EgressGateCoordinator.sha256Hex("$projectId\u0000$channel\u0000$recipient\u0000$payload").take(32),
            projectId = projectId,
            projectType = projectType,
            channel = channel,
            recipient = recipient,
            payload = payload,
            reason = reason,
            runId = runId,
        )
    }

    /** Parks [request] if its project is air-gapped, so the approval queue shows it (§4.3 step 2). */
    fun park(request: OutboundMessageRequest): ParkResult = coordinator.interceptAndPark(request)

    /**
     * Called by the approval path when a person approved the parked `message:send` call. Issues the
     * single-use grant bound to this exact payload and recipient (§4.3 step 4). Idempotent per
     * message: approving the same pending message twice yields one grant.
     */
    fun recordHumanApproval(request: OutboundMessageRequest, approverUserId: String = "user"): EgressGrant? {
        reparkIfSettled(request)
        val parked = coordinator.getParkedMessage(request.id) ?: return null
        val grant = if (parked.status == ParkedMessageStatus.PARKED) {
            coordinator.approve(request.id, approverUserId, grantTtl)
        } else {
            return approvedGrants[request.id]
        }
        approvedGrants[request.id] = grant
        return grant
    }

    /** Records a refusal: the message is marked rejected and no grant exists for it. */
    fun recordHumanRejection(request: OutboundMessageRequest, reason: String = "Rejected by user") {
        reparkIfSettled(request)
        if (coordinator.getParkedMessage(request.id)?.status == ParkedMessageStatus.PARKED) {
            coordinator.reject(request.id, reason)
        }
        approvedGrants.remove(request.id)
    }

    /** An identical message sent (or refused) earlier is a new decision this time, not a replay of the old one. */
    private fun reparkIfSettled(request: OutboundMessageRequest) {
        val existing = coordinator.getParkedMessage(request.id)
        if (existing == null || existing.status == ParkedMessageStatus.TRANSMITTED ||
            existing.status == ParkedMessageStatus.REJECTED ||
            existing.status == ParkedMessageStatus.DELIVERY_FAILED
        ) {
            park(request)
        }
    }

    /** Removes and returns the grant for [request]. A grant is consumed by asking, even if the send then fails. */
    fun takeGrant(request: OutboundMessageRequest): EgressGrant? = approvedGrants.remove(request.id)
}
