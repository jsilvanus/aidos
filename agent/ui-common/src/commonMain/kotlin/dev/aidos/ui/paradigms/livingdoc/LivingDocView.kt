package dev.aidos.ui.paradigms.livingdoc

import dev.aidos.ui.assistant.VoiceCaptureController
import dev.aidos.ui.assistant.VoiceCaptureState
import dev.aidos.ui.layout.FormFactor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.theme.colorForStatus
import dev.aidos.ui.workspace.WorkspaceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The Living Document (RFC-0106 §1.2, RFC-0124 UI-33): the selected node's text, or "Notes", editable
 * here and dictated with the host's voice controller. Child tasks sit under the text as inline blocks.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun LivingDocView(
    presenter: LivingDocPresenter,
    controller: WorkspaceController,
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
    voiceCaptureController: VoiceCaptureController? = null,
) {
    val state by presenter.state.collectAsState()
    val scope = rememberCoroutineScope()
    var field by remember { mutableStateOf(TextFieldValue(state.text)) }

    // The presenter's text drives the field when it changes from outside (a reload, a finished node switch).
    LaunchedEffect(state.text) {
        if (state.text != field.text) field = TextFieldValue(state.text, TextRange(state.text.length))
    }
    // Leaving the view saves whatever is still unsaved.
    DisposableEffect(presenter) {
        onDispose { presenter.flush() }
    }

    val voice = voiceCaptureController
    val voiceState by (voice?.state ?: remember { MutableStateFlow(VoiceCaptureState()) }).collectAsState()

    fun dictate() {
        val ctrl = voice ?: return
        scope.launch {
            if (ctrl.state.value.isListening) {
                val spoken = ctrl.stopListening()?.trim().orEmpty()
                if (spoken.isNotEmpty()) {
                    val cursor = field.selection.start
                    val needsSpace = cursor > 0 && !field.text[cursor - 1].isWhitespace()
                    val (text, after) = insertAtCursor(field.text, cursor, if (needsSpace) " $spoken" else spoken)
                    field = TextFieldValue(text, TextRange(after))
                    presenter.onTextChanged(text)
                }
            } else {
                ctrl.startListening()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorTokens.SurfaceDark)
            .padding(SpacingTokens.base)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (state.breadcrumb.isNotEmpty()) {
                    Text(
                        text = state.breadcrumb.joinToString(" › "),
                        style = AidosTypography.baseTypography.labelSmall,
                        color = Color(0xFF94A3B8),
                    )
                }
                Text(
                    text = state.title,
                    style = AidosTypography.baseTypography.headlineMedium,
                    color = Color.White,
                    modifier = state.nodeId?.let { id -> Modifier.clickable { controller.openInspector(id) } } ?: Modifier,
                )
                state.status?.let { status ->
                    Text(
                        text = status.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                        style = AidosTypography.baseTypography.labelSmall,
                        color = colorForStatus(status),
                    )
                }
            }
            Text(
                text = saveLabel(state.saveState),
                style = AidosTypography.baseTypography.labelSmall,
                color = Color(0xFF94A3B8),
            )
            if (voice != null) {
                val listening = voiceState.isListening
                IconButton(onClick = { dictate() }) {
                    Icon(
                        imageVector = if (listening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (listening) "Stop dictation" else "Dictate",
                        tint = if (listening) ColorTokens.ActorAgent else Color.White,
                    )
                }
            }
        }

        if (state.conflict) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Changed elsewhere",
                    style = AidosTypography.baseTypography.bodyMedium,
                    color = Color(0xFFFBBF24),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { presenter.reloadRemote() }) { Text("Reload") }
                TextButton(onClick = { presenter.keepMine() }) { Text("Keep mine") }
            }
        }
        state.error?.let { error ->
            Text(text = error, style = AidosTypography.baseTypography.labelSmall, color = ColorTokens.ActorAgent)
        }

        val ghost = if (voiceState.isListening) voiceState.partialText else ""
        BasicTextField(
            value = field,
            onValueChange = { next ->
                field = next
                presenter.onTextChanged(next.text)
            },
            textStyle = AidosTypography.baseTypography.bodyLarge.copy(color = Color(0xFFF1F5F9)),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = GhostTextTransformation(field.selection.start, ghost),
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
        )

        state.children.forEach { child ->
            InlineToolBlock(
                toolName = child.node.title,
                statusText = child.status?.name?.lowercase()?.replace('_', ' ') ?: "not started",
                modifier = Modifier.clickable { controller.openInspector(child.node.id) },
            ) {
                Text(
                    text = "Actor: ${child.node.actorKind.name.lowercase()}",
                    style = AidosTypography.baseTypography.labelSmall,
                    color = Color(0xFF94A3B8),
                )
            }
        }

        GenerativeBlocksSlot(nodeId = state.nodeId)
    }
}

/**
 * Where generative cards go under the text (UI-51). Renders nothing until UI-51 fills it in.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun GenerativeBlocksSlot(nodeId: String?) {
}

private fun saveLabel(state: LivingDocSaveState): String = when (state) {
    LivingDocSaveState.SAVED -> "Saved"
    LivingDocSaveState.DIRTY -> "Unsaved"
    LivingDocSaveState.SAVING -> "Saving…"
    LivingDocSaveState.ERROR -> "Not saved"
}

/**
 * Shows the partial dictation as ghost text at the cursor. The ghost is display only: the offsets the
 * field edits through skip over it, so typing and selection behave as if it were not there.
 */
private class GhostTextTransformation(
    private val cursor: Int,
    private val ghost: String,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (ghost.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val at = cursor.coerceIn(0, text.length)
        val shown = buildAnnotatedString {
            append(text.text.substring(0, at))
            withStyle(SpanStyle(color = Color(0x80F1F5F9))) { append(ghost) }
            append(text.text.substring(at))
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                if (offset <= at) offset else offset + ghost.length

            override fun transformedToOriginal(offset: Int): Int = when {
                offset <= at -> offset
                offset <= at + ghost.length -> at
                else -> offset - ghost.length
            }
        }
        return TransformedText(shown, mapping)
    }
}
