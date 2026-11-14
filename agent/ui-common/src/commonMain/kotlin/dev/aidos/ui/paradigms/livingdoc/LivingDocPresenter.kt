package dev.aidos.ui.paradigms.livingdoc

import dev.aidos.ui.pills.PillRole
import dev.aidos.ui.state.MviPresenter
import dev.aidos.ui.tcr.code.DiffLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DocumentLine(
    val lineNumber: Int,
    val text: String,
    val isLockedByAgent: Boolean = false,
    val lockOwner: PillRole = PillRole.WORKER_CODE_BLUE,
)

data class LivingDocUiState(
    val documentId: String = "",
    val title: String = "",
    val lines: List<DocumentLine> = emptyList(),
    val completedDiffs: List<Pair<String, List<DiffLine>>> = emptyList(),
    val isRecordingVoice: Boolean = false,
    val ghostText: String = "",
)

sealed interface LivingDocIntent {
    data class UpdateLine(val lineNumber: Int, val newText: String) : LivingDocIntent
    data class AcquireSoftLock(val lineNumber: Int, val role: PillRole) : LivingDocIntent
    data class ReleaseSoftLock(val lineNumber: Int) : LivingDocIntent
    data class CommitAgentDiff(val title: String, val diffLines: List<DiffLine>) : LivingDocIntent
    data class StreamVoiceTokens(val ghostTokens: String) : LivingDocIntent
    data class CommitVoiceText(val finalProse: String) : LivingDocIntent
}

class LivingDocPresenter(
    initialDocumentId: String = "doc-1",
    initialTitle: String = "System Architecture & Notes",
    initialText: String = "Introduction to Aidos\nThis document coordinates all specifications.\nEditable concurrently with voice and agent workers.",
) : MviPresenter<LivingDocUiState, LivingDocIntent> {

    private val _state = MutableStateFlow(
        LivingDocUiState(
            documentId = initialDocumentId,
            title = initialTitle,
            lines = parseLines(initialText),
        )
    )
    override val state: StateFlow<LivingDocUiState> = _state.asStateFlow()

    override fun send(intent: LivingDocIntent) {
        when (intent) {
            is LivingDocIntent.UpdateLine -> {
                _state.update { current ->
                    val updated = current.lines.map { line ->
                        if (line.lineNumber == intent.lineNumber && !line.isLockedByAgent) {
                            line.copy(text = intent.newText)
                        } else line
                    }
                    current.copy(lines = updated)
                }
            }
            is LivingDocIntent.AcquireSoftLock -> {
                _state.update { current ->
                    val updated = current.lines.map { line ->
                        if (line.lineNumber == intent.lineNumber) {
                            line.copy(isLockedByAgent = true, lockOwner = intent.role)
                        } else line
                    }
                    current.copy(lines = updated)
                }
            }
            is LivingDocIntent.ReleaseSoftLock -> {
                _state.update { current ->
                    val updated = current.lines.map { line ->
                        if (line.lineNumber == intent.lineNumber) {
                            line.copy(isLockedByAgent = false)
                        } else line
                    }
                    current.copy(lines = updated)
                }
            }
            is LivingDocIntent.CommitAgentDiff -> {
                _state.update { current ->
                    current.copy(
                        completedDiffs = current.completedDiffs + (intent.title to intent.diffLines)
                    )
                }
            }
            is LivingDocIntent.StreamVoiceTokens -> {
                _state.update { it.copy(isRecordingVoice = true, ghostText = intent.ghostTokens) }
            }
            is LivingDocIntent.CommitVoiceText -> {
                _state.update { current ->
                    val newLines = parseLines(intent.finalProse)
                    val nextLineNumber = (current.lines.maxOfOrNull { it.lineNumber } ?: 0) + 1
                    val appended = newLines.mapIndexed { idx, dl ->
                        dl.copy(lineNumber = nextLineNumber + idx)
                    }
                    current.copy(
                        lines = current.lines + appended,
                        isRecordingVoice = false,
                        ghostText = "",
                    )
                }
            }
        }
    }

    private companion object {
        fun parseLines(text: String): List<DocumentLine> {
            return text.lines().mapIndexed { index, line ->
                DocumentLine(lineNumber = index + 1, text = line)
            }
        }
    }
}
