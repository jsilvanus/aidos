package dev.aidos.bridge

import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.DenialReason
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.MutationScope
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.ResourceHandle
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolAvailability
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.AidosError
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import dev.aidos.kernel.ErrorClass
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.days

/** Looks up what a capability the caller holds actually allows; `null` when it is not a bridge grant. */
fun interface BridgeScopeLookup {
    suspend fun scopeOf(capabilityId: CapabilityId): CapabilityScope.Bridge?
}

private val allPlatforms = ToolAvailability(
    profiles = PlatformProfile.entries.toSet(),
    tier = AvailabilityTier.UNIVERSAL,
)

private fun obj(vararg required: Pair<String, String>, optional: List<Pair<String, String>> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                (required.toList() + optional).forEach { (name, type) ->
                    put(name, buildJsonObject { put("type", type) })
                }
            },
        )
        put("required", JsonArray(required.map { JsonPrimitive(it.first) }))
    }

private fun text(handle: ResourceHandle, outcome: ToolOutcome, message: String, trust: TrustLevel = TrustLevel.TRUSTED) =
    ToolCallResult(handle.capabilityId.value, outcome, listOf(ContentBlock.Text(message)), trust)

/**
 * RFC-0113 §4.2 and §4.1: the only operations a messaging session has into a zero-egress project.
 *
 * Every operation needs a [Permission.BLINDED_QUERY] grant whose [CapabilityScope.Bridge] names the
 * schema and the target project. None of them returns anything but the typed response fields
 * (there is no field for a title, a location or a note), so the result is TRUSTED and taint-neutral
 * and a Run that read an untrusted inbound message can still ask them.
 */
