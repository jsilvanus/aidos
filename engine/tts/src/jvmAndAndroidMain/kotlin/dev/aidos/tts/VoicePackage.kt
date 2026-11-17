package dev.aidos.tts

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A text-to-speech voice package is a directory holding a Piper voice and the manifest that says how to read text
 * for it (RFC-0117 amendment):
 *
 *     <voiceId>/
 *         aidos-tts.json          {"language": "fi" | "en", "phonemizer": "finnish-rules" | "cmudict"}
 *         <name>.onnx             the Piper VITS graph (the only .onnx in the directory)
 *         <name>.onnx.json        the Piper config (sample rate, scales, phoneme_id_map)
 *         cmudict.dict            English only: the CMU Pronouncing Dictionary, fetched with the package
 *
 * A package is data only; nothing in it is code. The manifest is the single source of truth for the phonemizer, so a
 * voice never guesses its language.
 */
class VoicePackage private constructor(
    val dir: File,
    val language: String,
    val phonemizerName: String,
    val model: File,
    val config: File,
    val dictionary: File?,
) {
    /** Builds the phonemizer this package asks for. English loads its dictionary file from the package. */
    fun phonemizer(): Phonemizer = when (phonemizerName) {
        PHONEMIZER_FINNISH -> FinnishPhonemizer()
        PHONEMIZER_CMUDICT -> {
            val file = dictionary ?: throw IllegalStateException("cmudict voice has no cmudict.dict: ${dir.path}")
            EnglishPhonemizer(EnglishPhonemizer.parseCmudict(file.readText(Charsets.UTF_8)))
        }
        else -> throw IllegalStateException("unknown phonemizer $phonemizerName in ${dir.path}")
    }

    companion object {
        const val MANIFEST = "aidos-tts.json"
        const val PHONEMIZER_FINNISH = "finnish-rules"
        const val PHONEMIZER_CMUDICT = "cmudict"
        const val DICTIONARY = "cmudict.dict"

        /** The package at [dir], or null when the directory is not a complete voice package. */
        fun open(dir: File): VoicePackage? {
            if (!dir.isDirectory) return null
            val manifest = File(dir, MANIFEST)
            if (!manifest.isFile) return null
            val models = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }.orEmpty()
            val model = models.singleOrNull() ?: return null
            val config = File(dir, "${model.name}.json").takeIf { it.isFile } ?: return null
            val json = Json.parseToJsonElement(manifest.readText(Charsets.UTF_8)).jsonObject
            val phonemizer = json["phonemizer"]?.jsonPrimitive?.content ?: return null
            val language = json["language"]?.jsonPrimitive?.content ?: return null
            val dictionary = File(dir, DICTIONARY).takeIf { it.isFile }
            if (phonemizer == PHONEMIZER_CMUDICT && dictionary == null) return null
            return VoicePackage(dir, language, phonemizer, model, config, dictionary)
        }

        /** Total size of the files a voice needs, for the model list. */
        fun sizeBytes(dir: File): Long = dir.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
    }
}
