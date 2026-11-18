package dev.aidos.ui.layout

/**
 * Where a view mode is offered on a given form factor (RFC-0124 §4).
 *
 * [PRIMARY] is the persistent navigation (bottom bar, rail or drawer). [MORE] is reachable but
 * secondary (the view chip row and the command palette). [HIDDEN] means the form factor does not
 * offer the view at all; it is never the case for an MVP view on the same form factor family.
 */
enum class ViewPlacement { PRIMARY, MORE, HIDDEN }

/** Whether a view ships in the MVP on Android handhelds or is scheduled after it (RFC-0124 §4). */
enum class AndroidTier { MVP, POST_MVP }

/**
 * One presentation paradigm of the Dual-Mode Intent Graph (RFC-0111) or a front door (RFC-0112).
 * [id] is the destination id [dev.aidos.ui.AidosUniversalApp] switches on.
 */
data class ViewMode(
    val id: String,
    val label: String,
    val icon: String,
    val compact: ViewPlacement,
    val expanded: ViewPlacement,
    val docked: ViewPlacement,
    val androidTier: AndroidTier,
) {
    fun placement(formFactor: FormFactor): ViewPlacement = when (formFactor) {
        FormFactor.HANDHELD_COMPACT -> compact
        FormFactor.HANDHELD_EXPANDED -> expanded
        FormFactor.DESKTOP_DOCKED -> docked
    }
}

/**
 * The single list of views and where each is offered, replacing the two hard-coded lists in the app
 * root. Encodes the RFC-0112 §2.1 parity matrix and the RFC-0111 §4 selection matrix, so the phone
 * shows the four task views plus Gates, and the canvas, timeline and wide designs arrive with room.
 */
object ViewModeCatalog {
    private val P = ViewPlacement.PRIMARY
    private val M = ViewPlacement.MORE

    val all: List<ViewMode> = listOf(
        ViewMode("inbox", "Inbox", "📥", P, P, P, AndroidTier.MVP),
        ViewMode("outliner", "Outliner", "🌳", P, P, P, AndroidTier.MVP),
        ViewMode("gates", "Gates", "🛡", P, P, M, AndroidTier.MVP),
        ViewMode("livingdoc", "Document", "📝", P, P, P, AndroidTier.MVP),
        ViewMode("projects", "Projects", "💼", P, P, P, AndroidTier.MVP),
        // Canvas is "Expand Canvas" on a phone, the active viewport once there is room.
        ViewMode("canvas", "Canvas", "🌌", M, P, P, AndroidTier.MVP),
        ViewMode("kanban", "Cartridge Dock", "🗂", M, M, P, AndroidTier.MVP),
        ViewMode("timeline", "Timeline", "📅", M, M, P, AndroidTier.MVP),
        ViewMode("pipeline", "Relay River", "🌊", M, M, M, AndroidTier.MVP),
        ViewMode("radar", "Radial Radar", "🎯", M, M, M, AndroidTier.MVP),
        // Wide designs (RFC-0111 Designs 2 and 5). A phone gets their narrow strip form, which lives
        // under More until one is rated primary (RFC-0124 0124.5, decision U11).
        ViewMode("metro", "Metro Map", "🚇", M, M, M, AndroidTier.POST_MVP),
        ViewMode("breadboard", "Breadboard", "🔌", M, M, M, AndroidTier.POST_MVP),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): ViewMode? = byId[id]

    /** Persistent navigation entries for [formFactor], in display order. */
    fun primary(formFactor: FormFactor): List<ViewMode> =
        all.filter { it.placement(formFactor) == ViewPlacement.PRIMARY }

    /** Everything offered on [formFactor]: primary first, then the rest, hidden views excluded. */
    fun offered(formFactor: FormFactor): List<ViewMode> =
        all.filter { it.placement(formFactor) != ViewPlacement.HIDDEN }
            .sortedBy { if (it.placement(formFactor) == ViewPlacement.PRIMARY) 0 else 1 }

    /** A bottom bar holds 5 items, a rail or drawer a few more; keep each within its limit. */
    fun maxPrimary(formFactor: FormFactor): Int = when (formFactor) {
        FormFactor.HANDHELD_COMPACT -> 5
        FormFactor.HANDHELD_EXPANDED -> 7
        FormFactor.DESKTOP_DOCKED -> 9
    }
}
