package dev.aidos.portability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * RFC-0041 archive layout (`.aidos-project`, a ZIP):
 *
 * ```
 * project/...        the project directory, relative paths preserved (.git, aidos.toml, content,
 *                    .aidos/state.db snapshot, .aidos/blobs)
 * metadata.json      ExportMetadata
 * manifest.json      ExportManifest: sha256 of every project/ file, total checksum
 * signature.json     optional: Ed25519 over the exact bytes of manifest.json
 * ```
 *
 * Deviation from the RFC's sketch, forced by RFC-0054: runtime state lives in `<project>/.aidos/`,
 * so the archive mirrors the project directory instead of splitting `git/`, `storage.db` and
 * `artifacts/`. Encryption wraps the whole ZIP ([ArchiveCrypto]), so even metadata.json is hidden.
 */
object ArchiveFormat {
    const val FORMAT_VERSION = "1.0"
    const val PAYLOAD_PREFIX = "project/"
    const val METADATA = "metadata.json"
    const val MANIFEST = "manifest.json"
    const val SIGNATURE = "signature.json"
    const val EXTENSION = ".aidos-project"

    val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    /** Only the major part must match: 1.x readers accept any 1.y archive. */
    fun isSupported(formatVersion: String): Boolean =
        formatVersion.substringBefore('.') == FORMAT_VERSION.substringBefore('.')

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Sorted `path \t size \t sha256` lines, hashed: one value that covers the whole file set. */
    fun totalChecksum(entries: List<ManifestEntry>): String =
        sha256Hex(
            entries.sortedBy { it.path }
                .joinToString("") { "${it.path}\t${it.sizeBytes}\t${it.checksumSha256}\n" }
                .toByteArray(Charsets.UTF_8),
        )
}

@Serializable
data class ExportMetadata(
    @SerialName("format_version") val formatVersion: String,
    @SerialName("aidos_version") val aidosVersion: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("project_name") val projectName: String,
    @SerialName("exported_at") val exportedAt: String,
    @SerialName("exported_by") val exportedBy: String,
    @SerialName("storage_schema_version") val storageSchemaVersion: Int,
    @SerialName("git_head") val gitHead: String? = null,
    @SerialName("export_type") val exportType: String = "full",
    val encrypted: Boolean = false,
    val signed: Boolean = false,
    @SerialName("size_bytes") val sizeBytes: Long,
    val files: Int,
)

@Serializable
data class ManifestEntry(
    val path: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("checksum_sha256") val checksumSha256: String,
)

@Serializable
data class ExportManifest(
    val files: List<ManifestEntry>,
    @SerialName("total_checksum") val totalChecksum: String,
    /** metadata.json is not a payload file; its hash is bound here so the signature covers it. */
    @SerialName("metadata_checksum_sha256") val metadataChecksumSha256: String,
)

@Serializable
data class SignatureFile(
    val algorithm: String = "ed25519",
    /** X.509 SubjectPublicKeyInfo, base64. */
    @SerialName("public_key") val publicKey: String,
    /** Over the exact bytes of manifest.json, base64. */
    val signature: String,
)

/** A refusal with a stable code (RFC-0029 style); never a bare exception message to parse. */
class PortabilityException(val code: String, message: String) : Exception(message)
