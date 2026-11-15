package dev.aidos.androidapp.ui.editor

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditorPresenterTest {

    @Test
    fun `opens edits and saves relative path with user subject`() = runTest {
        val files = mutableMapOf("src/Main.kt" to "fun main() {}")
        var lastSubject: String? = null
        val presenter = EditorPresenter(
            readFile = { path ->
                files[path]?.let { Result.success(it) }
                    ?: Result.failure(NoSuchElementException("Missing $path"))
            },
            writeFile = { path, content, subject ->
                files[path] = content
                lastSubject = subject
                Result.success(Unit)
            },
        )

        assertTrue(presenter.open("src/Main.kt").isSuccess)
        assertEquals("fun main() {}", presenter.state.value.content)
        assertFalse(presenter.state.value.isDirty)

        presenter.updateContent("fun main() { println(1) }")
        assertTrue(presenter.state.value.isDirty)

        assertTrue(presenter.save().isSuccess)
        assertFalse(presenter.state.value.isDirty)
        assertTrue(presenter.state.value.savedSuccessfully)
        assertEquals("user", lastSubject)
        assertEquals("fun main() { println(1) }", files["src/Main.kt"])
    }

    @Test
    fun `rejects path traversal and absolute paths`() = runTest {
        val presenter = EditorPresenter(
            readFile = { Result.success("") },
            writeFile = { _, _, _ -> Result.success(Unit) },
        )

        assertTrue(presenter.open("../secret.txt").isFailure)
        assertNotNull(presenter.state.value.errorMessage)

        assertTrue(presenter.open("/etc/passwd").isFailure)
        assertTrue(presenter.open("foo/../bar.txt").isFailure)
        assertTrue(presenter.open("").isFailure)
    }
}
