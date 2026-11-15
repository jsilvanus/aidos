package dev.aidos.routing

import dev.aidos.kernel.BatteryState
import dev.aidos.kernel.Budget
import dev.aidos.kernel.DeviceSignals
import dev.aidos.kernel.ExecutionWindow
import dev.aidos.kernel.MemoryPressureLevel
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.RoutingContext
import dev.aidos.kernel.RoutingDecision
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ThermalState
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Usage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ModelAdmissionPolicyTest {

    private fun fakeAdapter(id: String) = object : ModelAdapter {
        override val providerId: String = "test"
        override val modelId: String = id
        override val modelVersion: String = "1.0"
        override val contextWindow: Int = 4096
        override val isLocal: Boolean = true
        override fun supportsNativeToolCalls(): Boolean = false
        override suspend fun invoke(request: ModelRequest): Result<ModelResponse> = Result.success(
            ModelResponse(
                outputs = listOf(TextOutput("ok")),
                stopReason = StopReason.END_TURN,
                usage = Usage(inputTokens = 0, outputTokens = 0, totalTokens = 0),
                model = ModelRef(id = id, version = "1.0"),
            )
        )
    }

    @Test
    fun `admission enforces 1_3x memory headroom, thermal state, and battery floor for large models`() {
        val policy = ModelAdmissionPolicy(batteryFloorPercent = 20, largeModelThresholdBytes = 1_000_000_000L)
        val okSignals = DeviceSignals(MemoryPressureLevel.NONE, BatteryState(50, isCharging = false), ThermalState.NORMAL)

        // 1 GB model requires >= 1.3 GB available.
        assertIs<ModelAdmissionPolicy.AdmissionDecision.Denied>(
            policy.evaluate(1_000_000_000L, 1_200_000_000L, okSignals)
        )
        assertIs<ModelAdmissionPolicy.AdmissionDecision.Admitted>(
            policy.evaluate(1_000_000_000L, 1_300_000_000L, okSignals)
        )

        // Thermal throttling denies even with plenty of RAM.
        val hotSignals = okSignals.copy(thermal = ThermalState.THROTTLING)
        assertIs<ModelAdmissionPolicy.AdmissionDecision.Denied>(
            policy.evaluate(100_000_000L, 4_000_000_000L, hotSignals)
        )

        // Low battery & not charging denies large models, but allows if charging.
        val lowBatt = DeviceSignals(MemoryPressureLevel.NONE, BatteryState(10, isCharging = false), ThermalState.NORMAL)
        assertIs<ModelAdmissionPolicy.AdmissionDecision.Denied>(
            policy.evaluate(1_000_000_000L, 4_000_000_000L, lowBatt)
        )
        val chargingLowBatt = lowBatt.copy(battery = BatteryState(10, isCharging = true))
        assertIs<ModelAdmissionPolicy.AdmissionDecision.Admitted>(
            policy.evaluate(1_000_000_000L, 4_000_000_000L, chargingLowBatt)
        )
    }

    @Test
    fun `PolicyInferenceRouter falls back to smaller admitted local model when large model fails admission`() = runBlocking {
        val largeLocal = fakeAdapter("llama-7b")
        val smallLocal = fakeAdapter("llama-1b")
        val router = PolicyInferenceRouter(
            policy = RoutingPolicy(allowRemote = false),
            localAdapters = listOf(largeLocal, smallLocal),
            admissionPolicy = ModelAdmissionPolicy(),
            deviceSignalsProvider = {
                DeviceSignals(MemoryPressureLevel.NONE, BatteryState(80, isCharging = true), ThermalState.NORMAL)
            },
            availableMemoryBytesProvider = { 1_000_000_000L }, // 1 GB available
            modelWorkingSetBytes = mapOf(
                "llama-7b" to 4_000_000_000L, // needs 5.2 GB -> denied
                "llama-1b" to 500_000_000L,   // needs 650 MB -> admitted
            ),
        )

        val decision = router.select(
            ModelKind.LLM,
            RoutingContext(
                profile = PlatformProfile.DESKTOP,
                networkAvailable = false,
                budgetRemaining = Budget(),
                runTaint = TrustLevel.TRUSTED,
                executionWindow = object : ExecutionWindow {
                    override fun remainingMillis(): Long? = null
                    override fun permitsLocalInference(): Boolean = true
                },
            ),
        )

        val local = assertIs<RoutingDecision.Local>(decision)
        assertEquals("llama-1b", local.adapter.modelId)
    }
}
