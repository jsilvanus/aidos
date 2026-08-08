package dev.aidos.models

import dev.aidos.cookbook.CookbookVerdict
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelBrowserTest {
    @Test
    fun testCookbookVerdictHumanReadable() {
        // Verify all cookbook verdicts have readable labels
        val verdicts = listOf(
            CookbookVerdict.RUNS_WELL to "Runs smoothly (>30% headroom)",
            CookbookVerdict.RUNS_TIGHT to "Runs with caution (10-30% headroom)",
            CookbookVerdict.EXCEEDS_CONTEXT to "Weights fit, context window too large",
            CookbookVerdict.WILL_NOT_FIT to "Insufficient RAM for this model",
        )

        for ((verdict, expectedLabel) in verdicts) {
            val readable = verdict.humanReadable()
            assertEquals(expectedLabel, readable, "Verdict $verdict should map to readable label")
        }
    }

    private fun CookbookVerdict.humanReadable(): String = when (this) {
        CookbookVerdict.RUNS_WELL -> "Runs smoothly (>30% headroom)"
        CookbookVerdict.RUNS_TIGHT -> "Runs with caution (10-30% headroom)"
        CookbookVerdict.EXCEEDS_CONTEXT -> "Weights fit, context window too large"
        CookbookVerdict.WILL_NOT_FIT -> "Insufficient RAM for this model"
    }
}
