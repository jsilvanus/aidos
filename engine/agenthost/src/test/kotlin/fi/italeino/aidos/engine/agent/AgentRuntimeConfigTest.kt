package fi.italeino.aidos.engine.agent

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RecordingAdapter(override val modelId: String) : ModelAdapter {
    override val providerId = "test"
    override val modelVersion = "1"
    override val contextWindow = 4096
    override val isLocal = true
    override fun supportsNativeToolCalls() = true

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> =
        Result.success(ModelResponse(listOf(TextOutput("ok:$modelId")), StopReason.END_TURN, null, null))
}

class AgentRuntimeConfigTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun routingDefaultsAndPerRunOverridesPopulateRunSummaryAndRequest() = runBlocking {
        var seenRequest: AgentTurnRequest? = null
        val runtime = AgentRuntime(
            scope = scope,
            adapterFor = { req ->
                seenRequest = req
                RecordingAdapter(req.model)
            },
        )
        runtime.setRoutingConfig(
            AgentRoutingConfigDto(
                mainModelId = "qwen-main",
                defaultRunModelId = "qwen-fast",
                enforcedLocality = "trusted_cluster",
                guardianPolicy = "GUARDIAN",
                decisionModelId = "laya-multilingual",
                guardianLlmModelId = "guardian-8b",
            )
        )
        runtime.setRunLaunchConfig(
            AgentRunLaunchConfigDto(
                guardianPolicyOverride = "MANUAL",
                runModelOverride = "qwen-override",
                localityOverride = "local_only",
            )
        )

        val response = runtime.start(
            owner = "app-a",
            request = AgentTurnRequest(
                model = "",
                message = "hello",
                async = true,
            ),
        )
        assertEquals(AgentTurnResponse.RUNNING, response.status)
        assertEquals("qwen-override", seenRequest?.model)
        assertEquals("local_only", seenRequest?.locality)
        assertEquals("MANUAL", seenRequest?.taintApprovalPolicy)

        val summaries = runtime.listAll()
        assertEquals(1, summaries.size)
        val summary = summaries.first()
        assertEquals("qwen-override", summary.model)
        assertEquals("MANUAL", summary.guardianPolicy)
        assertEquals("local_only", summary.enforcedLocality)
        assertEquals("local_only", summary.locality)
    }

    @Test
    fun explicitRequestFieldsAndStrictestLocalityArePreserved() = runBlocking {
        val runtime = AgentRuntime(
            scope = scope,
            adapterFor = { req -> RecordingAdapter(req.model) },
        )
        runtime.setRoutingConfig(
            AgentRoutingConfigDto(
                mainModelId = "qwen-main",
                defaultRunModelId = "qwen-fast",
                enforcedLocality = "local_only",
                guardianPolicy = "GUARDIAN",
            )
        )

        runtime.start(
            owner = "app-b",
            request = AgentTurnRequest(
                model = "custom-worker",
                message = "check",
                locality = "any",
                async = true,
                modelChosenByMainModel = true,
            ),
        )

        val summary = runtime.listAll().first()
        assertEquals("custom-worker", summary.model)
        assertEquals("GUARDIAN", summary.guardianPolicy)
        assertTrue(summary.modelChosenByMainModel)
        assertEquals("local_only", summary.enforcedLocality)
        assertEquals("local_only", summary.locality)
    }
}
