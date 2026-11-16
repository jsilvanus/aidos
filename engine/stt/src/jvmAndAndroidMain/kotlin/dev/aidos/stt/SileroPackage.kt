package dev.aidos.stt

import java.io.File

/**
 * Where the Silero VAD graph lives. A Whisper package picks up a `silero_vad.onnx` placed in its own directory;
 * otherwise [SttPackages] uses one shared copy at `<modelsDir>/silero-vad/silero_vad.onnx`, so a phone stores the
 * 2 MB graph once for every Whisper model. Without either file the model runs as before, cutting at the
 * quietest point near 30 s. The file is `onnx/model.onnx` of the Hugging Face repo `onnx-community/silero-vad`
 * (v5, MIT) renamed, or the `silero_vad.onnx` of the `snakers4/silero-vad` repo.
 */
object SileroPackage {
    const val FILE = "silero_vad.onnx"
    const val SHARED_DIR = "silero-vad"

    fun shared(modelsDir: File): File? = File(File(modelsDir, SHARED_DIR), FILE).takeIf { it.isFile }
}
