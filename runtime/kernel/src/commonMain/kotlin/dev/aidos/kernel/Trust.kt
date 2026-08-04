package dev.aidos.kernel

/**
 * Inbound trust (RFC-0027). The counterpart to [SensitivityLevel], which is outbound.
 *
 * Assigned by *origin*, automatically. A labelling scheme that depends on user diligence is a
 * labelling scheme that stays at its default.
 */
enum class TrustLevel {
    /** Authored by the user or the runtime. */
    TRUSTED,

    /** Present in the project's Git history before this session began. */
    PROJECT,

    /**
     * Arrived from outside, or was produced by a tool this Run invoked.
     *
     * Model output is `UNTRUSTED` — not from paranoia about the model, but because its output is
     * a function of its input, and its input included untrusted content.
     */
    UNTRUSTED,
    ;

    /** Taint is monotonic within a Run: it never decreases (RFC-0027). */
    infix fun raisedBy(other: TrustLevel): TrustLevel =
        if (other.ordinal > this.ordinal) other else this
}

/** Outbound: may this leave the device? (RFC-0024) */
enum class SensitivityLevel { PUBLIC, INTERNAL, SENSITIVE, SECRET }

enum class EgressEligibility { ELIGIBLE, REQUIRES_APPROVAL, BLOCKED }

/** Mutability is a policy on a content node, not a type distinction (RFC-0024). */
enum class MutabilityPolicy { IMMUTABLE, APPEND_ONLY, VERSIONED, MUTABLE_LATEST }
