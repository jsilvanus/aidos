package dev.aidos.ui.paradigms.outliner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

/**
 * The inline text field for a new node (RFC-0111 Design 6). Enter submits, Escape cancels.
 * Tab and Escape are also seen by the list, which handles them first; see CausalOutlinerScreen.
 */
@Composable
fun ChildDraftField(
    draft: ChildDraft,
    depth: Int,
    parentTitle: String?,
    onText: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = (depth * 20 + 16).dp, end = SpacingTokens.base, top = 2.dp, bottom = 2.dp),
    ) {
        Text(
            text = "+ ${draft.type.name.lowercase().replace('_', ' ')}",
            style = AidosTypography.baseTypography.labelSmall,
            color = Color(0xFF94A3B8),
        )
        BasicTextField(
            value = draft.text,
            onValueChange = onText,
            singleLine = true,
            textStyle = AidosTypography.baseTypography.bodyMedium.copy(color = Color.White),
            cursorBrush = SolidColor(ColorTokens.ActorAgent),
            modifier = Modifier
                .padding(start = SpacingTokens.sm)
                .weight(1f)
                .focusRequester(focusRequester)
                .background(Color(0x221E293B), shape = RoundedCornerShape(CornerTokens.xs))
                .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs)
                .onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                            onSubmit(draft.text)
                            true
                        }
                        event.key == Key.Escape -> {
                            onCancel()
                            true
                        }
                        else -> false
                    }
                },
        )
        Text(
            text = parentTitle?.let { "  under $it" } ?: "  top level",
            style = AidosTypography.baseTypography.labelSmall,
            color = Color(0xFF64748B),
        )
    }
}
