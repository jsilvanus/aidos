package dev.aidos.portability

import dev.aidos.storage.AidosStorage
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC-0041. Every test names the requirement it covers; the RFC's table points here. */
class PortabilityTest {
    private val tmp: File = Files.createTempDirectory("aidos-portability").toFile()
    private var counter = 0

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private class Registry : ProjectRegistryPort {
        val map = mutableMapOf<String, String>()
        override fun isRegistered(projectId: String) = projectId in map
        override fun register(projectId: String, path: String, nowIso: String) { map[projectId] = path }
    }

    private val fastKdf = 1000
    private val head = "a".repeat(40)

    private fun exec(c: java.sql.Connection, sql: String) = c.createStatement().use { it.execute(sql) }

    /** A project as the runtime leaves it: state.db with grants/adoptions, a git dir, content, blobs. */
    private fun makeProject(name: String = "myapp", id: String = "proj-1"): File {
        val root = File(tmp, "src-${counter++}-$name").apply { mkdirs() }
        File(root, ".aidos").mkdirs()
        val db = File(root, ".aidos/state.db")
        AidosStorage.openProject(db.absolutePath, "test") { "2026-11-10T00:00:00Z" }.driver.close()
        Sqlite.use(db) { c ->
            exec(c, "INSERT INTO projects (id,name,root_path,created_at,updated_at,state_updated_at) VALUES ('$id','$name','${root.absolutePath}','t','t','t')")
            exec(c, "INSERT INTO audit_log (id,project_id,sequence,occurred_at,kind,actor_kind,actor_id,device_id) VALUES ('a1','$id',1,'t','CapabilityGranted','USER','u','d')")
            exec(c, "INSERT INTO capabilities (id,project_id,permission,subject_id,subject_kind,scope_json,constraints_json,issued_at,issued_by_kind,issued_by_id,revocation_epoch,audit_ref) VALUES ('cap1','$id','fs.write','s1','SESSION','{}','{}','t','USER','u',0,'a1')")
            exec(c, "INSERT INTO project_revocation_epoch (project_id,epoch) VALUES ('$id',3)")
            exec(c, "INSERT INTO instruction_adoptions (project_id,set_hash,adopted_at,adopted_by,source_manifest) VALUES ('$id','h','t','user','[]')")
        }
        File(root, ".git/refs/heads").mkdirs()
        File(root, ".git/HEAD").writeText("ref: refs/heads/main\n")
        File(root, ".git/refs/heads/main").writeText("$head\n")
        File(root, ".git/objects/aa").mkdirs()
        File(root, ".git/objects/aa/${head.drop(2)}").writeText("commit-bytes")
        File(root, "aidos.toml").writeText("name = \"$name\"\n")
        File(root, "src").mkdirs(); File(root, "src/main.txt").writeText("hello")
        File(root, ".aidos/blobs").mkdirs(); File(root, ".aidos/blobs/ab12").writeBytes(ByteArray(5000) { it.toByte() })
        File(root, ".aidos/instance.lock").writeText("lock")
        File(root, ".aidos/index").mkdirs(); File(root, ".aidos/index/derived").writeText("x")
        return root
    }

    private fun exportTo(root: File, name: String = "out.aidos-project", configure: (ExportOptions) -> Unit = {}): File =
        File(tmp, name).also { out -> ProjectExporter.export(root, ExportOptions(output = out, exportedBy = "juha", actorId = "juha", deviceId = "dev")) }

    private fun importOpts(archive: File, target: String, reg: Registry, password: String? = null, policy: ConflictPolicy = ConflictPolicy.REFUSE,
                           requireSig: Boolean = false, trusted: Set<String> = emptySet()) =
        ImportOptions(archive = archive, targetDir = File(tmp, target), registry = reg, password = password?.toCharArray(),
            onConflict = policy, requireSignature = requireSig, trustedSigners = trusted, newId = { "new-${counter++}" }, actorId = "juha", deviceId = "dev")

    private fun code(block: () -> Unit): String = assertFailsWith<PortabilityException> { block() }.code

    private fun rewriteZip(src: File, dst: File, mutate: (String, ByteArray) -> ByteArray?, extra: Map<String, ByteArray> = emptyMap()) {
        ZipFile(src).use { z ->
            ZipOutputStream(FileOutputStream(dst)).use { out ->
                for (e in z.entries()) {
                    val bytes = mutate(e.name, z.getInputStream(e).readBytes()) ?: continue
                    out.putNextEntry(ZipEntry(e.name)); out.write(bytes); out.closeEntry()
                }
                for ((n, b) in extra) { out.putNextEntry(ZipEntry(n)); out.write(b); out.closeEntry() }
            }
        }
    }

