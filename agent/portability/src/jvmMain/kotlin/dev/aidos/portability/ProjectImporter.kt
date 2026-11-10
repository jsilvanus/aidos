package dev.aidos.portability

import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DatabaseKind
import dev.aidos.storage.OpenResult
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipFile

/** What the importer needs from the user-scope project registry (RFC-0054), kept as a port. */
interface ProjectRegistryPort {
    fun isRegistered(projectId: String): Boolean
    fun register(projectId: String, path: String, nowIso: String)
}

/** RFC-0041 Conflict Handling. REFUSE is the default and the only one that never touches anything. */
enum class ConflictPolicy {
    /** Error if the project id is registered or the target directory exists. */
    REFUSE,

    /** Import as a copy: new project id and name, and a free directory name if the target is taken. */
    RENAME,

    /** Move the existing target directory aside (`<dir>.replaced-<time>`, never deleted) and import. */
    REPLACE,
}

class ImportOptions(
    val archive: File,
    val targetDir: File,
    val registry: ProjectRegistryPort,
    val password: CharArray? = null,
    val onConflict: ConflictPolicy = ConflictPolicy.REFUSE,
    val newName: String? = null,
    val requireSignature: Boolean = false,
    /** SHA-256 fingerprints ([ArchiveCrypto.fingerprint]); when non-empty the signer must be listed. */
    val trustedSigners: Set<String> = emptySet(),
    val runtimeVersion: String = "0.1.0",
    val actorId: String = "unknown",
    val deviceId: String = "unknown",
    val maxUncompressedBytes: Long = 256L * 1024 * 1024 * 1024,
    val nowIso: () -> String = { Instant.now().toString() },
    val newId: () -> String = { UUID.randomUUID().toString() },
)

data class ImportResult(
    val projectId: String,
    val projectName: String,
    val projectPath: File,
    val files: Int,
    val gitHead: String?,
    val schemaFrom: Int,
    val schemaTo: Int,
    val signerFingerprint: String?,
    val renamed: Boolean,
    val replacedPath: File?,
    val warnings: List<String>,
)

/**
 * RFC-0041 import. Everything is verified in a staging directory next to the target; nothing
 * appears at the target (or in the registry) until every check has passed.
 *
 * An archive is foreign input even when it is the user's own: after verification the importer
 * revokes every capability grant and every instruction/MCP adoption recorded in the archive's
 * database. Authority and admission are decisions of *this* device's user (RFC-0003, RFC-0016,
 * RFC-0031), and a file must not be able to carry them in.
 */
object ProjectImporter {

