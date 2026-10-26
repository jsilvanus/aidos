package fi.italeino.aidos.engine.inference

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor

/**
 * Where a request's data goes when a model serves it (RFC-0104). Ordered by how far the data
 * travels: [LOCAL] stays inside this Engine's process on this device, [CLUSTER] also reaches
 * devices in an authorised Aidos group (another Aidos instance, split-model helpers), [REMOTE]
 * reaches a third-party provider.
 *
 * Placement is a property of *how a model is served right now*, not of the model's name: the same
 * weights are [LOCAL] on this phone and [CLUSTER] when their layers are split across a group.
 */
enum class Placement(val wire: String) {
    LOCAL("local"),
    CLUSTER("cluster"),
    REMOTE("remote");

    companion object {
        /** An unknown value is [REMOTE]: a placement nobody understands must not pass a local-only check. */
        fun parse(value: String): Placement = entries.firstOrNull { it.wire == value.trim().lowercase() } ?: REMOTE
    }
}

/**
 * The widest [Placement] a caller accepts for a request (RFC-0104). Lower ordinal is stricter, so
 * "the stricter of two" is the smaller ordinal.
 */
enum class Locality(val wire: String, val widest: Placement) {
    LOCAL_ONLY("local_only", Placement.LOCAL),
    TRUSTED_CLUSTER("trusted_cluster", Placement.CLUSTER),
    ANY("any", Placement.REMOTE);

    fun permits(placement: Placement): Boolean = placement.ordinal <= widest.ordinal

    /** The stricter of the two; how request, app and Engine-wide limits combine. */
    fun strictest(other: Locality): Locality = if (ordinal <= other.ordinal) this else other

    companion object {
        /** Null for anything unrecognised: callers answer 400 rather than guess a policy. */
        fun parse(value: String): Locality? = entries.firstOrNull { it.wire == value.trim().lowercase() }
    }
}

/** Header a client uses to state the [Locality] it requires; Engine echoes the [Placement] used in [PLACEMENT_HEADER]. */
const val LOCALITY_HEADER = "X-Aidos-Locality"
const val PLACEMENT_HEADER = "X-Aidos-Placement"

/** Descriptor metadata key a runtime may use to declare a placement wider than `isLocal` implies. */
const val PLACEMENT_METADATA_KEY = "placement"

/**
 * The placement a model is served at. The widest of every claim wins and nothing can narrow
 * another claim, so one dishonest or stale "local" cannot override a "not local":
 * `isLocal = false` on the descriptor or the loaded [adapter] means [Placement.REMOTE]; an explicit
 * `placement` in the descriptor metadata can widen it further (e.g. to [Placement.CLUSTER]).
 *
 * With no descriptor the adapter's own claim is all there is.
 */
fun placementOf(descriptor: ModelDescriptor?, adapter: ModelAdapter? = null): Placement {
    val claims = buildList {
        if (descriptor != null) {
            add(if (descriptor.isLocal) Placement.LOCAL else Placement.REMOTE)
            descriptor.metadata[PLACEMENT_METADATA_KEY]?.let { add(Placement.parse(it)) }
        }
        if (adapter != null) add(if (adapter.isLocal) Placement.LOCAL else Placement.REMOTE)
        if (descriptor == null && adapter == null) add(Placement.REMOTE)
    }
    return claims.max()
}

/** A request required [required] but the model it names is served at [placement]. */
class LocalityViolationException(
    val modelId: String,
    val required: Locality,
    val placement: Placement,
) : RuntimeException(
    "Model $modelId is served at placement '${placement.wire}', which '${required.wire}' does not allow"
)