class BlindedQueryTool(
    private val bridge: BridgeRuntime,
    private val scopes: BridgeScopeLookup,
    /** The calling session; the audit trail records it as the querying actor. */
    private val actorId: String,
) : Tool {
    override val id: String = TOOL_ID
    override val version: String = "1.0.0"

    private fun descriptor(name: String, title: String, description: String, schema: JsonObject, effect: EffectKind) =
        ToolDescriptor(
            name = name,
            title = title,
            description = description,
            inputSchema = schema,
            effect = effect,
            requiredPermission = Permission.BLINDED_QUERY,
            recoveryClass = RecoveryClass.IDEMPOTENT,
            availability = allPlatforms,
        )

    private val descriptors = listOf(
        descriptor(
            CHECK_AVAILABILITY,
            "Check availability",
            "Ask a private project whether a time window is free. Returns only busy or free and the next free time; never what the event is.",
            obj("target_project_id" to "string", "start" to "string", "end" to "string"),
            EffectKind.Read,
        ),
        descriptor(
            VERIFY_CONTACT_CIRCLE,
            "Verify contact circle",
            "Ask a private project whether a contact handle belongs to a named circle (for example Family). Returns only yes or no.",
            obj("target_project_id" to "string", "contact_handle" to "string", "required_circle" to "string"),
            EffectKind.Read,
        ),
        descriptor(
            CHECK_LIST_CONTAINS,
            "Check list contains",
            "Ask a private project whether an item is already on a list (for example groceries). Returns only yes or no.",
            obj("target_project_id" to "string", "list_tag" to "string", "item" to "string"),
            EffectKind.Read,
        ),
        // Writing inward into a more confidential domain does not breach confidentiality (Biba and
        // Bell-LaPadula, RFC-0113 §4.1), and the result is a proposal in the target's own graph,
        // not an action there, so it is classed as an in-project mutation and stays allowed under
        // taint, which an inbound message always carries.
        descriptor(
            PROPOSE_TASK,
            "Propose task",
            "Propose a task to a private project from an incoming message. The project decides whether to act on it.",
            obj(
                "target_project_id" to "string", "source_channel" to "string", "sender_id" to "string", "summary" to "string",
                optional = listOf("suggested_parent_goal_id" to "string"),
            ),
            EffectKind.Mutate(MutationScope.IN_PROJECT, reversible = true),
        ),
    )

    override fun operations(): List<ToolDescriptor> = descriptors

    override suspend fun execute(handle: ResourceHandle, operation: String, arguments: JsonObject): ToolCallResult {
        val scope = scopes.scopeOf(handle.capabilityId)
            ?: return text(handle, ToolOutcome.Denied(DenialReason.SCOPE_MISMATCH), "denied: the capability is not a bridge grant")
        val target = arguments.str("target_project_id")
        val schema = SCHEMA_OF.getValue(operation)
        if (schema !in scope.allowedQuerySchemas || target !in scope.targetProjectIds) {
            return text(
                handle, ToolOutcome.Denied(DenialReason.SCOPE_MISMATCH),
                "denied: this session may not run $schema against that project",
            )
        }
        // Proposals write into the project and answer nothing, so they do not spend its budget.
        when (bridge.admitQuery(actorId, target.takeIf { operation in SCHEMA_ANSWERS })) {
            BridgeRuntime.QueryRefusal.RATE_LIMITED -> return text(
                handle, ToolOutcome.Denied(DenialReason.CONSTRAINT_EXCEEDED),
                "denied: too many blinded queries in a short time; try again later",
            )
            BridgeRuntime.QueryRefusal.BUDGET_EXHAUSTED -> return text(
                handle, ToolOutcome.Denied(DenialReason.BUDGET_EXHAUSTED),
                "denied: that project has answered its daily number of blinded queries",
            )
            null -> Unit
        }
        val actor = ActorRef(ActorKind.SESSION, actorId)
        return try {
            when (operation) {
                CHECK_AVAILABILITY -> {
                    val start = Instant.parse(arguments.str("start"))
                    val end = Instant.parse(arguments.str("end"))
                    if (end <= start || end - start > MAX_WINDOW) {
                        return text(handle, ToolOutcome.Failed(bad("window must be positive and at most 14 days")), "invalid window")
                    }
                    val result = bridge.dispatcher.dispatch(BlindedQuery.CheckAvailability(target, start, end), actor)
                    val r = result.response
                    ok(handle, buildJsonObject {
                        put("is_busy", r.isBusy)
                        r.nextAvailableSlot?.let { put("next_available_slot", it.toString()) }
                    })
                }
                VERIFY_CONTACT_CIRCLE -> {
                    val r = bridge.dispatcher.dispatch(
                        BlindedQuery.VerifyContactCircle(target, arguments.str("contact_handle"), arguments.str("required_circle")), actor,
                    ).response
                    ok(handle, buildJsonObject { put("is_member", r.isMember) })
                }
                CHECK_LIST_CONTAINS -> {
                    val r = bridge.dispatcher.dispatch(
                        BlindedQuery.CheckListContains(target, arguments.str("list_tag"), arguments.str("item")), actor,
                    ).response
                    ok(handle, buildJsonObject { put("already_present", r.alreadyPresent) })
                }
                else -> {
                    val proposal = IngressIntentProposal(
                        sourceChannel = arguments.str("source_channel").take(32),
                        senderId = arguments.str("sender_id").take(128),
                        targetProjectId = target,
                        summary = sanitizeInbound(arguments.str("summary")),
                        suggestedParentGoalId = arguments["suggested_parent_goal_id"]?.jsonPrimitive?.content,
                    )
                    if (proposal.summary.isBlank()) {
                        return text(handle, ToolOutcome.Failed(bad("summary is empty")), "invalid proposal")
                    }
                    bridge.dispatcher.dispatchIngressProposal(proposal, ActorRef(ActorKind.WORKER, actorId))
                    ok(handle, buildJsonObject { put("proposed", true) })
                }
            }
        } catch (e: IllegalArgumentException) {
            text(handle, ToolOutcome.Failed(bad(e.message ?: "invalid arguments")), "invalid arguments")
        } catch (e: IllegalStateException) {
            // Target project not open, or no longer there: a failed query, never a guessed answer.
            text(handle, ToolOutcome.Failed(AidosError("bridge.target_unavailable", ErrorClass.UNAVAILABLE, "target project is not available")), "target project is not available")
        }
    }

    override suspend fun preview(handle: ResourceHandle, operation: String, arguments: JsonObject): Result<Preview> =
        Result.success(Preview.Description("Ask ${arguments["target_project_id"]?.jsonPrimitive?.content ?: "a project"} ($operation)"))

    override suspend fun cancel(operationId: String) = Unit

    private fun ok(handle: ResourceHandle, body: JsonObject) = text(handle, ToolOutcome.Ok, body.toString())
    private fun bad(message: String) = AidosError("bridge.invalid_arguments", ErrorClass.INVALID_INPUT, message)

    companion object {
        const val TOOL_ID = "bridge"
        const val CHECK_AVAILABILITY = "bridge:check_availability"
        const val VERIFY_CONTACT_CIRCLE = "bridge:verify_contact_circle"
        const val CHECK_LIST_CONTAINS = "bridge:check_list_contains"
        const val PROPOSE_TASK = "bridge:propose_task"

        /** The operations that answer a question about the target project, as opposed to writing into it. */
        val SCHEMA_ANSWERS = setOf(CHECK_AVAILABILITY, VERIFY_CONTACT_CIRCLE, CHECK_LIST_CONTAINS)
        private val MAX_WINDOW = 14.days

        /** The schema name a [CapabilityScope.Bridge] grant lists for each operation. */
        val SCHEMA_OF = mapOf(
            CHECK_AVAILABILITY to "CheckAvailability",
            VERIFY_CONTACT_CIRCLE to "VerifyContactCircle",
            CHECK_LIST_CONTAINS to "CheckListContains",
            PROPOSE_TASK to "IngressIntentProposal",
        )

        /** Inbound text is data: bounded, control characters removed (RFC-0113 §4.1 step 2). */
        fun sanitizeInbound(raw: String): String =
            raw.filter { !it.isISOControl() || it == ' ' }.trim().take(280)
    }
}

