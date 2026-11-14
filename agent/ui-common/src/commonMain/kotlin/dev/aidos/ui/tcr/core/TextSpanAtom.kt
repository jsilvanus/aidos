package dev.aidos.ui.tcr.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

enum class TextSpanKind {
    MONOSPACE_TOKEN,
    CODE_SNIPPET,
    MATH_FORMULA,
    PROSE_BODY,
}

@Composable
fun TextSpanAtom(
    text: String,
    kind: TextSpanKind = TextSpanKind.PROSE_BODY,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    when (kind) {
        TextSpanKind.MONOSPACE_TOKEN -> {
            Box(
                modifier = modifier
                    .background(
                        color = Color(0x2294A3B8),
                        shape = RoundedCornerShape(CornerTokens.xs)
                    )
                    .padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs)
            ) {
                Text(
                    text = text,
                    style = AidosTypography.codeSnippet,
                    color = color,
                )
            }
        }
        TextSpanKind.CODE_SNIPPET -> {
            Text(
                text = text,
                style = AidosTypography.codeSnippet,
                modifier = modifier,
                color = color,
            )
        }
        TextSpanKind.MATH_FORMULA -> {
            Text(
                text = text,
                style = AidosTypography.mathFormula,
                modifier = modifier,
                color = color,
            )
        }
        TextSpanKind.PROSE_BODY -> {
            Text(
                text = text,
                style = AidosTypography.baseTypography.bodyMedium,
                modifier = modifier,
                color = color,
            )
        }
    }
}
