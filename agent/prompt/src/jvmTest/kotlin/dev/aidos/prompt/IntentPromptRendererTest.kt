package dev.aidos.prompt

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentCheckKind
import dev.aidos.intent.IntentGranularity
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.Turn
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IntentPromptRendererTest {

    private fun fakeModel() = object : ModelAdapter {
        override val providerId = "test"
        override val modelId = "mobile-local"
        override val modelVersion = "1.0"
        override val contextWindow = 8192
        override val isLocal = true
        override fun supportsNativeToolCalls() = false
        override suspend fun invoke(request: ModelRequest) =
            Result.failure<ModelResponse>(UnsupportedOperationException("unused"))
    }

    private fun nodeRecord(
        id: String,
        title: String,
        type: IntentNodeType = IntentNodeType.ATOMIC_TASK,
        actorKind: IntentActorKind = IntentActorKind.AGENT,
        granularity: IntentGranularity = IntentGranularity.ATOMIC,
        scopePaths: List<String> = emptyList(),
        estimatedSteps: Int? = null,
        checkKind: IntentCheckKind? = null,
        checkSpec: String? = null,
        dueAt: String? = null,
    ) = IntentNodeRecord(
        id = id,
        projectId = "proj1",
        type = type,
        title = title,
        actorKind = actorKind,
        granularity = granularity,
        targetProfile = IntentTargetProfile.MOBILE,
        minLocality = IntentMinLocality.LOCAL_ONLY,
        estimatedSteps = estimatedSteps,
        scopePaths = scopePaths,
        checkKind = checkKind,
        checkSpec = checkSpec,
        dueAt = dueAt,
        createdAt = "2025-01-01T00:00:00Z",
        createdByKind = "USER",
        createdById = "user1",
        modifiedAt = "2025-01-01T00:00:00Z",
        modifiedByKind = "USER",
        modifiedById = "user1",
    )

    @Test
    fun `renders scoped atomic coding task constraints criteria and fenced human handoff into system turn`() {
        val targetView = IntentNodeStatusView(
            node = nodeRecord(
                id = "leaf-auth",
                title = "Fix null token expiry in Auth.kt",
                estimatedSteps = 5,
                scopePaths = listOf("src/Auth.kt", "src/AuthTest.kt"),
            ),
            derivedStatus = DerivedStatus.IN_PROGRESS,
            effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
            unmetDependencyIds = emptyList(),
            activeRunIds = listOf("run-1"),
            pendingPlatformChecks = true,
        )
        val constraint = nodeRecord(
            id = "c-1",
            title = "Keep public API signature unchanged",
            type = IntentNodeType.CONSTRAINT,
        )
        val criterion = nodeRecord(
            id = "ac-1",
            title = "Auth unit suite passes",
            type = IntentNodeType.ACCEPTANCE_CRITERION,
            checkKind = IntentCheckKind.SHELL_COMMAND,
            checkSpec = "./gradlew :auth:jvmTest",
        )
        val humanFollowup = IntentNodeStatusView(
            node = nodeRecord(
                id = "human-review",
                title = "Approve mobile release notes",
                actorKind = IntentActorKind.HUMAN,
                dueAt = "2025-01-02T12:00:00Z",
            ),
            derivedStatus = DerivedStatus.BLOCKED,
            effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
            unmetDependencyIds = listOf("leaf-auth"),
            activeRunIds = emptyList(),
            pendingPlatformChecks = false,
        )

        val rendered = IntentPromptRenderer.renderTargetContext(
            targetView = targetView,
            constraints = listOf(constraint),
            acceptanceCriteria = listOf(criterion),
            humanHandoffs = listOf(humanFollowup),
        )

        assertTrue(rendered.contains("[INTENT GRAPH]"))
        assertTrue(rendered.contains("Step budget: 5 steps max"))
        assertTrue(rendered.contains("- src/Auth.kt"))
        assertTrue(rendered.contains("Keep public API signature unchanged"))
        assertTrue(rendered.contains("[UNMET] Auth unit suite passes"))
        assertTrue(rendered.contains("READ-ONLY CONTEXT — do NOT execute on the user's behalf"))
        assertTrue(rendered.contains("Approve mobile release notes"))

        val assembled = PromptAssembler().assemble(
            AssemblyRequest(
                model = fakeModel(),
                userMessage = "Proceed with leaf-auth",
                intentInstructions = rendered,
            ),
        )
        assertIs<AssemblyResult.Ok>(assembled)
        val systemTurn = assembled.pkg.request.messages.filterIsInstance<Turn.System>().first()
        assertTrue(systemTurn.content.contains("[INTENT GRAPH]"))
        assertTrue(systemTurn.content.contains("- src/Auth.kt"))
    }

    @Test
    fun `rejects rendering HUMAN task as autonomous agent target`() {
        val humanView = IntentNodeStatusView(
            node = nodeRecord(
                id = "buy-milk",
                title = "Buy milk",
                actorKind = IntentActorKind.HUMAN,
            ),
            derivedStatus = DerivedStatus.NOT_STARTED,
            effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
            unmetDependencyIds = emptyList(),
            activeRunIds = emptyList(),
            pendingPlatformChecks = false,
        )

        assertFailsWith<IllegalArgumentException> {
            IntentPromptRenderer.renderTargetContext(humanView)
        }
    }
}
