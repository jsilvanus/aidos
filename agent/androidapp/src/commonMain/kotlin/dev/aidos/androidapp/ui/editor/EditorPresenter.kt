package dev.aidos.androidapp.ui.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class EditorUiState(
    val relativePath: String = "",
    val content: String = "",
    val savedContent: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val savedSuccessfully: Boolean = false,
    val errorMessage: String? = null,
) {
    val isDirty: Boolean get() = content != savedContent
}

/**
 * Platform-neutral presenter for the RFC-0050 scoped file Editor screen.
 *
 * Enforces relative-path containment (`RelPath` rules: no leading slash, no `..` traversal,
 * no backslashes or null bytes) and attributes saves to the user subject.
 */
class EditorPresenter(
    private val readFile: suspend (relativePath: String) -> Result<String>,
    private val writeFile: suspend (relativePath: String, content: String, subject: String) -> Result<Unit>,
    private val userSubject: String = "user",
) {
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    suspend fun open(relativePath: String): Result<Unit> {
        val validated = validateRelPath(relativePath)
        if (validated.isFailure) {
            val msg = validated.exceptionOrNull()?.message ?: "Invalid path"
            _state.update { it.copy(relativePath = relativePath, errorMessage = msg, isLoading = false) }
            return Result.failure(IllegalArgumentException(msg))
        }
        val cleanPath = validated.getOrThrow()
        _state.update {
            it.copy(
                relativePath = cleanPath,
                isLoading = true,
                savedSuccessfully = false,
                errorMessage = null,
            )
        }
        val res = readFile(cleanPath)
        res.fold(
            onSuccess = { text ->
                _state.update {
                    it.copy(
                        content = text,
                        savedContent = text,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
            },
            onFailure = { err ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Failed to read $cleanPath",
                    )
                }
            },
        )
        return res.map { }
    }

    fun updateContent(newContent: String) {
        _state.update {
            it.copy(
                content = newContent,
                savedSuccessfully = false,
                errorMessage = null,
            )
        }
    }

    suspend fun save(): Result<Unit> {
        val current = _state.value
        val validated = validateRelPath(current.relativePath)
        if (validated.isFailure) {
            val msg = validated.exceptionOrNull()?.message ?: "Invalid path"
            _state.update { it.copy(errorMessage = msg) }
            return Result.failure(IllegalArgumentException(msg))
        }
        val cleanPath = validated.getOrThrow()
        _state.update { it.copy(isSaving = true, savedSuccessfully = false, errorMessage = null) }
        val res = writeFile(cleanPath, current.content, userSubject)
        res.fold(
            onSuccess = {
                _state.update {
                    it.copy(
                        savedContent = current.content,
                        isSaving = false,
                        savedSuccessfully = true,
                        errorMessage = null,
                    )
                }
            },
            onFailure = { err ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        savedSuccessfully = false,
                        errorMessage = err.message ?: "Failed to save $cleanPath",
                    )
                }
            },
        )
        return res
    }

    companion object {
        fun validateRelPath(path: String): Result<String> {
            val trimmed = path.trim()
            if (trimmed.isEmpty()) {
                return Result.failure(IllegalArgumentException("Path must not be blank"))
            }
            if (trimmed.startsWith("/") || trimmed.startsWith("\\") || trimmed.contains(':')) {
                return Result.failure(IllegalArgumentException("Absolute paths are forbidden: $path"))
            }
            if (trimmed.contains('\u0000') || trimmed.contains('\\')) {
                return Result.failure(IllegalArgumentException("Invalid path characters: $path"))
            }
            val segments = trimmed.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
                return Result.failure(IllegalArgumentException("Path traversal is forbidden: $path"))
            }
            return Result.success(segments.joinToString("/"))
        }
    }
}
