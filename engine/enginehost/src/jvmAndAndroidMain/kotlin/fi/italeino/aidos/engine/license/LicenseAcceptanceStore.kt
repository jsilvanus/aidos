package fi.italeino.aidos.engine.license

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * License/ToS acceptance record per model+version (RFC-0103, Data Model section).
 *
 * Users must explicitly accept a model's license/ToS before downloading or using it.
 * Acceptance is recorded once per model+version and re-shown only if the license text changes.
 *
 * @param modelId The model identifier (e.g., "qwen2.5-3b-instruct-q4_k_m")
 * @param modelVersion The model version (e.g., "1.0", or "" for unversioned)
 * @param licenseTextDigest SHA-256 digest of the license text when accepted.
 *                         If the text changes, a new digest triggers re-prompt.
 * @param acceptedAt Timestamp when acceptance occurred
 */
@Serializable
data class LicenseAcceptanceRecord(
    val modelId: String,
    val modelVersion: String,
    val licenseTextDigest: String,
    val acceptedAt: String
)

/**
 * Persistent storage for per-model license acceptance decisions (RFC-0103).
 *
 * Backed by encrypted shared preferences on Android, not in-memory,
 * so acceptances survive Engine restarts.
 *
 * Used by the Engine's acquire flow to gate model downloads and by inference
 * to gate execution (once downloaded and accepted).
 */
interface LicenseAcceptanceStore {
    /**
     * Check if a model+version has been accepted by the user.
     *
     * @param modelId The model identifier
     * @param modelVersion The model version
     * @param licenseTextDigest The current digest of the license text
     *
     * @return true if acceptance exists and digest matches (user approved this exact text)
     *         false if acceptance never occurred, or if digest changed (re-prompt needed)
     */
    suspend fun isAccepted(
        modelId: String,
        modelVersion: String,
        licenseTextDigest: String
    ): Boolean

    /**
     * Record that a user has accepted a model's license/ToS.
     *
     * @param modelId The model identifier
     * @param modelVersion The model version
     * @param licenseTextDigest SHA-256 digest of the license text accepted
     *
     * @return the new acceptance record
     */
    suspend fun accept(
        modelId: String,
        modelVersion: String,
        licenseTextDigest: String
    ): LicenseAcceptanceRecord

    /**
     * Get the acceptance record for a model+version, if it exists.
     *
     * @return the record, or null if never accepted or digest mismatch
     */
    suspend fun getAcceptance(
        modelId: String,
        modelVersion: String
    ): LicenseAcceptanceRecord?

    /**
     * List all acceptance records (for audit/settings views).
     */
    suspend fun listAllAcceptances(): List<LicenseAcceptanceRecord>

    /**
     * Watch for changes to the acceptance list.
     */
    fun watchAcceptances(): Flow<List<LicenseAcceptanceRecord>>

    /**
     * Revoke acceptance for a model+version (user can withdraw consent).
     * Future inference attempts will require acceptance again.
     */
    suspend fun revoke(modelId: String, modelVersion: String): Boolean

    /**
     * Delete all acceptance records (for testing or uninstall).
     */
    suspend fun clear()
}

/**
 * Exception thrown when a model requires license acceptance before use.
 *
 * Inference code catches this and surface it to the user for approval.
 */
class LicenseNotAcceptedException(
    val modelId: String,
    val modelVersion: String,
    val licenseText: String,
    val licenseTextDigest: String
) : RuntimeException("License acceptance required for $modelId $modelVersion")
