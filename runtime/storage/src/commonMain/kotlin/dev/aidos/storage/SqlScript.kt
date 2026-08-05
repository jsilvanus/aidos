package dev.aidos.storage

/**
 * Splits one of `schema/`'s files into individually-executable statements. `SqlDriver.execute`
 * takes one statement at a time; there is no `executescript` on the KMP driver interface the way
 * there is on Python's `sqlite3` (which is what `schema/check.py` uses instead).
 *
 * A line-based `--`-comment strip followed by a `;` split is sufficient for `schema/`'s own
 * dialect: plain DDL, no triggers, no `BEGIN...END` blocks, and no embedded semicolons inside a
 * string literal (verified by inspection; `schema/check.py` would also fail to parse either of
 * those, since it greps for bare `CREATE TABLE`). If a schema file ever needs a trigger body, this
 * splitter needs to grow with it.
 */
internal object SqlScript {
    fun statements(script: String): List<String> =
        script.lineSequence()
            .map { line ->
                val commentAt = line.indexOf("--")
                if (commentAt >= 0) line.substring(0, commentAt) else line
            }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
