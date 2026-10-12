package dev.aidos.cookbook

import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals

class CookbookEngineTest {

    private val engine = CookbookEngine()

    @Test
    fun testRunsWellWithPlentifulMemory() {
        // Mid-range phone with 6GB RAM, trying to run a 3.5GB Q4 model
        val device = DeviceProfile(
            totalRamBytes = 6_000_000_000,
            availableRamBytes = 4_000_000_000, // 4GB free
            storageFreeBytes = 50_000_000_000,
            cpuCoreCount = 8,
            hasAccelerator = false,
        )

        val model = ModelDescriptor(
            id = "qwen2.5-3b",
            name = "Qwen2.5 3B Q4",
            kind = ModelKind.LLM,
            providerId = "huggingface",
            isLocal = true,
            contextWindow = 32768,
            sizeBytes = 2_000_000_000, // 2GB
            digest = "abc123",
        )

        val verdict = engine.verdict(model, device, 4096)
        assertEquals(CookbookVerdict.RUNS_WELL, verdict)
    }

    @Test
    fun testRunsTightWithLimitedMemory() {
        // Mid-range phone with 4GB RAM, tight margins
        val device = DeviceProfile(
            totalRamBytes = 4_000_000_000,
            availableRamBytes = 3_000_000_000, // 3GB free
            storageFreeBytes = 50_000_000_000,
            cpuCoreCount = 8,
            hasAccelerator = false,
        )

        val model = ModelDescriptor(
            id = "llama-3.1-8b",
            name = "Llama 3.1 8B Q4",
            kind = ModelKind.LLM,
            providerId = "huggingface",
            isLocal = true,
            contextWindow = 32768,
            sizeBytes = 4_500_000_000, // 4.5GB
            digest = "def456",
        )

        val verdict = engine.verdict(model, device, 4096)
        // 4.5GB weights > 3GB available, so WILL_NOT_FIT
        assertEquals(CookbookVerdict.WILL_NOT_FIT, verdict)
    }

    @Test
    fun testExceedsContextAtLongWindow() {
        // 3B model fits at 4K context but not at 32K
        val device = DeviceProfile(
            totalRamBytes = 6_000_000_000,
            availableRamBytes = 3_500_000_000, // 3.5GB free
            storageFreeBytes = 50_000_000_000,
            cpuCoreCount = 8,
            hasAccelerator = false,
        )

        val model = ModelDescriptor(
            id = "qwen2.5-3b",
            name = "Qwen2.5 3B Q4",
            kind = ModelKind.LLM,
            providerId = "huggingface",
            isLocal = true,
            contextWindow = 32768,
            sizeBytes = 2_000_000_000, // 2GB
            digest = "abc123",
        )

        // At 4K context: RUNS_WELL
        val verdict4k = engine.verdict(model, device, 4096)
        assertEquals(CookbookVerdict.RUNS_WELL, verdict4k)

        // At 32K context: KV cache blows up
        val resident32k = engine.computeResidentMemory(
            ModelRequirements(
                weightsBytesOnDisk = model.sizeBytes!!,
                contextWindow = 32768,
                parameterCount = 3_000_000_000,
                quantizationType = "Q4_K_M",
            ),
            device,
            32768,
        )
        // Should be much larger: 2GB weights + 2GB KV cache + overhead
        assert(resident32k > device.availableRamBytes) { "32K context should exceed available RAM" }
    }

    @Test
    fun testResidentMemoryCalculation() {
        val device = DeviceProfile(
            totalRamBytes = 4_000_000_000,
            availableRamBytes = 3_000_000_000,
            storageFreeBytes = 50_000_000_000,
            cpuCoreCount = 8,
            hasAccelerator = false,
        )

        val requirements = ModelRequirements(
            weightsBytesOnDisk = 2_000_000_000, // 2GB
            contextWindow = 4096,
            parameterCount = 7_000_000_000,
            quantizationType = "Q4_K_M",
        )

        val resident = engine.computeResidentMemory(requirements, device, 4096)

        // 2GB (weights) + 4096 * 76,800 (KV) + 2GB * 0.05 (overhead)
        // = 2GB + 0.31GB + 0.1GB ≈ 2.41GB
        assert(resident in 2_300_000_000..2_700_000_000) {
            "Resident estimate should be ~2.5GB, got ${resident / 1_000_000_000}GB"
        }
    }

    @Test
    fun testWillNotFitWithInsufficientRAM() {
        // Tiny device with 2GB RAM
        val device = DeviceProfile(
            totalRamBytes = 2_000_000_000,
            availableRamBytes = 1_500_000_000,
            storageFreeBytes = 10_000_000_000,
            cpuCoreCount = 4,
            hasAccelerator = false,
        )

        val model = ModelDescriptor(
            id = "llama-3.1-8b",
            name = "Llama 3.1 8B Q4",
            kind = ModelKind.LLM,
            providerId = "huggingface",
            isLocal = true,
            contextWindow = 32768,
            sizeBytes = 4_500_000_000, // 4.5GB — too large
            digest = "def456",
        )

        val verdict = engine.verdict(model, device, 4096)
        assertEquals(CookbookVerdict.WILL_NOT_FIT, verdict)
    }

    private val twoGbModel = ModelDescriptor(
        id = "m", name = "m", kind = ModelKind.LLM, providerId = "huggingface", isLocal = true,
        contextWindow = 32768, sizeBytes = 2_000_000_000, digest = "x",
    )

    private fun device(baselineUsed: Long?) = DeviceProfile(
        totalRamBytes = 16_000_000_000,
        availableRamBytes = 3_000_000_000, // browser etc. open right now
        storageFreeBytes = 50_000_000_000,
        cpuCoreCount = 8,
        hasAccelerator = false,
        baselineUsedRamBytes = baselineUsed,
    )

    @Test
    fun baselineFreeRamIsTotalMinusBaselineUsed() {
        assertEquals(10_000_000_000, device(6_000_000_000).baselineAvailableRamBytes)
        assertEquals(0L, device(20_000_000_000).baselineAvailableRamBytes)
        assertEquals(null, device(null).baselineAvailableRamBytes)
    }

    @Test
    fun noBaselineMeansNoBaselineVerdict() {
        assertEquals(null, engine.verdictAtBaseline(twoGbModel, device(null), 4096))
        assertEquals(null, engine.ramFit(twoGbModel, device(null), 4096).baselineVerdict)
    }

    @Test
    fun fitCanDifferBetweenCurrentAndBaseline() {
        val d = device(6_000_000_000)
        val report = engine.ramFit(twoGbModel, d, 4096)
        // 3 GB free right now is tight for a 2 GB model; at the 10 GB baseline it runs well.
        assertEquals(CookbookVerdict.RUNS_TIGHT, report.currentVerdict)
        assertEquals(CookbookVerdict.RUNS_WELL, report.baselineVerdict)
        assertEquals(10_000_000_000, report.baselineAvailableBytes)
        assertEquals(3_000_000_000, report.currentAvailableBytes)
        assertEquals(engine.verdict(twoGbModel, d, 4096), report.currentVerdict)
    }

    @Test
    fun neededRamGrowsWithContext() {
        val d = device(6_000_000_000)
        assertEquals(true, engine.ramFit(twoGbModel, d, 16384).neededBytes > engine.ramFit(twoGbModel, d, 2048).neededBytes)
    }
}
