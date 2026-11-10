package dev.aidos.capability

import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.MutationScope
import dev.aidos.kernel.Operation
import dev.aidos.kernel.Permission
import dev.aidos.kernel.RecoveryClass

/**
 * RFC-0027 "Authority attenuation": what a Run whose taint is UNTRUSTED may still do.
 *
 * The principle: a tainted Run keeps doing local, reversible, in-project work without friction,
 * and must ask before anything that leaves the project or cannot be undone.
 *
 * | Effect                                   | Tainted Run                     |
 * |------------------------------------------|---------------------------------|
 * | Read, Notify, Mutate IN_PROJECT          | allowed (previews already kept) |
 * | Mutate OUT_OF_PROJECT, secrets read      | denied                          |
 * | Egress, shell, push / UNSAFE recovery    | requires approval per call      |
 *
 * Callers apply this only when the Run's taint is UNTRUSTED; PROJECT and TRUSTED never attenuate.
 * Worker creation with a wider scope than the parent is not decided here (no scope comparison is
 * available at validation time); see STATUS.md.
 */
object TaintAttenuation {
    enum class Decision { ALLOW, REQUIRES_APPROVAL, DENY }

    fun decide(permission: Permission, operation: Operation<*>): Decision {
        if (permission == Permission.SECRETS_READ) return Decision.DENY
        val effect = operation.effect
        if (effect is EffectKind.Mutate && effect.scope == MutationScope.OUT_OF_PROJECT) return Decision.DENY
        if (effect is EffectKind.Egress ||
            permission == Permission.NETWORK_EGRESS ||
            permission == Permission.SHELL_EXEC ||
            operation.recoveryClass == RecoveryClass.UNSAFE
        ) return Decision.REQUIRES_APPROVAL
        return Decision.ALLOW
    }
}
