package dev.aidos.portability

import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.KeyPair
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExportOptions(
    val output: File,
    val password: CharArray? = null,
    val signingKey: KeyPair? = null,
    val exportedBy: String = "unknown",
    val aidosVersion: String = "0.1.0",
    val compression: Int = 6,
    val overwrite: Boolean = false,
    val actorId: String = exportedBy,
    val deviceId: String = "unknown",
    val kdfIterations: Int = ArchiveCrypto.DEFAULT_ITERATIONS,
    val nowIso: () -> String = { Instant.now().toString() },
    val newId: () -> String = { UUID.randomUUID().toString() },
)

data class ExportResult(
    val file: File,
    val projectId: String,
    val projectName: String,
    val files: Int,
    val payloadBytes: Long,
    val archiveBytes: Long,
    val gitHead: String?,
    val encrypted: Boolean,
    val signerFingerprint: String?,
    val warnings: List<String>,
)

/**
 * RFC-0041 full export.
 *
 * Consistency: the RFC says "acquire a read lock on project storage". A project the runtime has
 * open holds the exclusive ProjectLock (RFC-0055), so export must work *beside* a running
 * runtime; `state.db` is therefore copied with `VACUUM INTO`, a transactionally consistent
 * snapshot, and no lock is taken. Git and blob files are append-only (content-addressed), so a
 * file read mid-write is the only hazard and the manifest hashes the bytes actually written.
 *
 * Never included: anything outside the project directory (vault, device identity, user.db,
 * models), `.aidos/instance.lock`, and `.aidos/index/` (derived, rebuildable).
 */
object ProjectExporter {

