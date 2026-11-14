package dev.aidos.ui.tcr.code

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

enum class DiffLineType {
    CONTEXT,
    ADDITION,
    DELETION,
}

data class DiffLine(
    val type: DiffLineType,
    val content: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
)

@Composable
fun DiffChunkAtom(
    header: String,
    lines: List<DiffLine>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A), shape = RoundedCornerShape(CornerTokens.sm))
            .padding(SpacingTokens.sm)
    ) {
        Text(
            text = header,
            style = AidosTypography.codeSnippet,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(bottom = SpacingTokens.xs),
        )

        lines.forEach { line ->
            val (bgColor, textColor, prefix) = when (line.type) {
                DiffLineType.CONTEXT -> Triple(Color.Transparent, Color(0xFFCBD5E1), " ")
                DiffLineType.ADDITION -> Triple(Color(0x2210B981), Color(0xFF34D399), "+")
                DiffLineType.DELETION -> Triple(Color(0x22EF4444), Color(0xFFF87171), "-")
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor)
                    .padding(vertical = 1.dp, horizontal = 4.dp)
            ) {
                Text(
                    text = "$prefix ${line.content}",
                    style = AidosTypography.codeSnippet,
                    color = textColor,
                )
            }
        }
    }
}
