package dev.aidos.ui.spatial.radar

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.kernel.PlatformProfile
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.state.MviPresenter
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * RFC-0111 Design 4 zones. The strike zone is the centre; sealed is the perimeter.
 * Zone bands on the radar are in [blipPosition].
 */
enum class RadarZone(val label: String) {
    STRIKE_ZONE("Strike zone"), // NOT_STARTED with every dependency met: ready now
    IN_FLIGHT("In flight"),     // IN_PROGRESS or NEEDS_REVIEW (NEEDS_REVIEW is drawn amber)
    HORIZON("Horizon"),         // BLOCKED, or not started with unmet dependencies; one wedge per top GOAL
    SEALED("Sealed"),           // DONE, faded, at most the 30 most recent
}

enum class RadarFilter(val label: String) {
    ALL("All"),
    MINE("Mine"),
    AGENTS("Agents"),
    THIS_DEVICE("This device"),
}

/** Position on the radar. radius 0 is the centre and 1 the rim; angle 0 is three o'clock, clockwise on screen. */
data class RadarPolar(val radius: Float, val angleRad: Float, val fraction: Float)

data class RadarNodeUiModel(
    val node: IntentNodeRecord,
    val status: DerivedStatus,
    val zone: RadarZone,
    val wedge: Int,
    val unmetDependencyIds: List<String>,
    val polar: RadarPolar,
)

/** A top GOAL label. [goalId] is null for the extra wedge that holds blips with no GOAL ancestor. */
data class RadarWedge(val index: Int, val goalId: String?, val label: String)

/** A faint line from a horizon blip to one of its unmet dependencies that is also on the radar. */
data class RadarBeam(val fromId: String, val toId: String)

/** A blip with its position in the same units as the canvas it is drawn on. */
data class PlacedBlip(val nodeId: String, val x: Float, val y: Float)

data class FocusRadarUiState(
    val radarNodes: List<RadarNodeUiModel> = emptyList(),
    val wedges: List<RadarWedge> = emptyList(),
    val beams: List<RadarBeam> = emptyList(),
    val activeZone: RadarZone? = null,
    val filter: RadarFilter = RadarFilter.ALL,
    val deviceProfile: PlatformProfile? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    fun countIn(zone: RadarZone): Int = radarNodes.count { it.zone == zone }
}

sealed interface FocusRadarIntent {
    data object Refresh : FocusRadarIntent
    data class SelectZone(val zone: RadarZone?) : FocusRadarIntent
    data class SetFilter(val filter: RadarFilter) : FocusRadarIntent
    data class SetDeviceProfile(val profile: PlatformProfile?) : FocusRadarIntent
}

const val SEALED_LIMIT = 30

/**
 * Radar zone of a node. NOT_STARTED with unmet dependencies is in the horizon, because it cannot start
 * yet. STALE and CONFLICTED are not ready either, so they stay on the horizon rather than in flight.
 */
fun zoneOf(status: DerivedStatus, unmetDeps: List<String>): RadarZone = when (status) {
    DerivedStatus.DONE -> RadarZone.SEALED
    DerivedStatus.IN_PROGRESS, DerivedStatus.NEEDS_REVIEW -> RadarZone.IN_FLIGHT
    DerivedStatus.NOT_STARTED -> if (unmetDeps.isEmpty()) RadarZone.STRIKE_ZONE else RadarZone.HORIZON
    DerivedStatus.BLOCKED, DerivedStatus.STALE, DerivedStatus.CONFLICTED -> RadarZone.HORIZON
}

/** Only atomic tasks and sub-goals are blips. GOALs label the wedges. */
fun isBlip(node: IntentNodeRecord): Boolean =
    node.type == IntentNodeType.ATOMIC_TASK || node.type == IntentNodeType.SUB_GOAL

/**
 * Wedge index of a blip: the index of its highest GOAL ancestor among the top GOALs. A blip with no GOAL
 * ancestor goes to the extra wedge after the last top GOAL.
 */
fun wedgeIndex(node: IntentNodeRecord, snapshot: GraphSnapshot): Int {
    val byId = snapshot.nodes.associateBy { it.id }
    val goals = topGoals(snapshot.nodes, byId)
    return wedgeOf(node, byId, goals.withIndex().associate { it.value.id to it.index }, goals.size)
}

/**
 * Position of a blip. [wedge] and [wedgeCount] divide the full circle into equal wedges; [indexInWedge] and
 * [count] place the blip within its wedge. Radius falls in the band of its zone (strike centre to sealed rim).
 */
