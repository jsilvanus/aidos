package dev.aidos.ui.paradigms.outliner

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.SpacingTokens

/** Display text for a criterion checklist line, for example "[SHELL_COMMAND] ./gradlew test". */
internal fun criterionLabel(criterion: IntentNodeRecord): String {
    val kind = criterion.checkKind?.name ?: "NONE"
    val spec = criterion.checkSpec?.takeIf { it.isNotBlank() } ?: criterion.title
    return "[$kind] $spec"
}

/** A nested ACCEPTS criterion: a checkbox that is ticked once the check has been met. */
@Composable
fun OutlinerCriterionRow(criterion: IntentNodeRecord, modifier: Modifier = Modifier) {
    val met = criterion.verificationMet == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Text(
            text = if (met) "☑ " else "☐ ",
            style = AidosTypography.baseTypography.labelSmall,
            color = if (met) Color(0xFF22C55E) else Color(0xFF94A3B8),
        )
        Text(
            text = criterionLabel(criterion),
            style = AidosTypography.baseTypography.labelSmall,
            color = Color(0xFFCBD5E1),
            modifier = Modifier.padding(start = SpacingTokens.xxs),
        )
    }
}