    fun export(projectRoot: File, options: ExportOptions): ExportResult {
        val stateDb = File(projectRoot, ".aidos/state.db")
        if (!projectRoot.isDirectory || !stateDb.isFile) {
            throw PortabilityException("export.not_a_project", "${projectRoot.path} is not an Aidos project (no .aidos/state.db)")
        }
        if (options.compression !in 0..9) throw PortabilityException("export.bad_option", "compression must be 0-9")
        val out = options.output.absoluteFile
        if (out.exists() && !options.overwrite) {
            throw PortabilityException("export.output_exists", "${out.path} already exists")
        }
        out.parentFile.mkdirs()

        val project = Sqlite.project(stateDb)
        val work = Files.createTempDirectory(out.parentFile.toPath(), ".aidos-export-").toFile()
        try {
            val snapshot = File(work, "state.db")
            Sqlite.snapshot(stateDb, snapshot)
            val schemaVersion = Sqlite.schemaVersion(snapshot)
                ?: throw PortabilityException("export.no_schema", "state.db has no schema version")

            val plainZip = File(work, "archive.zip")
            val entries = mutableListOf<ManifestEntry>()
            var payloadBytes = 0L
            ZipOutputStream(FileOutputStream(plainZip).buffered()).use { zip ->
                zip.setLevel(if (options.compression == 0) Deflater.NO_COMPRESSION else options.compression)
                for ((rel, file) in collect(projectRoot, snapshot)) {
                    val e = writeEntry(zip, ArchiveFormat.PAYLOAD_PREFIX + rel, file)
                    entries += e; payloadBytes += e.sizeBytes
                }

                val gitHead = resolveGitHead(projectRoot)
                val metadata = ExportMetadata(
                    formatVersion = ArchiveFormat.FORMAT_VERSION,
                    aidosVersion = options.aidosVersion,
                    projectId = project.id,
                    projectName = project.name,
                    exportedAt = options.nowIso(),
                    exportedBy = options.exportedBy,
                    storageSchemaVersion = schemaVersion,
                    gitHead = gitHead,
                    encrypted = options.password != null,
                    signed = options.signingKey != null,
                    sizeBytes = payloadBytes,
                    files = entries.size,
                )
                val metadataBytes = ArchiveFormat.json.encodeToString(metadata).toByteArray(Charsets.UTF_8)
                val manifest = ExportManifest(
                    files = entries.sortedBy { it.path },
                    totalChecksum = ArchiveFormat.totalChecksum(entries),
                    metadataChecksumSha256 = ArchiveFormat.sha256Hex(metadataBytes),
                )
                val manifestBytes = ArchiveFormat.json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
                putBytes(zip, ArchiveFormat.METADATA, metadataBytes)
                putBytes(zip, ArchiveFormat.MANIFEST, manifestBytes)
                options.signingKey?.let { kp ->
                    val sig = SignatureFile(
                        publicKey = ArchiveCrypto.encodePublic(kp.public),
                        signature = java.util.Base64.getEncoder().encodeToString(ArchiveCrypto.sign(kp.private, manifestBytes)),
                    )
                    putBytes(zip, ArchiveFormat.SIGNATURE, ArchiveFormat.json.encodeToString(sig).toByteArray(Charsets.UTF_8))
                }
            }

            val finished = File(work, "final.part")
            val password = options.password
            if (password == null) {
                Files.move(plainZip.toPath(), finished.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                plainZip.inputStream().buffered().use { i ->
                    FileOutputStream(finished).buffered().use { o -> ArchiveCrypto.encrypt(i, o, password, options.kdfIterations) }
                }
            }
            moveInto(finished, out)

            val warnings = mutableListOf<String>()
            val gitHead = resolveGitHead(projectRoot)
            // Audited in the source project *after* the snapshot, so the archive does not contain
            // its own export record. A failed audit write must not delete a finished archive.
            try {
                Sqlite.use(stateDb) { c ->
                    Sqlite.appendAudit(
                        c, options.newId(), project.id, options.nowIso(), "ProjectExported",
                        options.actorId, options.deviceId,
                        """{"files":${entries.size},"encrypted":${password != null},"signed":${options.signingKey != null}}""",
                    )
                }
            } catch (e: Exception) {
                warnings += "export audit entry could not be written: ${e.message}"
            }

            return ExportResult(
                file = out, projectId = project.id, projectName = project.name, files = entries.size,
                payloadBytes = payloadBytes, archiveBytes = out.length(), gitHead = gitHead,
                encrypted = password != null,
                signerFingerprint = options.signingKey?.let { ArchiveCrypto.fingerprint(it.public) },
                warnings = warnings,
            )
        } finally {
            work.deleteRecursively()
        }
    }

    private fun moveInto(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** (archive-relative path, source file), sorted so identical projects export identically. */
    private fun collect(root: File, snapshot: File): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()
        val base = root.toPath()
        Files.walk(base).use { stream ->
            stream.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.forEach { p ->
                val rel = base.relativize(p).joinToString("/") { it.toString() }
                when {
                    rel == ".aidos/state.db" -> result += rel to snapshot
                    rel.startsWith(".aidos/blobs/") -> result += rel to p.toFile()
                    rel.startsWith(".aidos/") -> Unit // lock, WAL/SHM, derived index
                    else -> result += rel to p.toFile()
                }
            }
        }
        return result.sortedBy { it.first }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, file: File): ManifestEntry {
        val digest = MessageDigest.getInstance("SHA-256")
        zip.putNextEntry(ZipEntry(name).apply { time = 0 }) // fixed time: reproducible archives
        var size = 0L
        val counting = object : OutputStream() {
            override fun write(b: Int) { zip.write(b); size++ }
            override fun write(b: ByteArray, off: Int, len: Int) { zip.write(b, off, len); size += len }
        }
        file.inputStream().use { i -> i.copyTo(DigestOutputStream(counting, digest)) }
        zip.closeEntry()
        return ManifestEntry(name.removePrefix(ArchiveFormat.PAYLOAD_PREFIX), size, ArchiveFormat.hex(digest.digest()))
    }

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0 })
        zip.write(bytes)
        zip.closeEntry()
    }
}