fun blipPosition(zone: RadarZone, wedge: Int, indexInWedge: Int, count: Int, wedgeCount: Int = 1): RadarPolar {
    val slots = wedgeCount.coerceAtLeast(1)
    val t = (indexInWedge + 1).toFloat() / (count.coerceAtLeast(1) + 1)
    val fraction = ((wedge + t) / slots).coerceIn(0f, 1f)
    val (lo, hi) = band(zone)
    val radius = lo + (hi - lo) * t
    val angle = (-PI / 2 + 2 * PI * fraction).toFloat()
    return RadarPolar(radius, angle, fraction)
}

/** The nearest blip within [maxDistance] of (x, y), or null. Coordinates and distance share one unit. */
fun hitTest(x: Float, y: Float, blips: List<PlacedBlip>, maxDistance: Float = 24f): PlacedBlip? {
    var best: PlacedBlip? = null
    var bestDistance = maxDistance
    for (blip in blips) {
        val d = distance(blip.x, blip.y, x, y)
        if (d <= bestDistance) {
            best = blip
            bestDistance = d
        }
    }
    return best
}

/** The platform a device-filtered view matches. Handhelds are MOBILE; a docked window is DESKTOP. */
fun profileForFormFactor(formFactor: FormFactor): PlatformProfile = when (formFactor) {
    FormFactor.DESKTOP_DOCKED -> PlatformProfile.DESKTOP
    FormFactor.HANDHELD_COMPACT, FormFactor.HANDHELD_EXPANDED -> PlatformProfile.MOBILE
}

internal fun matchesFilter(node: IntentNodeRecord, filter: RadarFilter, device: PlatformProfile?): Boolean =
    when (filter) {
        RadarFilter.ALL -> true
        RadarFilter.MINE -> node.actorKind == IntentActorKind.HUMAN
        RadarFilter.AGENTS -> node.actorKind == IntentActorKind.AGENT
        RadarFilter.THIS_DEVICE -> device != null && runsOn(node.targetProfile, device)
    }

/** A node with no target profile, or ANY, is not tied to this device, so it does not match. */
private fun runsOn(target: IntentTargetProfile?, device: PlatformProfile): Boolean = when (target) {
    IntentTargetProfile.MOBILE -> device == PlatformProfile.MOBILE
    IntentTargetProfile.DESKTOP -> device == PlatformProfile.DESKTOP
    IntentTargetProfile.HEADLESS_SERVER -> device == PlatformProfile.HEADLESS_SERVER
    IntentTargetProfile.ANY, null -> false
}

/** A radar built from one snapshot and filter. Pure, so the JVM tests can check it without Compose. */
internal class RadarModel(
    val nodes: List<RadarNodeUiModel>,
    val wedges: List<RadarWedge>,
    val beams: List<RadarBeam>,
)

internal fun buildRadar(snap: GraphSnapshot, filter: RadarFilter, device: PlatformProfile?): RadarModel {
    val byId = snap.nodes.associateBy { it.id }
    val goals = topGoals(snap.nodes, byId)
    val goalIndex = goals.withIndex().associate { it.value.id to it.index }
    val unfiled = goals.size

    val rows = snap.nodes
        .filter { isBlip(it) && it.lifecycle == IntentLifecycle.ACTIVE && matchesFilter(it, filter, device) }
        .map { node ->
            val view: IntentNodeStatusView? = snap.statuses[node.id]
            val status = view?.derivedStatus ?: DerivedStatus.NOT_STARTED
            val unmet = view?.unmetDependencyIds ?: emptyList()
            RawBlip(node, status, zoneOf(status, unmet), wedgeOf(node, byId, goalIndex, unfiled), unmet)
        }

    // Only the 30 most recently modified sealed blips stay on the radar. ISO-8601 timestamps sort as text.
    val sealedKept = rows.filter { it.zone == RadarZone.SEALED }
        .sortedByDescending { it.node.modifiedAt }
        .take(SEALED_LIMIT)
        .map { it.node.id }
        .toSet()
    val kept = rows.filter { it.zone != RadarZone.SEALED || it.node.id in sealedKept }

    val horizonUnfiled = kept.any { it.zone == RadarZone.HORIZON && it.wedge == unfiled }
    val wedgeCount = unfiled + if (horizonUnfiled) 1 else 0

    // Blips in the same zone (and, on the horizon, the same wedge) are spread over one slot each.
    fun slotOf(r: RawBlip) = if (r.zone == RadarZone.HORIZON) r.wedge else 0
    val groupSize = kept.groupingBy { it.zone to slotOf(it) }.eachCount()
    val seen = HashMap<Pair<RadarZone, Int>, Int>()
    val nodes = kept.map { r ->
        val key = r.zone to slotOf(r)
        val index = seen.getOrDefault(key, 0)
        seen[key] = index + 1
        val polar = blipPosition(
            zone = r.zone,
            wedge = slotOf(r),
            indexInWedge = index,
            count = groupSize.getValue(key),
            wedgeCount = if (r.zone == RadarZone.HORIZON) wedgeCount else 1,
        )
        RadarNodeUiModel(r.node, r.status, r.zone, r.wedge, r.unmet, polar)
    }

    val onRadar = nodes.map { it.node.id }.toSet()
    val beams = nodes.filter { it.zone == RadarZone.HORIZON }.flatMap { m ->
        m.unmetDependencyIds.filter { it in onRadar }.map { RadarBeam(m.node.id, it) }
    }

    val wedges = goals.mapIndexed { i, g -> RadarWedge(i, g.id, g.title) } +
        if (horizonUnfiled) listOf(RadarWedge(unfiled, null, "Unfiled")) else emptyList()

    return RadarModel(nodes, wedges, beams)
}

