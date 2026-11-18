package dev.aidos.ui

import dev.aidos.ui.layout.AndroidTier
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.layout.ViewModeCatalog
import dev.aidos.ui.layout.ViewPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** RFC-0124 §4: the per-form-factor view matrix. */
class ViewModeCatalogTest {
    @Test
    fun idsAreUniqueAndEveryAppScreenIsListed() {
        val ids = ViewModeCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // The twelve destinations AidosUniversalApp switches on.
        assertEquals(
            setOf("inbox", "outliner", "pipeline", "metro", "kanban", "radar", "breadboard", "canvas", "timeline", "livingdoc", "gates", "projects"),
            ids.toSet(),
        )
        assertNotNull(ViewModeCatalog.byId("canvas"))
        assertEquals(null, ViewModeCatalog.byId("nope"))
    }

    @Test
    fun primaryNavigationFitsEachFormFactor() {
        for (ff in FormFactor.entries) {
            val primary = ViewModeCatalog.primary(ff)
            assertTrue(primary.isNotEmpty())
            assertTrue(primary.size <= ViewModeCatalog.maxPrimary(ff), "$ff has ${primary.size} primary views")
        }
    }

    @Test
    fun phoneOffersTheFourTaskViewsAndGatesAndKeepsTheCanvasSecondary() {
        val phone = ViewModeCatalog.primary(FormFactor.HANDHELD_COMPACT).map { it.id }
        assertEquals(listOf("inbox", "outliner", "gates", "livingdoc", "projects"), phone)
        assertEquals(ViewPlacement.MORE, ViewModeCatalog.byId("canvas")!!.placement(FormFactor.HANDHELD_COMPACT))
        // With room, the canvas is an active viewport (RFC-0112 §2.1).
        assertTrue("canvas" in ViewModeCatalog.primary(FormFactor.HANDHELD_EXPANDED).map { it.id })
        assertTrue("canvas" in ViewModeCatalog.primary(FormFactor.DESKTOP_DOCKED).map { it.id })
    }

    @Test
    fun everyAndroidMvpViewIsReachableOnEveryFormFactor() {
        for (mode in ViewModeCatalog.all.filter { it.androidTier == AndroidTier.MVP }) {
            for (ff in FormFactor.entries) {
                assertTrue(mode.placement(ff) != ViewPlacement.HIDDEN, "${mode.id} is hidden on $ff but is an Android MVP view")
            }
        }
    }

    @Test
    fun metroAndBreadboardAreOfferedUnderMoreOnAPhone() {
        for (id in listOf("metro", "breadboard")) {
            val m = ViewModeCatalog.byId(id)!!
            assertEquals(AndroidTier.POST_MVP, m.androidTier)
            assertEquals(ViewPlacement.MORE, m.placement(FormFactor.HANDHELD_COMPACT))
            assertTrue(ViewModeCatalog.offered(FormFactor.HANDHELD_COMPACT).any { it.id == id })
            assertTrue(m.placement(FormFactor.HANDHELD_EXPANDED) != ViewPlacement.HIDDEN)
        }
        assertTrue(ViewModeCatalog.primary(FormFactor.HANDHELD_COMPACT).none { it.id == "metro" || it.id == "breadboard" })
    }

    @Test
    fun offeredListsPrimaryFirst() {
        for (ff in FormFactor.entries) {
            val offered = ViewModeCatalog.offered(ff)
            val firstNonPrimary = offered.indexOfFirst { it.placement(ff) != ViewPlacement.PRIMARY }
            if (firstNonPrimary >= 0) {
                assertTrue(offered.drop(firstNonPrimary).none { it.placement(ff) == ViewPlacement.PRIMARY })
            }
        }
    }
}