    // ── Export format ────────────────────────────────────────────────────────

    @Test fun `full export has metadata, manifest, git head and excludes lock and derived index`() {
        val root = makeProject()
        val archive = exportTo(root)
        ZipFile(archive).use { z ->
            val names = z.entries().asSequence().map { it.name }.toSet()
            assertTrue("metadata.json" in names && "manifest.json" in names)
            assertTrue("project/.git/HEAD" in names && "project/aidos.toml" in names && "project/.aidos/state.db" in names && "project/.aidos/blobs/ab12" in names)
            assertFalse(names.any { it.contains("instance.lock") || it.contains(".aidos/index") })
            val md = ArchiveFormat.json.decodeFromString<ExportMetadata>(z.getInputStream(z.getEntry("metadata.json")).readBytes().decodeToString())
            assertEquals(head, md.gitHead); assertEquals("proj-1", md.projectId); assertEquals("1.0", md.formatVersion)
            assertEquals(1, md.storageSchemaVersion); assertFalse(md.encrypted)
        }
    }

    @Test fun `export works beside an open database and audits itself in the source project`() {
        val root = makeProject()
        val db = File(root, ".aidos/state.db")
        val r = Sqlite.use(db) { c ->
            exec(c, "PRAGMA journal_mode=WAL")
            ProjectExporter.export(root, ExportOptions(output = File(tmp, "o.aidos-project"), actorId = "juha", deviceId = "dev"))
        }
        assertTrue(r.warnings.isEmpty(), r.warnings.toString())
        val kinds = Sqlite.use(db, readOnly = true) { c -> c.createStatement().executeQuery("SELECT kind FROM audit_log ORDER BY sequence").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
        assertEquals(listOf("CapabilityGranted", "ProjectExported"), kinds)
    }

    @Test fun `export refuses a non-project and an existing output`() {
        assertEquals("export.not_a_project", code { ProjectExporter.export(File(tmp, "nothing").apply { mkdirs() }, ExportOptions(File(tmp, "x"))) })
        val root = makeProject(); val out = exportTo(root)
        assertEquals("export.output_exists", code { ProjectExporter.export(root, ExportOptions(out)) })
    }

    // ── Round trip ───────────────────────────────────────────────────────────

    @Test fun `round trip restores content, git, blobs and registers the project`() {
        val root = makeProject(); val archive = exportTo(root)
        val reg = Registry()
        val r = ProjectImporter.import(importOpts(archive, "restored", reg))
        val dst = File(tmp, "restored")
        assertEquals(dst.absoluteFile, r.projectPath.absoluteFile)
        assertEquals("hello", File(dst, "src/main.txt").readText())
        assertEquals("ref: refs/heads/main\n", File(dst, ".git/HEAD").readText())
        assertTrue(File(root, ".aidos/blobs/ab12").readBytes().contentEquals(File(dst, ".aidos/blobs/ab12").readBytes()))
        assertEquals(dst.absolutePath, reg.map["proj-1"])
        assertEquals(head, r.gitHead); assertEquals(1, r.schemaFrom); assertEquals(1, r.schemaTo)
        assertEquals(dst.absolutePath, Sqlite.use(File(dst, ".aidos/state.db"), readOnly = true) { c -> c.createStatement().executeQuery("SELECT root_path FROM projects").use { it.next(); it.getString(1) } })
    }

    @Test fun `import leaves no staging directory behind`() {
        val archive = exportTo(makeProject())
        ProjectImporter.import(importOpts(archive, "r1", Registry()))
        assertTrue(tmp.listFiles()!!.none { it.name.startsWith(".aidos-import-") })
    }

    @Test fun `import revokes grants and adoptions and records a ProjectImported audit entry`() {
        val archive = exportTo(makeProject())
        ProjectImporter.import(importOpts(archive, "r2", Registry()))
        Sqlite.use(File(tmp, "r2/.aidos/state.db"), readOnly = true) { c ->
            fun one(sql: String) = c.createStatement().executeQuery(sql).use { it.next(); it.getString(1) }
            assertEquals("import", one("SELECT revoked_by FROM capabilities"))
            assertEquals("4", one("SELECT epoch FROM project_revocation_epoch"))
            assertEquals("0", one("SELECT COUNT(*) FROM instruction_adoptions"))
            assertEquals("ProjectImported", one("SELECT kind FROM audit_log ORDER BY sequence DESC LIMIT 1"))
        }
    }

    // ── Conflicts ────────────────────────────────────────────────────────────

    @Test fun `default policy refuses an already registered project and an occupied directory`() {
        val archive = exportTo(makeProject())
        val reg = Registry(); ProjectImporter.import(importOpts(archive, "c1", reg))
        assertEquals("import.conflict", code { ProjectImporter.import(importOpts(archive, "c2", reg)) })
        assertEquals("import.conflict", code { ProjectImporter.import(importOpts(archive, "c1", Registry())) })
        assertEquals(1, reg.map.size)
    }

    @Test fun `rename imports a copy with a new id, name and directory and rewrites project ids`() {
        val archive = exportTo(makeProject())
        val reg = Registry(); ProjectImporter.import(importOpts(archive, "d1", reg))
        val r = ProjectImporter.import(importOpts(archive, "d1", reg, policy = ConflictPolicy.RENAME))
        assertTrue(r.renamed); assertNotEquals("proj-1", r.projectId); assertEquals("myapp (imported)", r.projectName)
        assertEquals("d1-imported", r.projectPath.name)
        Sqlite.use(File(r.projectPath, ".aidos/state.db"), readOnly = true) { c ->
            for (t in listOf("audit_log", "capabilities", "project_revocation_epoch", "instruction_adoptions"))
                assertEquals("0", c.createStatement().executeQuery("SELECT COUNT(*) FROM $t WHERE project_id = 'proj-1'").use { it.next(); it.getString(1) }, t)
            assertEquals(r.projectId, c.createStatement().executeQuery("SELECT id FROM projects").use { it.next(); it.getString(1) })
        }
        assertEquals(2, reg.map.size)
    }

    @Test fun `replace moves the existing directory aside instead of deleting it`() {
        val archive = exportTo(makeProject())
        val reg = Registry(); ProjectImporter.import(importOpts(archive, "e1", reg))
        File(tmp, "e1/src/main.txt").writeText("local edits")
        val r = ProjectImporter.import(importOpts(archive, "e1", reg, policy = ConflictPolicy.REPLACE))
        assertEquals("hello", File(tmp, "e1/src/main.txt").readText())
        assertEquals("local edits", File(r.replacedPath!!, "src/main.txt").readText())
    }

    // ── Integrity ────────────────────────────────────────────────────────────

    @Test fun `a modified payload file is rejected and nothing is placed`() {
        val archive = exportTo(makeProject()); val bad = File(tmp, "bad.aidos-project")
        rewriteZip(archive, bad, { n, b -> if (n == "project/src/main.txt") "HELLO".toByteArray() else b })
        val reg = Registry()
        assertEquals("import.checksum", code { ProjectImporter.import(importOpts(bad, "t1", reg)) })
        assertFalse(File(tmp, "t1").exists()); assertTrue(reg.map.isEmpty())
        assertTrue(tmp.listFiles()!!.none { it.name.startsWith(".aidos-import-") })
    }

    @Test fun `an added or removed file is rejected`() {
        val archive = exportTo(makeProject())
        val added = File(tmp, "added.aidos-project"); rewriteZip(archive, added, { _, b -> b }, mapOf("project/evil.sh" to "x".toByteArray()))
        assertEquals("import.checksum", code { ProjectImporter.import(importOpts(added, "t2", Registry())) })
        val removed = File(tmp, "removed.aidos-project"); rewriteZip(archive, removed, { n, b -> if (n == "project/aidos.toml") null else b })
        assertEquals("import.checksum", code { ProjectImporter.import(importOpts(removed, "t3", Registry())) })
    }

    @Test fun `zip-slip and absolute entry names are rejected`() {
        val archive = exportTo(makeProject())
        for ((i, name) in listOf("project/../../escape.txt", "/etc/passwd", "project/a\\b").withIndex()) {
            val evil = File(tmp, "evil$i.aidos-project"); rewriteZip(archive, evil, { _, b -> b }, mapOf(name to "x".toByteArray()))
            assertEquals("import.unsafe_path", code { ProjectImporter.import(importOpts(evil, "t4-$i", Registry())) }, name)
        }
        assertFalse(File(tmp.parentFile, "escape.txt").exists())
    }

    @Test fun `an entry outside project and the metadata files is rejected`() {
        val archive = exportTo(makeProject()); val evil = File(tmp, "stray.aidos-project")
        rewriteZip(archive, evil, { _, b -> b }, mapOf("stray.txt" to "x".toByteArray()))
        assertEquals("import.corrupt", code { ProjectImporter.import(importOpts(evil, "t5", Registry())) })
    }

    @Test fun `an archive that expands past the limit is rejected`() {
        val archive = exportTo(makeProject())
        val o = ImportOptions(archive, File(tmp, "t6"), Registry(), maxUncompressedBytes = 100)
        assertEquals("import.too_large", code { ProjectImporter.import(o) })
    }

    @Test fun `a garbage file and an unsupported format version are rejected`() {
        val junk = File(tmp, "junk.aidos-project").apply { writeText("not a zip") }
        assertEquals("import.corrupt", code { ProjectImporter.import(importOpts(junk, "t7", Registry())) })
        val archive = exportTo(makeProject()); val v2 = File(tmp, "v2.aidos-project")
        rewriteZip(archive, v2, { n, b -> if (n == "metadata.json") b.decodeToString().replace("\"1.0\"", "\"2.0\"").toByteArray() else b })
        // metadata edit also breaks the manifest binding; the version gate fires first.
        assertEquals("import.format_unsupported", code { ProjectImporter.import(importOpts(v2, "t8", Registry())) })
        assertTrue(ArchiveFormat.isSupported("1.7")); assertFalse(ArchiveFormat.isSupported("2.0"))
    }

    @Test fun `a database newer than this runtime is refused`() {
        val root = makeProject()
        Sqlite.use(File(root, ".aidos/state.db")) { c -> exec(c, "UPDATE schema_versions SET version = 99") }
        val archive = exportTo(root)
        assertEquals("import.schema_too_new", code { ProjectImporter.import(importOpts(archive, "t9", Registry())) })
    }

    @Test fun `a git HEAD that does not match the export is rejected`() {
        val root = makeProject(); val archive = exportTo(root)
        val bad = File(tmp, "git.aidos-project")
        // Re-sign nothing: change HEAD *and* keep the manifest consistent by rebuilding the project.
        File(root, ".git/refs/heads/main").writeText("b".repeat(40) + "\n")
        val md = ZipFile(archive).use { z -> z.getInputStream(z.getEntry("metadata.json")).readBytes().decodeToString() }
        assertTrue(md.contains(head))
        val second = exportTo(root, "second.aidos-project")
        // exported HEAD is now bbbb; the object is missing but a loose-object-only repo has no packs.
        assertEquals("import.git_mismatch", code { ProjectImporter.import(importOpts(second, "t10", Registry())) })
        assertFalse(bad.exists())
    }

    // ── Encryption ───────────────────────────────────────────────────────────

    @Test fun `encrypted export hides everything and round trips with the password`() {
        val root = makeProject(); val out = File(tmp, "enc.aidos-project")
        val r = ProjectExporter.export(root, ExportOptions(out, password = "correct horse".toCharArray(), kdfIterations = fastKdf))
        assertTrue(r.encrypted)
        val bytes = out.readBytes()
        assertTrue(ArchiveCrypto.isEncrypted(bytes))
        assertFalse(bytes.decodeToString(throwOnInvalidSequence = false).contains("myapp"))
        assertFalse(bytes.decodeToString(throwOnInvalidSequence = false).contains("metadata.json"))
        ProjectImporter.import(importOpts(out, "enc-restored", Registry(), password = "correct horse"))
        assertEquals("hello", File(tmp, "enc-restored/src/main.txt").readText())
    }

    @Test fun `wrong or missing password and tampering are refused`() {
        val out = File(tmp, "enc2.aidos-project")
        ProjectExporter.export(makeProject(), ExportOptions(out, password = "pw".toCharArray(), kdfIterations = fastKdf))
        assertEquals("import.decrypt_failed", code { ProjectImporter.import(importOpts(out, "w1", Registry(), password = "nope")) })
        assertEquals("import.password_required", code { ProjectImporter.import(importOpts(out, "w2", Registry())) })
        val flipped = out.readBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        val bad = File(tmp, "flip.aidos-project").apply { writeBytes(flipped) }
        assertEquals("import.decrypt_failed", code { ProjectImporter.import(importOpts(bad, "w3", Registry(), password = "pw")) })
        val truncated = File(tmp, "trunc.aidos-project").apply { writeBytes(out.readBytes().copyOf((out.length() / 2).toInt())) }
        assertEquals("import.decrypt_failed", code { ProjectImporter.import(importOpts(truncated, "w4", Registry(), password = "pw")) })
    }

    @Test fun `crypto streams survive chunk boundaries and empty input`() {
        for (n in listOf(0, 1, 65535, 65536, 65537, 200_000)) {
            val data = ByteArray(n) { (it * 31).toByte() }
            val enc = java.io.ByteArrayOutputStream(); ArchiveCrypto.encrypt(data.inputStream(), enc, "p".toCharArray(), 100)
            val dec = java.io.ByteArrayOutputStream(); ArchiveCrypto.decrypt(enc.toByteArray().inputStream(), dec, "p".toCharArray())
            assertTrue(data.contentEquals(dec.toByteArray()), "size $n")
        }
    }

    @Test fun `chunk reordering is detected`() {
        val data = ByteArray(200_000) { it.toByte() }
        val enc = java.io.ByteArrayOutputStream(); ArchiveCrypto.encrypt(data.inputStream(), enc, "p".toCharArray(), 100)
        val b = enc.toByteArray()
        val headerLen = java.nio.ByteBuffer.wrap(b, 9, 4).int
        val start = 13 + headerLen
        val chunkTotal = 4 + 65536 + 16
        val swapped = b.copyOf()
        System.arraycopy(b, start + chunkTotal, swapped, start, chunkTotal)
        System.arraycopy(b, start, swapped, start + chunkTotal, chunkTotal)
        assertEquals("import.decrypt_failed", code { ArchiveCrypto.decrypt(swapped.inputStream(), java.io.ByteArrayOutputStream(), "p".toCharArray()) })
    }

    // ── Signing ──────────────────────────────────────────────────────────────

    @Test fun `signed export verifies and reports the signer`() {
        val kp = ArchiveCrypto.generateSigningKeyPair(); val out = File(tmp, "signed.aidos-project")
        ProjectExporter.export(makeProject(), ExportOptions(out, signingKey = kp))
        val r = ProjectImporter.import(importOpts(out, "s1", Registry(), requireSig = true, trusted = setOf(ArchiveCrypto.fingerprint(kp.public))))
        assertEquals(ArchiveCrypto.fingerprint(kp.public), r.signerFingerprint)
    }

    @Test fun `unsigned archive is refused when a signature is required, accepted otherwise with a warning`() {
        val archive = exportTo(makeProject())
        assertEquals("import.unsigned", code { ProjectImporter.import(importOpts(archive, "s2", Registry(), requireSig = true)) })
        val r = ProjectImporter.import(importOpts(archive, "s3", Registry()))
        assertNull(r.signerFingerprint); assertTrue(r.warnings.any { it.contains("unsigned") })
    }

    @Test fun `untrusted signer and a tampered manifest are refused`() {
        val kp = ArchiveCrypto.generateSigningKeyPair(); val other = ArchiveCrypto.generateSigningKeyPair()
        val out = File(tmp, "signed2.aidos-project"); ProjectExporter.export(makeProject(), ExportOptions(out, signingKey = kp))
        assertEquals("import.untrusted_signer", code { ProjectImporter.import(importOpts(out, "s4", Registry(), trusted = setOf(ArchiveCrypto.fingerprint(other.public)))) })
        // Swap in a manifest whose content differs: signature no longer verifies.
        val forged = File(tmp, "forged.aidos-project")
        rewriteZip(out, forged, { n, b -> if (n == "manifest.json") b.decodeToString().replace("\"files\"", "\"files\" ").toByteArray() else b })
        assertEquals("import.signature", code { ProjectImporter.import(importOpts(forged, "s5", Registry())) })
        // Stripping the signature of an archive whose metadata says signed.
        val stripped = File(tmp, "stripped.aidos-project"); rewriteZip(out, stripped, { n, b -> if (n == "signature.json") null else b })
        assertEquals("import.signature", code { ProjectImporter.import(importOpts(stripped, "s6", Registry())) })
    }

    @Test fun `key encoding round trips`() {
        val kp = ArchiveCrypto.generateSigningKeyPair()
        val pub = ArchiveCrypto.decodePublic(ArchiveCrypto.encodePublic(kp.public)); val priv = ArchiveCrypto.decodePrivate(ArchiveCrypto.encodePrivate(kp.private))
        assertTrue(ArchiveCrypto.verify(pub, "x".toByteArray(), ArchiveCrypto.sign(priv, "x".toByteArray())))
    }

    // ── Scope ────────────────────────────────────────────────────────────────

    @Test fun `nothing outside the project directory is ever exported`() {
        val root = makeProject()
        File(tmp, "secrets-vault.db").writeText("TOP-SECRET")
        val archive = exportTo(root)
        ZipFile(archive).use { z -> assertTrue(z.entries().asSequence().none { it.name.contains("secrets") || it.name.contains("vault") }) }
        assertFalse(archive.readBytes().decodeToString(throwOnInvalidSequence = false).contains("TOP-SECRET"))
    }
}
