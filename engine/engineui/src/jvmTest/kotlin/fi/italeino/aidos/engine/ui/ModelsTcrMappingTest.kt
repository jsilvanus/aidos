package fi.italeino.aidos.engine.ui

import dev.aidos.kernel.ModelKind
import fi.italeino.aidos.engine.EngineState
import fi.italeino.aidos.engine.suggestions.SuggestedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import dev.aidos.tcr.engine.EngineState as TcrEngineState
import dev.aidos.tcr.engine.ModelFitVerdict as TcrModelFitVerdict
import dev.aidos.tcr.engine.ModelKind as TcrModelKind
import dev.aidos.tcr.engine.ModelLoadingStatus as TcrModelLoadingStatus

/**
 * The TCR keeps presentation copies of the Engine's Models types (it cannot depend on the host or
 * the kernel), so every enum constant and field must survive the mapping. A constant added on one
 * side fails here instead of in `valueOf` at runtime.
 */
class ModelsTcrMappingTest {

    @Test fun everyHostEnumConstantHasATcrCounterpart() {
        for (e in EngineState.entries) TcrEngineState.valueOf(e.name)
        for (e in ModelKind.entries) TcrModelKind.valueOf(e.name)
        for (e in ModelFitVerdict.entries) TcrModelFitVerdict.valueOf(e.name)
        for (e in ModelLoadingStatus.entries) TcrModelLoadingStatus.valueOf(e.name)
        assertEquals(EngineState.entries.size, TcrEngineState.entries.size)
        assertEquals(ModelKind.entries.size, TcrModelKind.entries.size)
        assertEquals(ModelFitVerdict.entries.size, TcrModelFitVerdict.entries.size)
        assertEquals(ModelLoadingStatus.entries.size, TcrModelLoadingStatus.entries.size)
    }

    @Test fun cookbookModelKeepsEveryField() {
        val m = CookbookModel(
            id = "a/b", name = "B", kind = "LLM", quantization = "Q4", sizeBytes = 12L, contextLength = 4096,
            fitVerdict = ModelFitVerdict.RUNS_TIGHT, isRunnable = false, tokensPerSecond = 3.5f, estimatedVramMB = 900,
        ).toTcr()
        assertEquals("a/b", m.id)
        assertEquals("Q4", m.quantization)
        assertEquals(12L, m.sizeBytes)
        assertEquals(4096, m.contextLength)
        assertEquals(TcrModelFitVerdict.RUNS_TIGHT, m.fitVerdict)
        assertEquals(false, m.isRunnable)
        assertEquals(3.5f, m.tokensPerSecond)
        assertEquals(900, m.estimatedVramMB)
    }

    @Test fun suggestionKeepsTheModelAndInstallState() {
        val s = SuggestionUi(
            model = SuggestedModel("id", "Name", "desc", "repo/x", "x.gguf", "gguf", "llama.cpp", 99L, true),
            isInstalled = false, isInstalling = true, progressPercent = 40, error = "boom",
        ).toTcr()
        assertEquals("repo/x", s.model.repoId)
        assertEquals("x.gguf", s.model.filename)
        assertEquals(99L, s.model.approxSizeBytes)
        assertEquals(true, s.model.runnable)
        assertEquals(true, s.isInstalling)
        assertEquals(40, s.progressPercent)
        assertEquals("boom", s.error)
    }

    @Test fun runSettingsRoundTripThroughTheTcr() {
        val settings = RunSettingsUi(
            contextLength = 2048, threads = 4, batchSize = 512, gpuLayers = 8,
            temperature = 0.7f, topP = 0.9f, topK = 40, useMmap = false,
        )
        assertEquals(settings, settings.toTcr().toHost())
        assertEquals(RunSettingsUi(), RunSettingsUi().toTcr().toHost())
    }

    @Test fun searchFiltersMapBackToTheHostType() {
        val f = dev.aidos.tcr.engine.ModelSearchFilters(minSizeMb = 1, maxSizeMb = 9, fitsDeviceOnly = true, runnableOnly = true)
        assertEquals(ModelSearchFilters(1, 9, fitsDeviceOnly = true, runnableOnly = true), f.toHost())
    }

    @Test fun detailStateKeepsModelOutcomeAndSettings() {
        val state = ModelDetailState(
            model = ModelDetail(
                id = "a/b", name = "B", providerName = "HF", kind = ModelKind.EMBEDDING, licenseName = "mit",
                modelUrl = "https://x", sizeMB = 3,
                contextFitTable = listOf(ContextFitRow(2048, ModelFitVerdict.RUNS_WELL, 700, ModelFitVerdict.WILL_NOT_FIT)),
                format = "gguf", deviceTotalRamMB = 8000, deviceFreeRamMB = 4000, baselineFreeRamMB = 3000,
            ),
            isInstalled = true, installedSizeBytes = 5L, embeddingVector = listOf(0.5f),
            selfTestOutcome = SelfTestOutcome(passed = true, title = "Passed", lines = listOf("ok"), durationMs = 1500, generatedTokens = 30),
            runSettings = RunSettingsUi(threads = 2), loadedRunSettings = null,
        )
        val t = state.toTcr()
        assertEquals(dev.aidos.tcr.engine.ModelKind.EMBEDDING, t.model?.kind)
        assertEquals("mit", t.model?.licenseName)
        assertEquals(TcrModelFitVerdict.WILL_NOT_FIT, t.model?.contextFitTable?.single()?.baselineVerdict)
        assertEquals(3000, t.model?.baselineFreeRamMB)
        assertEquals("Passed", t.selfTestOutcome?.summary)
        assertEquals(1500L, t.selfTestOutcome?.durationMs)
        assertEquals(30, t.selfTestOutcome?.generatedTokens)
        assertEquals(2, t.runSettings.threads)
        assertNull(t.loadedRunSettings)
    }

    @Test fun loadingStateKeepsStatusAndProgress() {
        val t = ModelLoadingState("m", ModelLoadingStatus.LOADING, 55, 1200, null, null).toTcr()
        assertEquals(TcrModelLoadingStatus.LOADING, t.status)
        assertEquals(55, t.loadProgress)
        assertEquals(1200, t.estimatedMemoryMB)
    }
}
