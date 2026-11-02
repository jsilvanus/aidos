package fi.italeino.aidos.engine.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelSearchFiltersTest {
    private val mb = 1024L * 1024L

    private fun model(
        sizeMb: Long?,
        verdict: ModelFitVerdict = ModelFitVerdict.RUNS_WELL,
        runnable: Boolean = true,
    ) = CookbookModel(
        id = "m", name = "m", kind = "LLM", quantization = "GGUF",
        sizeBytes = sizeMb?.times(mb), contextLength = 4096, fitVerdict = verdict, isRunnable = runnable,
    )

    @Test
    fun defaultFiltersAcceptEverythingIncludingUnknownSize() {
        val f = ModelSearchFilters()
        assertFalse(f.isActive)
        assertTrue(f.accepts(model(null)))
    }

    @Test
    fun sizeBoundsAreInclusiveAndUnknownSizeIsRejected() {
        val f = ModelSearchFilters(minSizeMb = 100, maxSizeMb = 500)
        assertTrue(f.isActive)
        assertTrue(f.accepts(model(100)))
        assertTrue(f.accepts(model(500)))
        assertFalse(f.accepts(model(99)))
        assertFalse(f.accepts(model(501)))
        assertFalse(f.accepts(model(null)))
    }

    @Test
    fun fitAndRunnableFilters() {
        val f = ModelSearchFilters(fitsDeviceOnly = true, runnableOnly = true)
        assertTrue(f.accepts(model(1)))
        assertFalse(f.accepts(model(1, verdict = ModelFitVerdict.WILL_NOT_FIT)))
        assertFalse(f.accepts(model(1, runnable = false)))
        assertEquals(listOf(true), listOf(model(1), model(1, runnable = false)).map(f::accepts).take(1))
    }
}
