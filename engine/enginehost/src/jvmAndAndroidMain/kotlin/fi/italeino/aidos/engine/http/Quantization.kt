package fi.italeino.aidos.engine.http

/** Quantization scheme named in a model id/filename, or null when it names none (never guessed). */
internal fun deriveQuantization(modelId: String): String? {
    val lowered = modelId.lowercase()
    return when {
        lowered.contains("q2_k") -> "q2_k"
        lowered.contains("q3_k") -> "q3_k"
        lowered.contains("q4_k_m") -> "q4_k_m"
        lowered.contains("q4_k_s") -> "q4_k_s"
        lowered.contains("q4_0") -> "q4_0"
        lowered.contains("q5_k_m") -> "q5_k_m"
        lowered.contains("q5_k_s") -> "q5_k_s"
        lowered.contains("q6_k") -> "q6_k"
        lowered.contains("q8_0") -> "q8_0"
        else -> null
    }
}