    fun import(o: ImportOptions): ImportResult {
        if (!o.archive.isFile) throw PortabilityException("import.not_found", "${o.archive.path} does not exist")
        val target = o.targetDir.absoluteFile
        val parent = target.parentFile ?: throw PortabilityException("import.bad_target", "target has no parent directory")
        parent.mkdirs()
        val staging = Files.createTempDirectory(parent.toPath(), ".aidos-import-").toFile()
        try {
            val plain = decryptIfNeeded(o, staging)
            val payload = File(staging, "payload").apply { mkdirs() }
            val meta = File(staging, "meta").apply { mkdirs() }
            val sums = extract(plain, payload, meta, o.maxUncompressedBytes)

            val metadataBytes = File(meta, ArchiveFormat.METADATA).takeIf { it.isFile }?.readBytes()
                ?: throw PortabilityException("import.corrupt", "archive has no metadata.json")
            val manifestBytes = File(meta, ArchiveFormat.MANIFEST).takeIf { it.isFile }?.readBytes()
                ?: throw PortabilityException("import.corrupt", "archive has no manifest.json")
            val metadata = parse<ExportMetadata>(metadataBytes, "metadata.json")
            if (!ArchiveFormat.isSupported(metadata.formatVersion)) {
                throw PortabilityException("import.format_unsupported", "archive format ${metadata.formatVersion} is not supported (this build reads ${ArchiveFormat.FORMAT_VERSION})")
            }
            val manifest = parse<ExportManifest>(manifestBytes, "manifest.json")
            verifyManifest(manifest, metadata, metadataBytes, sums)
            val signer = verifySignature(o, meta, manifestBytes, metadata)

            // Schema: refuse a newer database; open an older one through the migration runner.
            val project = File(payload, "project")
            val stateDb = File(project, ".aidos/state.db")
            if (!stateDb.isFile) throw PortabilityException("import.corrupt", "archive has no .aidos/state.db")
            if (!Sqlite.integrityOk(stateDb)) throw PortabilityException("import.corrupt", "state.db failed the SQLite integrity check")
            val found = Sqlite.schemaVersion(stateDb) ?: throw PortabilityException("import.corrupt", "state.db has no schema version")
            val supported = DatabaseKind.PROJECT.currentVersion
            if (found > supported) {
                throw PortabilityException("import.schema_too_new", "archive database is schema v$found; this runtime supports up to v$supported")
            }
            val opened = AidosStorage.openProject(stateDb.absolutePath, o.runtimeVersion, o.nowIso)
            try {
                if (opened.result is OpenResult.ReadOnly) throw PortabilityException("import.schema_too_new", "database opened read-only")
            } finally {
                opened.driver.close()
            }
            val row = Sqlite.project(stateDb)
            if (row.id != metadata.projectId) throw PortabilityException("import.corrupt", "metadata project id does not match state.db")

            metadata.gitHead?.let { head ->
                if (resolveGitHead(project) != head) throw PortabilityException("import.git_mismatch", "git HEAD does not match the exported HEAD $head")
                if (!gitObjectPresent(project, head)) throw PortabilityException("import.git_mismatch", "HEAD commit $head is not in the object store")
            }

            // Conflicts
            val idTaken = o.registry.isRegistered(row.id)
            val dirTaken = target.exists() && (!target.isDirectory || (target.list()?.isNotEmpty() != false))
            var finalTarget = target
            var newId = row.id
            var newName = row.name
            var replaced: File? = null
            var renamed = false
            when (o.onConflict) {
                ConflictPolicy.REFUSE -> {
                    if (idTaken) throw PortabilityException("import.conflict", "project ${row.id} is already registered (use --rename or --replace)")
                    if (dirTaken) throw PortabilityException("import.conflict", "${target.path} already exists (use --rename or --replace)")
                }
                ConflictPolicy.RENAME -> {
                    if (idTaken) { newId = o.newId(); newName = o.newName ?: "${row.name} (imported)"; renamed = true }
                    else if (o.newName != null) { newName = o.newName; renamed = true }
                    if (dirTaken) {
                        var n = 1
                        do { finalTarget = File(parent, target.name + "-imported" + if (n > 1) "-$n" else ""); n++ } while (finalTarget.exists())
                        renamed = true
                    }
                }
                ConflictPolicy.REPLACE -> if (dirTaken) {
                    replaced = File(parent, target.name + ".replaced-" + o.nowIso().replace(Regex("[^0-9A-Za-z]"), ""))
                }
            }

            rewriteForThisDevice(stateDb, row, newId, newName, finalTarget, o, metadata, signer, renamed)

            if (replaced != null) Files.move(target.toPath(), replaced.toPath())
            try {
                if (finalTarget.exists()) finalTarget.delete() // an empty directory; checked above
                Files.move(project.toPath(), finalTarget.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                if (replaced != null && !target.exists()) Files.move(replaced.toPath(), target.toPath())
                throw PortabilityException("import.place_failed", "could not place the project at ${finalTarget.path}: ${e.message}")
            }
            o.registry.register(newId, finalTarget.absolutePath, o.nowIso())

            return ImportResult(
                projectId = newId, projectName = newName, projectPath = finalTarget, files = manifest.files.size,
                gitHead = metadata.gitHead, schemaFrom = found, schemaTo = supported, signerFingerprint = signer,
                renamed = renamed, replacedPath = replaced,
                warnings = buildList {
                    if (signer == null) add("archive is unsigned: origin not verified")
                    add("capability grants, instruction adoptions and MCP adoptions from the archive were revoked; re-grant them on this device")
                },
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    // ── Steps ────────────────────────────────────────────────────────────────

    private fun decryptIfNeeded(o: ImportOptions, staging: File): File {
        val head = o.archive.inputStream().use { it.readNBytes(16) }
        if (!ArchiveCrypto.isEncrypted(head)) return o.archive
        val password = o.password ?: throw PortabilityException("import.password_required", "the archive is encrypted; a password is required")
        val plain = File(staging, "decrypted.zip")
        o.archive.inputStream().buffered().use { i ->
            FileOutputStream(plain).buffered().use { out -> ArchiveCrypto.decrypt(i, out, password) }
        }
        return plain
    }

    /** Extracts with zip-slip and size-bomb guards; returns (entry name → size, sha256) as written. */
    private fun extract(zipFile: File, payload: File, meta: File, maxBytes: Long): Map<String, Pair<Long, String>> {
        val sums = LinkedHashMap<String, Pair<Long, String>>()
        var total = 0L
        val payloadRoot = payload.toPath().normalize()
        try {
            ZipFile(zipFile).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val name = entry.name
                    val isMeta = name == ArchiveFormat.METADATA || name == ArchiveFormat.MANIFEST || name == ArchiveFormat.SIGNATURE
                    if (name.contains('\\') || name.startsWith("/") || name.split('/').any { it == ".." || it.isEmpty() } || name.contains(':')) {
                        throw PortabilityException("import.unsafe_path", "unsafe archive path '$name'")
                    }
                    if (!isMeta && !name.startsWith(ArchiveFormat.PAYLOAD_PREFIX)) {
                        throw PortabilityException("import.corrupt", "unexpected archive entry '$name'")
                    }
                    val dest: Path = if (isMeta) File(meta, name).toPath() else payloadRoot.resolve(name).normalize()
                    if (!isMeta && !dest.startsWith(payloadRoot)) throw PortabilityException("import.unsafe_path", "unsafe archive path '$name'")
                    if (sums.containsKey(name)) throw PortabilityException("import.corrupt", "duplicate archive entry '$name'")
                    Files.createDirectories(dest.parent)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(dest.toFile()).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                size += n; total += n
                                if (total > maxBytes) throw PortabilityException("import.too_large", "archive expands beyond the $maxBytes byte limit")
                                digest.update(buf, 0, n); out.write(buf, 0, n)
                            }
                        }
                    }
                    sums[name] = size to ArchiveFormat.hex(digest.digest())
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw PortabilityException("import.corrupt", "not a valid archive: ${e.message}")
        }
        return sums
    }

    private fun verifyManifest(m: ExportManifest, md: ExportMetadata, metadataBytes: ByteArray, sums: Map<String, Pair<Long, String>>) {
        if (ArchiveFormat.sha256Hex(metadataBytes) != m.metadataChecksumSha256) {
            throw PortabilityException("import.checksum", "metadata.json does not match the manifest")
        }
        val declared = m.files.associateBy { ArchiveFormat.PAYLOAD_PREFIX + it.path }
        if (declared.size != m.files.size) throw PortabilityException("import.checksum", "manifest lists a file twice")
        val present = sums.keys.filter { it.startsWith(ArchiveFormat.PAYLOAD_PREFIX) }.toSet()
        (present - declared.keys).firstOrNull()?.let { throw PortabilityException("import.checksum", "'$it' is in the archive but not in the manifest") }
        (declared.keys - present).firstOrNull()?.let { throw PortabilityException("import.checksum", "'$it' is in the manifest but missing from the archive") }
        for ((name, e) in declared) {
            val (size, sha) = sums.getValue(name)
            if (size != e.sizeBytes || sha != e.checksumSha256) throw PortabilityException("import.checksum", "'${e.path}' does not match its checksum")
        }
        if (ArchiveFormat.totalChecksum(m.files) != m.totalChecksum) throw PortabilityException("import.checksum", "manifest total checksum mismatch")
        if (md.files != m.files.size) throw PortabilityException("import.checksum", "metadata file count does not match the manifest")
    }

    /** Returns the signer fingerprint, or null when unsigned (and signing was not required). */
    private fun verifySignature(o: ImportOptions, meta: File, manifestBytes: ByteArray, md: ExportMetadata): String? {
        val sigFile = File(meta, ArchiveFormat.SIGNATURE)
        if (!sigFile.isFile) {
            if (o.requireSignature || o.trustedSigners.isNotEmpty()) throw PortabilityException("import.unsigned", "the archive is not signed")
            if (md.signed) throw PortabilityException("import.signature", "metadata says signed but the signature is missing")
            return null
        }
        val sig = parse<SignatureFile>(sigFile.readBytes(), "signature.json")
        if (sig.algorithm != "ed25519") throw PortabilityException("import.signature", "unsupported signature algorithm ${sig.algorithm}")
        val key = try { ArchiveCrypto.decodePublic(sig.publicKey) } catch (e: Exception) {
            throw PortabilityException("import.signature", "unreadable public key")
        }
        val ok = try { ArchiveCrypto.verify(key, manifestBytes, Base64.getDecoder().decode(sig.signature)) } catch (_: IllegalArgumentException) { false }
        if (!ok) throw PortabilityException("import.signature", "signature does not verify")
        val fp = ArchiveCrypto.fingerprint(key)
        if (o.trustedSigners.isNotEmpty() && fp !in o.trustedSigners) throw PortabilityException("import.untrusted_signer", "signed by an untrusted key ($fp)")
        return fp
    }

    /** Make the staged database this device's: new location/identity, no inherited authority, audited. */
    private fun rewriteForThisDevice(
        stateDb: File, old: ProjectRow, newId: String, newName: String, finalTarget: File,
        o: ImportOptions, md: ExportMetadata, signer: String?, renamed: Boolean,
    ) {
        val now = o.nowIso()
        Sqlite.use(stateDb) { c ->
            c.autoCommit = false
            try {
                if (newId != old.id) {
                    for (t in Sqlite.tablesWithColumn(c, "project_id")) {
                        c.prepareStatement("UPDATE \"$t\" SET project_id = ? WHERE project_id = ?").use { ps ->
                            ps.setString(1, newId); ps.setString(2, old.id); ps.executeUpdate()
                        }
                    }
                    c.prepareStatement("UPDATE projects SET id = ? WHERE id = ?").use { ps ->
                        ps.setString(1, newId); ps.setString(2, old.id); ps.executeUpdate()
                    }
                }
                c.prepareStatement("UPDATE projects SET name = ?, root_path = ?, updated_at = ? WHERE id = ?").use { ps ->
                    ps.setString(1, newName); ps.setString(2, finalTarget.absolutePath); ps.setString(3, now); ps.setString(4, newId); ps.executeUpdate()
                }
                c.prepareStatement("UPDATE capabilities SET revoked_at = ?, revoked_by = 'import' WHERE revoked_at IS NULL").use { ps ->
                    ps.setString(1, now); ps.executeUpdate()
                }
                c.prepareStatement("UPDATE project_revocation_epoch SET epoch = epoch + 1").use { it.executeUpdate() }
                for (t in listOf("instruction_adoptions", "mcp_operation_adoptions")) {
                    c.createStatement().use { it.executeUpdate("DELETE FROM $t") }
                }
                val detail = ArchiveFormat.json.encodeToString(
                    mapOf(
                        "source_project_id" to old.id,
                        "exported_at" to md.exportedAt,
                        "exported_by" to md.exportedBy,
                        "signer" to (signer ?: ""),
                        "renamed" to renamed.toString(),
                    ),
                )
                Sqlite.appendAudit(c, o.newId(), newId, now, "ProjectImported", o.actorId, o.deviceId, detail)
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    private inline fun <reified T> parse(bytes: ByteArray, what: String): T =
        try {
            ArchiveFormat.json.decodeFromString<T>(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw PortabilityException("import.corrupt", "$what is not valid: ${e.message}")
        }
}