private class RawBlip(
    val node: IntentNodeRecord,
    val status: DerivedStatus,
    val zone: RadarZone,
    val wedge: Int,
    val unmet: List<String>,
)

private fun band(zone: RadarZone): Pair<Float, Float> = when (zone) {
    RadarZone.STRIKE_ZONE -> 0.10f to 0.30f
    RadarZone.IN_FLIGHT -> 0.36f to 0.56f
    RadarZone.HORIZON -> 0.62f to 0.86f
    RadarZone.SEALED -> 0.92f to 0.98f
}

/** The GOALs that have no GOAL ancestor, in snapshot order. These are the wedges. */
private fun topGoals(nodes: List<IntentNodeRecord>, byId: Map<String, IntentNodeRecord>): List<IntentNodeRecord> =
    nodes.filter { it.type == IntentNodeType.GOAL && topGoalOf(it, byId) == null }

/** The highest GOAL strictly above [node], or null. Walks the parent chain and guards against cycles. */
private fun topGoalOf(node: IntentNodeRecord, byId: Map<String, IntentNodeRecord>): IntentNodeRecord? {
    var top: IntentNodeRecord? = null
    var current = node.parentId?.let { byId[it] }
    val seen = HashSet<String>()
    while (current != null && seen.add(current.id)) {
        if (current.type == IntentNodeType.GOAL) top = current
        current = current.parentId?.let { byId[it] }
    }
    return top
}

private fun wedgeOf(
    node: IntentNodeRecord,
    byId: Map<String, IntentNodeRecord>,
    goalIndex: Map<String, Int>,
    unfiled: Int,
): Int {
    val top = topGoalOf(node, byId) ?: return unfiled
    return goalIndex[top.id] ?: unfiled
}

private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx
    val dy = ay - by
    return sqrt(dx * dx + dy * dy)
}

/** RFC-0124 amendment (UI-20): radar nodes are derived from the live [GraphSnapshot]. */
class FocusRadarPresenter(
    snapshot: StateFlow<GraphSnapshot>,
    scope: CoroutineScope,
) : MviPresenter<FocusRadarUiState, FocusRadarIntent> {

    private val _state = MutableStateFlow(FocusRadarUiState(isLoading = true))
    override val state: StateFlow<FocusRadarUiState> = _state.asStateFlow()

    private var latest: GraphSnapshot? = null

    init {
        scope.launch {
            snapshot.collect { snap ->
                latest = snap
                rebuild(snap)
            }
        }
    }

    private fun rebuild(snap: GraphSnapshot) {
        val current = _state.value
        val model = buildRadar(snap, current.filter, current.deviceProfile)
        _state.update {
            it.copy(
                radarNodes = model.nodes,
                wedges = model.wedges,
                beams = model.beams,
                isLoading = snap.loadedAt == 0L && snap.error == null,
                error = snap.error,
            )
        }
    }

    override fun send(intent: FocusRadarIntent) {
        when (intent) {
            is FocusRadarIntent.Refresh -> Unit // the ProjectGraphStore refreshes itself
            is FocusRadarIntent.SelectZone -> _state.update { it.copy(activeZone = intent.zone) }
            is FocusRadarIntent.SetFilter -> {
                _state.update { it.copy(filter = intent.filter) }
                latest?.let(::rebuild)
            }
            is FocusRadarIntent.SetDeviceProfile -> {
                _state.update { it.copy(deviceProfile = intent.profile) }
                latest?.let(::rebuild)
            }
        }
    }
}
