package dev.aidos.portability

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

internal data class ProjectRow(val id: String, val name: String)

internal object Sqlite {
    fun <T> use(file: File, readOnly: Boolean = false, block: (Connection) -> T): T {
        val url = "jdbc:sqlite:${file.absolutePath}" + if (readOnly) "?open_mode=1" else ""
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { it.execute("PRAGMA busy_timeout = 10000") }
            return block(c)
        }
    }

    /** A transactionally consistent copy, safe while the runtime has the database open (WAL). */
    fun snapshot(source: File, destination: File) {
        use(source, readOnly = true) { c ->
            c.createStatement().use { it.execute("VACUUM INTO '${destination.absolutePath.replace("'", "''")}'") }
        }
    }

    fun project(db: File): ProjectRow = use(db, readOnly = true) { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT id, name FROM projects ORDER BY created_at LIMIT 1").use { rs ->
                if (!rs.next()) throw PortabilityException("export.no_project", "state.db has no project row")
                ProjectRow(rs.getString(1), rs.getString(2))
            }
        }
    }

    fun schemaVersion(db: File): Int? = use(db, readOnly = true) { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT version FROM schema_versions WHERE id = 1").use { rs ->
                if (rs.next()) rs.getInt(1) else null
            }
        }
    }

    fun integrityOk(db: File): Boolean = use(db, readOnly = true) { c ->
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA integrity_check").use { rs -> rs.next() && rs.getString(1) == "ok" }
        }
    }

    fun tablesWithColumn(c: Connection, column: String): List<String> {
        val tables = c.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
        return tables.filter { t ->
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(\"$t\")").use { rs ->
                    var found = false
                    while (rs.next()) if (rs.getString("name") == column) found = true
                    found
                }
            }
        }
    }

    /** audit_log is append-only and PERMANENT (RFC-0056); sequence is per project. */
    fun appendAudit(
        c: Connection, id: String, projectId: String, nowIso: String, kind: String,
        actorId: String, deviceId: String, detailJson: String,
    ) {
        val seq = c.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(sequence), 0) + 1 FROM audit_log WHERE project_id = '${projectId.replace("'", "''")}'")
                .use { rs -> rs.next(); rs.getLong(1) }
        }
        c.prepareStatement(
            "INSERT INTO audit_log (id, project_id, sequence, occurred_at, kind, actor_kind, actor_id, device_id, subject_ref, detail_json) " +
                "VALUES (?, ?, ?, ?, ?, 'USER', ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, projectId); ps.setLong(3, seq); ps.setString(4, nowIso)
            ps.setString(5, kind); ps.setString(6, actorId); ps.setString(7, deviceId)
            ps.setString(8, projectId); ps.setString(9, detailJson)
            ps.executeUpdate()
        }
    }
}

/** Resolve `.git/HEAD` to a commit id without a Git dependency; null when there is no repository. */
internal fun resolveGitHead(projectRoot: File): String? {
    val gitDir = File(projectRoot, ".git")
    val head = File(gitDir, "HEAD").takeIf { it.isFile }?.readText()?.trim() ?: return null
    if (!head.startsWith("ref: ")) return head.takeIf { it.matches(Regex("[0-9a-f]{40,64}")) }
    val ref = head.removePrefix("ref: ").trim()
    File(gitDir, ref).takeIf { it.isFile }?.readText()?.trim()?.let { return it }
    val packed = File(gitDir, "packed-refs").takeIf { it.isFile } ?: return null
    return packed.readLines().firstOrNull { !it.startsWith("#") && !it.startsWith("^") && it.trim().endsWith(" $ref") }
        ?.substringBefore(' ')
}

/** Cheap object-store sanity check: the HEAD commit exists loose, or packs are present. */
internal fun gitObjectPresent(projectRoot: File, sha: String): Boolean {
    val objects = File(projectRoot, ".git/objects")
    if (File(objects, "${sha.take(2)}/${sha.drop(2)}").isFile) return true
    return File(objects, "pack").listFiles { f -> f.name.endsWith(".pack") }?.isNotEmpty() == true
}