private fun JsonObject.str(key: String): String =
    this[key]?.jsonPrimitive?.content ?: throw IllegalArgumentException("missing '$key'")

/**
 * RFC-0113 §4.3: sends a message over an external channel.
 *
 * From a zero-egress project (`personal`, or `routing.remote_egress` of `never`/`ask`) nothing is
 * sent unless the call carries a person's single-use declassification grant for exactly this payload
 * and recipient. Without one the tool answers [DenialReason.REQUIRES_APPROVAL], which is what makes
 * the Run park; the approval path issues the grant ([BridgeRuntime.recordHumanApproval]) and the
 * re-driven call finds it here. The grant is required even if a standing capability would let the
 * call through, so a loosened grant cannot open the gate.
 */
class MessageSendTool(
    private val bridge: BridgeRuntime,
    private val scopes: BridgeScopeLookup,
    private val projectId: String,
    private val projectType: () -> String,
) : Tool {
    override val id: String = TOOL_ID
    override val version: String = "1.0.0"

    private val descriptor = ToolDescriptor(
        name = SEND,
        title = "Send message",
        description = "Send a message to a person over an external channel such as SMS, Matrix or email. " +
            "In a private project the user is shown the message and must approve it first.",
        inputSchema = obj(
            "channel" to "string", "recipient" to "string", "payload" to "string",
            optional = listOf("reason" to "string"),
        ),
        effect = EffectKind.Egress("message"),
        requiredPermission = Permission.MESSAGE_SEND,
        // A sent message cannot be unsent or observed, so a crash mid-send is reported, not retried.
        recoveryClass = RecoveryClass.UNSAFE,
        availability = ToolAvailability(PlatformProfile.entries.toSet(), AvailabilityTier.NETWORKED, requiresNetwork = true),
    )

    override fun operations(): List<ToolDescriptor> = listOf(descriptor)

    override suspend fun execute(handle: ResourceHandle, operation: String, arguments: JsonObject): ToolCallResult {
        val scope = scopes.scopeOf(handle.capabilityId)
            ?: return text(handle, ToolOutcome.Denied(DenialReason.SCOPE_MISMATCH), "denied: the capability is not a messaging grant")
        val request = bridge.requestFor(projectId, projectType(), arguments)
        if (request.channel !in scope.allowedChannels) {
            return text(handle, ToolOutcome.Denied(DenialReason.SCOPE_MISMATCH), "denied: this session may not use channel '${request.channel}'")
        }
        val transport = bridge.transport
            ?: return text(
                handle,
                ToolOutcome.Failed(AidosError("message.no_transport", ErrorClass.UNAVAILABLE, "no messaging channel is configured")),
                "no messaging channel is configured; nothing was sent",
            )

        val grant = bridge.takeGrant(request)
        if (grant == null && bridge.policies.isEgressAirGapped(request.projectId, request.projectType)) {
            bridge.park(request)
            return text(
                handle, ToolOutcome.Denied(DenialReason.REQUIRES_APPROVAL),
                "EGRESS_POLICY_PROHIBITED: this project does not send over the network without your approval; the message is waiting for it",
            )
        }

        return when (val result = bridge.coordinator.transmit(request, grant)) {
            is TransmissionResult.Transmitted -> {
                try {
                    transport.deliver(request.channel, request.recipient, request.payload)
                    text(handle, ToolOutcome.Ok, "message sent to ${request.recipient} via ${request.channel}")
                } catch (e: Exception) {
                    // The grant is spent; a retry needs a new approval, which is the safe side.
                    // But nothing left the device, and the record must say so.
                    bridge.coordinator.deliveryFailed(result.transmissionId)
                    text(
                        handle,
                        ToolOutcome.Failed(AidosError("message.delivery_failed", ErrorClass.INDETERMINATE, "the channel did not confirm delivery")),
                        "the channel did not confirm delivery",
                    )
                }
            }
            is TransmissionResult.Blocked ->
                text(handle, ToolOutcome.Denied(DenialReason.REQUIRES_APPROVAL), result.reason)
            is TransmissionResult.Rejected ->
                text(handle, ToolOutcome.Denied(DenialReason.REQUIRES_APPROVAL), result.reason)
        }
    }

    override suspend fun preview(handle: ResourceHandle, operation: String, arguments: JsonObject): Result<Preview> =
        Result.success(
            Preview.Description(
                "Send to ${arguments["recipient"]?.jsonPrimitive?.content} via ${arguments["channel"]?.jsonPrimitive?.content}: " +
                    (arguments["payload"]?.jsonPrimitive?.content ?: ""),
            ),
        )

    override suspend fun cancel(operationId: String) = Unit

    companion object {
        const val TOOL_ID = "message"
        const val SEND = "message:send"
    }
}
