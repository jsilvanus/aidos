package dev.aidos.ui.theme

import androidx.compose.ui.graphics.Color
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind

/**
 * Canonical token binding across all seven presentation modes (RFC-0106, RFC-0110, RFC-0111).
 */
object ColorTokens {
    // Actor Semantic Colors
    val ActorHuman: Color = Color(0xFFF59E0B)       // Warm Amber/Gold
    val ActorAgent: Color = Color(0xFF06B6D4)       // Electric Cyan
    val ActorHybrid: Color = Color(0xFF8B5CF6)      // Amethyst Purple

    // Status Semantic Colors
    val StatusReview: Color = Color(0xFFF97316)     // Warning Flame (NEEDS_REVIEW)
    val StatusBlocked: Color = Color(0xFFEF4444)    // Muted Crimson (BLOCKED)
    val StatusDone: Color = Color(0xFF10B981)       // Emerald Green (DONE)
    val StatusInProgress: Color = Color(0xFF3B82F6) // Vibrant Blue (IN_PROGRESS)
    val StatusNotStarted: Color = Color(0xFF6B7280) // Neutral Slate (NOT_STARTED)
    val StatusStale: Color = Color(0xFF9CA3AF)      // Dim Gray (STALE)
    val StatusConflicted: Color = Color(0xFFDC2626) // Alert Red (CONFLICTED)

    // Subagent Pill Visual Identities
    val PillOrchestratorRed: Color = Color(0xFFEF4444)
    val PillWorkerCodeBlue: Color = Color(0xFF3B82F6)
    val PillVerifierGreen: Color = Color(0xFF10B981)
    val PillResearchViolet: Color = Color(0xFF8B5CF6)

    // Line Lock Washes & Waves
    val BlueWashReservation: Color = Color(0x1F3B82F6)
    val WaveRippleEmission: Color = Color(0x6606B6D4)

    // OLED Dark / High Contrast Neutrals
    val OledBlack: Color = Color(0xFF000000)
    val SurfaceDark: Color = Color(0xFF0F172A)
    val SurfaceCardDark: Color = Color(0xFF1E293B)
    val SurfaceLight: Color = Color(0xFFF8FAFC)
    val SurfaceCardLight: Color = Color(0xFFFFFFFF)
}

fun colorForActor(actor: IntentActorKind): Color = when (actor) {
    IntentActorKind.HUMAN -> ColorTokens.ActorHuman
    IntentActorKind.AGENT -> ColorTokens.ActorAgent
    IntentActorKind.HYBRID -> ColorTokens.ActorHybrid
}

fun colorForStatus(status: DerivedStatus): Color = when (status) {
    DerivedStatus.NOT_STARTED -> ColorTokens.StatusNotStarted
    DerivedStatus.IN_PROGRESS -> ColorTokens.StatusInProgress
    DerivedStatus.BLOCKED -> ColorTokens.StatusBlocked
    DerivedStatus.NEEDS_REVIEW -> ColorTokens.StatusReview
    DerivedStatus.DONE -> ColorTokens.StatusDone
    DerivedStatus.STALE -> ColorTokens.StatusStale
    DerivedStatus.CONFLICTED -> ColorTokens.StatusConflicted
}
