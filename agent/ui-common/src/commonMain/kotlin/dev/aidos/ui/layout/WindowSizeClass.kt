package dev.aidos.ui.layout

/**
 * Universal adaptive layout form factors (RFC-0111, RFC-0112).
 */
enum class FormFactor {
    HANDHELD_COMPACT,   // Normal phone in portrait (< 600dp width)
    HANDHELD_EXPANDED,  // Foldable unfolded / small tablet (600dp - 839dp)
    DESKTOP_DOCKED,     // OTG monitor, Samsung DeX, or Desktop App (>= 840dp)
}

object WindowBreakpoints {
    const val COMPACT_MAX_WIDTH_DP = 599.99f
    const val EXPANDED_MAX_WIDTH_DP = 839.99f
    const val DOCKED_MIN_WIDTH_DP = 840.0f
}

fun computeFormFactor(widthDp: Float): FormFactor {
    return when {
        widthDp < 600f -> FormFactor.HANDHELD_COMPACT
        widthDp < 840f -> FormFactor.HANDHELD_EXPANDED
        else -> FormFactor.DESKTOP_DOCKED
    }
}
