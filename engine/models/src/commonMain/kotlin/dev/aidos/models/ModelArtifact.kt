package dev.aidos.models

/** Runtime-neutral description of a downloadable model artifact. */
data class ModelArtifact(
    val filename: String,
    val format: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L,
    val sha256Digest: String? = null,
    val compatibleBackends: List<String> = emptyList(),
)
