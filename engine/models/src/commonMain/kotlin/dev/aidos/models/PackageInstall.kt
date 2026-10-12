package dev.aidos.models

import dev.aidos.huggingface.ModelPackage
import dev.aidos.kernel.ModelKind

/**
 * Install planning for ONNX packages that the desktop and Android hosts both need, so the two
 * Models screens cannot drift apart on what a Hugging Face repo installs as (RFC-0022 D45, RFC-0103).
 */
object PackageInstall {
    /**
     * The package to install for a repo that has no GGUF quantization: a Laya decision package first,
     * then Whisper speech-to-text, then a SmolVLM vision model. Null when the repo has none, in which
     * case this Engine has no backend for it and installing would leave a file it can never load.
     */
    fun choose(packages: List<ModelPackage>): ModelPackage? =
        packages.firstOrNull { it.isDecisionPackage }
            ?: packages.firstOrNull { it.isSpeechPackage }
            ?: packages.firstOrNull { it.isVisionPackage }

    fun kindOf(pkg: ModelPackage): ModelKind = when {
        pkg.isSpeechPackage -> ModelKind.STT
        pkg.isVisionPackage -> ModelKind.VISION
        else -> ModelKind.DECISION
    }

    /** Directory name under the models folder: the loaders find a package by its catalog id, made filesystem-safe. */
    fun directoryName(modelId: String): String = modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Files in install order: the graphs last, so a partial install is never a loadable package. */
    fun toRequest(modelId: String, pkg: ModelPackage, destinationDir: String): ModelPackageDownloadRequest =
        ModelPackageDownloadRequest(
            modelId = modelId,
            files = pkg.files.sortedBy { it.path.endsWith(".onnx") }
                .map { PackageFileRequest(it.path.substringAfterLast('/'), pkg.downloadUrl(it), it.sha256Digest) },
            destinationDir = destinationDir,
            kind = kindOf(pkg),
            format = "onnx",
            backend = "onnx-runtime",
        )

    /** Whether the Engine has a backend for an installed artifact of [format] and [kind]. */
    fun isRunnable(format: String, kind: ModelKind): Boolean =
        format == "gguf" || (format == "onnx" && kind in onnxKinds)

    private val onnxKinds = setOf(ModelKind.DECISION, ModelKind.STT, ModelKind.VISION)
}
