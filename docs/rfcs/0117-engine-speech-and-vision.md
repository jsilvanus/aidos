# RFC-0117: Engine Speech and Vision (STT, vision, voice providers)

Status: Implemented 2026-11-15 (MVP: every item in the MVP section is built, wired and tested; post-MVP and device acceptance remain: nothing has run on a physical Android device; no streaming or timestamps).
Non-MVP Work: **Remaining**

Related: RFC-0125 (inference backends; owns the ONNX Runtime backend and the Decision capability), RFC-0103 (Engine and API v1), RFC-0104 (locality), RFC-0050/0057 (Android, hands-free), RFC-0035 (secrets), D28, D36 to D44, D45.

## Abstract

RFC-0125 defines how the Engine hosts inference backends and describes the typed-decision ("System One") capability. Two further modalities are built on that foundation and have no RFC: **speech-to-text** (Whisper on ONNX Runtime behind `POST /v1/audio/transcriptions`) and **vision** (SmolVLM on ONNX Runtime behind `images` on chat messages). This RFC records what they are, why they run where they run, the package format they load, and what they promise to callers.

## Motivation

Dictator needs transcription from the Engine, and liturgical dictation in Finnish is the first real workload. Without a document, three choices are invisible: why STT is not faster-whisper or whisper.cpp, which model sizes are usable for Finnish, and how a fine-tuned model from `liturgos-auditor` gets in. Vision is similar: it arrived as an additive field on the frozen chat type, and the rule for models that cannot take images is nowhere stated.

## Goals

- One runtime for non-LLM models on both hosts: ONNX Runtime (`onnxruntime` on desktop, `onnxruntime-android` on Android), no second native dependency next to llama.cpp.
- Pre- and post-processing is plain Kotlin and unit-tested against reference implementations, so behaviour does not depend on a native library.
- A model is a directory package, detected from its files, installable all-or-nothing from the in-app Hugging Face browser.
- Everything is subject to locality (RFC-0104) and the per-app approval of RFC-0103 like any other model call.

## Non-goals

- Streaming or partial transcripts, word timestamps, diarisation.
- GPU or NNAPI execution providers.
- Loading CTranslate2 (faster-whisper) weights.
- Speaking models in the Engine are not built yet; see "Text-to-speech" below for the proposed amendment. The `voice` module defines the provider interfaces only.

## Design

### Speech-to-text

`POST /v1/audio/transcriptions` takes JSON `{file, model, language?, prompt?}` where `file` is a base64 WAV (8/16/24/32-bit PCM or float, any channel count and rate). The Engine resamples to 16 kHz mono, computes a log-mel spectrogram, runs the Whisper encoder, and decodes greedily with a KV-cached merged decoder. Audio over 30 seconds is cut into windows at the quietest point near each boundary; there is no overlap and no timestamps, so callers should send utterance-sized pieces. Pure silence returns an empty string instead of the text Whisper invents for it. `prompt` is a spelling and vocabulary hint (for example liturgical terms). A model of kind `stt` is listed with capability `audio.transcriptions`; a non-STT model on this route keeps the legacy placeholder behaviour.

**Amendment: multipart upload.** The same route also accepts OpenAI-style `multipart/form-data`, so ordinary OpenAI clients work without changes and audio need not travel base64-encoded inside JSON (a third larger, and held in memory twice). The form is chosen by `Content-Type`; the JSON form is unchanged. Multipart fields: `file` (required, the raw WAV bytes; exactly one `file` part is accepted), `model` (required), `language?`, `prompt?`, `response_format?` (`json` by default, or `text` for a plain-text body; `verbose_json`, `srt` and `vtt` answer 400 because timestamps are not available), `temperature?` (accepted and ignored). Unknown fields are ignored. The `file` part is capped at the same decoded audio limit as the JSON form (`maxAudioBytes`, 25 MiB by default), so both forms admit the same audio; the byte count is enforced while reading and an oversized upload answers 413 without reading further. Both forms then run the same code path, including auth, locality, admission and peer delegation; the peer hop stays JSON. Audio must be WAV, detected by its header; any other encoding answers 400 and names the accepted encodings.

Why ONNX Runtime Whisper: faster-whisper is Python on CTranslate2 and cannot run in a JVM or an Android service; whisper.cpp needs a native build per ABI and a second native runtime beside llama.cpp. ONNX Runtime is already shipped for the Laya decision models, so STT adds no native dependency.

Measured on the desktop JVM, CPU, int8 graphs: tiny (41 MB) is exact in English and garbles Finnish word boundaries (CER 0.14); small (250 MB) is usable Finnish (CER 0.04); large-v3-turbo (1.1 GB) is exact on the test clip but too large for most phones. Finnish therefore needs small or larger. Fine-tuned models, such as those trained in `liturgos-auditor`, load by exporting the Hugging Face checkpoint they started from to ONNX (`optimum-cli export onnx ... automatic-speech-recognition-with-past`); vocabulary changes are fine because `tokenizer.json` and added-token ids are read from the package.

### Vision

A chat message may carry `images`: base64 image bytes or `data:image/...;base64,` URLs on `user` messages, with `content` as the question about them. A model of kind `vision` (SmolVLM-256M on ONNX Runtime) answers; a model that cannot take images sees text placeholders. Images are decoded by a platform decoder (`JvmImageDecoder`, `AndroidImageDecoder`) and preprocessed in Kotlin; the tokenizer is byte-level BPE in Kotlin.

### Packages

A package is a flat directory laid out like an `onnx-community/*` repository: encoder and decoder graphs at one precision (int8 preferred, then int8, fp32, fp16), tokenizer and config files. `ModelPackage.detectWhisper` and the vision/decision equivalents pick the files; the installer downloads all of them or none. The desktop app installs from the Hugging Face browser; Android has no in-app package install yet (scripts `scripts/stt/fetch-whisper.mjs` and `scripts/vision/fetch-smolvlm.mjs` fetch a package for copying).

### Text-to-speech (amendment, built on the desktop JVM host; not run on a device)

Status of this section: decided 2026-11-16 (Finnish: Piper fi_FI harri with the Kotlin phonemizer; English: Piper en_US LJSpeech high with CMUdict). Built in `engine/tts` and `POST /v1/audio/speech`; the real-voice test is opt-in. The Android host stacks the voice backend (`AndroidOnnxTtsBackend`), not yet run on a device. A virtual speech model may be delegated to a peer over `/aidin/v1/audio/speech` (PCM travels base64 inside the JSON peer reply), under the same locality rules as chat.

Goal: the Engine can speak. A model of kind `tts` loads and `POST /v1/audio/speech` returns audio. Finnish must work first; English should work too. The Android app keeps the system TTS (`AndroidDeviceTtsEngine`) until a later decision.

**Candidates compared (licence facts from model cards and repository pages, checked 2026-11-16):**

| Candidate | Finnish | Weights licence | Phonemizer | Notes |
|---|---|---|---|---|
| Piper `fi_FI-harri-medium` (VITS, ONNX, 22.05 kHz, one speaker, about 63 MB) | Yes | Model card (current): dataset is a Kaggle Finnish single-speaker set under CC0; fine-tuned from an nl_BE CC0 base. Card read 2026-11-16, Kaggle page not read | Expects espeak-ng phoneme ids (see below) | Recommended, see below |
| Piper `fi_FI` other voices | Yes | Check per voice card | As above | Not compared in depth |
| MMS-TTS Finnish (`facebook/mms-tts-fin`, VITS) | Yes | The MMS TTS family is listed as CC-BY-NC 4.0 (non-commercial). The Finnish card itself was not found, so this is unconfirmed for that checkpoint | Not needed (character input) | Excluded if the Engine is ever used commercially or in a paid church setting |
| Kokoro ONNX | No evidence of Finnish in any source found | Apache-2.0 per common reports (not re-checked) | Espeak-based in common builds | Excluded: no Finnish |
| Supertonic 3 (ONNX, 99M) | Listed as one of 31 languages in its release notes; the Finnish code was not confirmed | OpenRAIL-M (use restrictions, attribution, redistribution duties); SDK code MIT | Own text front end | Fallback candidate; needs legal read of OpenRAIL-M Attachment A |

**Phonemizer.** Piper's current code (`piper1-gpl`) embeds espeak-ng, which is GPL-3.0-or-later. Shipping it in an EUPL-1.2 app is possible (the EUPL lists GPL-3.0 as compatible) but makes every binary distribution GPL-bound and adds a native library per ABI. Finnish spelling is close to phonemic, so the recommended route is a pure-Kotlin Finnish grapheme-to-phoneme rule table that emits the same IPA symbols the voice's `phoneme_id_map` expects. espeak-ng is used only as a golden reference in tests run on the developer's machine, never shipped.

**Recommendation.** Piper `fi_FI-harri-medium` for Finnish, with a pure-Kotlin Finnish phonemizer and no espeak-ng in the shipped Engine. English uses the Piper `en_US-ljspeech-high` voice (LJSpeech public-domain dataset; chosen by the owner 2026-11-16) with a CMU Pronouncing Dictionary lookup (BSD-2-Clause data, copyright notice shipped with it) and a Kotlin letter-to-sound fallback for words outside the dictionary. The `lessac` voices are excluded: their Blizzard 2013 training licence is research-only and excludes commercial voice synthesis. Ryan (CC BY-NC-SA) is excluded. A user-installed espeak-ng (GPL-3.0 or later, never bundled) is an optional, post-MVP quality fallback (0117.11).

**Package layout (proposed).** A directory package: `<voice>.onnx` (VITS graph), `<voice>.onnx.json` (sample rate, `phoneme_id_map`, `num_speakers`, inference scales, `espeak.voice` field kept for reference only), and `MODEL_CARD`. `ModelPackage.detectPiper` picks the files; the installer downloads all of them or none, as for STT.

**Route.** `POST /v1/audio/speech`, OpenAI-shaped JSON `{model, input, voice?, response_format?, speed?}`. Output `audio/wav` (16-bit PCM) by default; `pcm` also allowed; other formats are HTTP 400 with a clear message. Input limit 4000 characters; longer text is split at sentence ends. The request goes through the request manager and the same auth, locality and admission rules as the other inference routes. A `tts` model on chat routes is refused with a helpful error, as for STT. Capability string `audio.speech`.

**Limits and non-goals.** No streaming audio, no SSML, no voice cloning, no emotion controls, no speaker selection beyond the voices in the package, no Android in-app package install in this amendment.

**Measurements not yet taken.** CPU real-time factor and memory on the desktop JVM and on a reference phone (OnePlus 8) are stage 2 work and must be measured before the Finnish recommendation is final.

### Voice providers

`engine/voice` defines `SttProvider` and companion interfaces so the agent side (RFC-0057) and Dictator can ask for transcription without knowing whether the model is local; hybrid routing between a device recogniser and the Engine lives in `agent/` (RFC-0050).

## Security

- Audio and images are request data under the caller's locality; STT and vision never leave the device unless the request and the app's limits allow a peer or provider.
- A package is code-free data (ONNX graphs and tokenizer files); the installer verifies sizes and hashes where the source provides them and refuses a partial set.
- Unusable audio is HTTP 400 on `file`, never a silent empty transcript, except true silence.

## MVP

Built: the STT route with Whisper on ONNX (JVM tested against transformers' log-mel golden values, scripted-logit decode tests, real-weight tests behind `AIDOS_STT_MODEL_DIR`); the vision path with SmolVLM (unit tests and an opt-in loaded-model test behind `AIDOS_VISION_MODEL_DIR`); package detection and all-or-nothing install on desktop; voice provider interfaces. Not built or not run: everything on a physical Android device.

## Implementation Files

```yaml
files:
  - engine/stt/src/commonMain/kotlin/dev/aidos/stt/SttTypes.kt
  - engine/stt/src/commonMain/kotlin/dev/aidos/stt/WavDecoder.kt
  - engine/stt/src/commonMain/kotlin/dev/aidos/stt/WhisperFeatures.kt
  - engine/stt/src/commonMain/kotlin/dev/aidos/stt/WhisperSttModel.kt
  - engine/stt/src/commonMain/kotlin/dev/aidos/stt/WhisperTokenizer.kt
  - engine/stt/src/jvmAndAndroidMain/kotlin/dev/aidos/stt/OrtWhisperGraphs.kt
  - engine/stt/src/jvmAndAndroidMain/kotlin/dev/aidos/stt/SttPackages.kt
  - engine/stt/src/jvmAndAndroidMain/kotlin/dev/aidos/stt/WhisperPackage.kt
  - engine/stt/src/commonMain/kotlin/dev/aidos/stt/SpeechVad.kt
  - engine/stt/src/jvmAndAndroidMain/kotlin/dev/aidos/stt/OrtSileroGraph.kt
  - engine/stt/src/jvmAndAndroidMain/kotlin/dev/aidos/stt/SileroPackage.kt
  - engine/stt/src/jvmMain/kotlin/dev/aidos/stt/OnnxSttBackend.kt
  - engine/stt/src/jvmTest/kotlin/dev/aidos/stt/SttPackagesTest.kt
  - engine/stt/src/jvmTest/kotlin/dev/aidos/stt/WavDecoderTest.kt
  - engine/stt/src/jvmTest/kotlin/dev/aidos/stt/Wavs.kt
  - engine/stt/src/jvmTest/kotlin/dev/aidos/stt/WhisperFeaturesTest.kt
  - engine/stt/src/jvmTest/kotlin/dev/aidos/stt/WhisperRealModelTest.kt
  - engine/stt/src/jvmTest/kotlin/dev/aidos/stt/WhisperSttModelTest.kt
  - engine/stt/src/jvmTest/kotlin/dev/aidos/stt/WhisperTokenizerTest.kt
  - engine/vision/src/androidMain/kotlin/dev/aidos/vision/AndroidImageDecoder.kt
  - engine/vision/src/jvmAndAndroidMain/kotlin/dev/aidos/vision/ByteLevelBpeTokenizer.kt
  - engine/vision/src/jvmAndAndroidMain/kotlin/dev/aidos/vision/ImagePreprocessor.kt
  - engine/vision/src/jvmAndAndroidMain/kotlin/dev/aidos/vision/OrtVisionRunner.kt
  - engine/vision/src/jvmAndAndroidMain/kotlin/dev/aidos/vision/SmolVlmModel.kt
  - engine/vision/src/jvmAndAndroidMain/kotlin/dev/aidos/vision/VisionModelAdapter.kt
  - engine/vision/src/jvmAndAndroidMain/kotlin/dev/aidos/vision/VisionPackage.kt
  - engine/vision/src/jvmAndAndroidMain/kotlin/dev/aidos/vision/VisionPackages.kt
  - engine/vision/src/jvmAndAndroidMain/kotlin/dev/aidos/vision/VisionRunner.kt
  - engine/vision/src/jvmMain/kotlin/dev/aidos/vision/JvmImageDecoder.kt
  - engine/vision/src/jvmMain/kotlin/dev/aidos/vision/OnnxVisionBackend.kt
  - engine/vision/src/jvmTest/kotlin/dev/aidos/vision/ByteLevelBpeTokenizerTest.kt
  - engine/vision/src/jvmTest/kotlin/dev/aidos/vision/ImagePreprocessorTest.kt
  - engine/vision/src/jvmTest/kotlin/dev/aidos/vision/LoadedSmolVlmTest.kt
  - engine/vision/src/jvmTest/kotlin/dev/aidos/vision/SmolVlmModelTest.kt
  - engine/vision/src/jvmTest/kotlin/dev/aidos/vision/VisionPackageTest.kt
  - engine/voice/src/commonMain/kotlin/dev/aidos/voice/VoiceProviders.kt
  - engine/voice/src/jvmTest/kotlin/dev/aidos/voice/VoiceProvidersTest.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/http/EngineHttpServer.kt
  - engine/enginehost/src/jvmTest/kotlin/fi/italeino/aidos/engine/http/EngineHttpServerMultipartTranscriptionTest.kt
  - engine/enginehost/src/jvmTest/kotlin/fi/italeino/aidos/engine/http/EngineHttpServerTranscriptionTest.kt
  - sdk/client/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/sdk/client/AidosEngineClient.kt
  - sdk/client/src/jvmTest/kotlin/fi/italeino/aidos/sdk/client/AidosEngineClientTest.kt
  - sdk/client/src/jvmTest/kotlin/fi/italeino/aidos/sdk/client/ContractFreezeTest.kt
  - docs/stt.md
  - scripts/stt/fetch-whisper.mjs
  - scripts/vision/fetch-smolvlm.mjs
  - engine/tts/build.gradle.kts
  - engine/tts/src/commonMain/kotlin/dev/aidos/tts/EnglishPhonemizer.kt
  - engine/tts/src/commonMain/kotlin/dev/aidos/tts/FinnishPhonemizer.kt
  - engine/tts/src/commonMain/kotlin/dev/aidos/tts/Phonemizer.kt
  - engine/tts/src/commonMain/kotlin/dev/aidos/tts/PiperConfig.kt
  - engine/tts/src/commonMain/kotlin/dev/aidos/tts/SpeechText.kt
  - engine/tts/src/commonMain/kotlin/dev/aidos/tts/TtsTypes.kt
  - engine/tts/src/commonMain/kotlin/dev/aidos/tts/WavWriter.kt
  - engine/tts/src/commonTest/kotlin/dev/aidos/tts/TtsCoreTest.kt
  - engine/tts/src/jvmAndAndroidMain/kotlin/dev/aidos/tts/OrtPiperGraph.kt
  - engine/tts/src/jvmAndAndroidMain/kotlin/dev/aidos/tts/PiperVoice.kt
  - engine/tts/src/jvmAndAndroidMain/kotlin/dev/aidos/tts/TtsPackages.kt
  - engine/tts/src/jvmAndAndroidMain/kotlin/dev/aidos/tts/VoicePackage.kt
  - engine/tts/src/jvmMain/kotlin/dev/aidos/tts/OnnxTtsBackend.kt
  - engine/tts/src/jvmTest/kotlin/dev/aidos/tts/PiperVoiceTest.kt
  - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/http/OpenAiSchema.kt
  - engine/enginehost/src/jvmTest/kotlin/fi/italeino/aidos/engine/http/EngineHttpServerSpeechTest.kt
  - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopEngineHost.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/inference/AndroidOnnxTtsBackend.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/EngineService.kt
```

## Post-MVP Work

Checklist for work beyond the MVP. Each item has a stable id; set `status` to `partial` or `done` and list the implementing `files` (checked by `scripts/rfc/postmvp.py`).

```yaml
post_mvp:
  - id: "0117.1"
    title: "Streaming and partial transcripts"
    status: todo
    phase: P2
  - id: "0117.2"
    title: "Word-level timestamps"
    status: todo
    phase: P2
  - id: "0117.3"
    title: "GPU or NNAPI execution providers for ONNX models"
    status: todo
    phase: P3
  - id: "0117.4"
    title: "Android in-app package install for STT and vision models"
    status: todo
    phase: P1
  - id: "0117.5"
    title: "On-device timing and a minimum model recommendation for Finnish on phones"
    status: todo
    phase: P1
  - id: "0117.6"
    title: "Export and package a liturgos-auditor fine-tune as an ONNX Whisper package"
    status: todo
    phase: P1
  - id: "0117.7"
    title: "Larger-vision-model support and the OpenAI content-part image form"
    status: todo
    phase: P3
  - id: "0117.8"
    title: "Speaker diarisation"
    status: todo
    phase: P3
  - id: "0117.9"
    title: "Measure Whisper small on a mid-range Android phone; decide whether a smaller Finnish fine-tune is needed"
    status: todo
  - id: "0117.10"
    title: "Text-to-speech: Piper voice packages (Finnish and English), pure-Kotlin Finnish phonemizer, CMUdict English, POST /v1/audio/speech"
    status: partial
    phase: P1
    files:
      - engine/tts/build.gradle.kts
      - engine/tts/src/commonMain/kotlin/dev/aidos/tts/EnglishPhonemizer.kt
      - engine/tts/src/commonMain/kotlin/dev/aidos/tts/FinnishPhonemizer.kt
      - engine/tts/src/commonMain/kotlin/dev/aidos/tts/Phonemizer.kt
      - engine/tts/src/commonMain/kotlin/dev/aidos/tts/PiperConfig.kt
      - engine/tts/src/commonMain/kotlin/dev/aidos/tts/SpeechText.kt
      - engine/tts/src/commonMain/kotlin/dev/aidos/tts/TtsTypes.kt
      - engine/tts/src/commonMain/kotlin/dev/aidos/tts/WavWriter.kt
      - engine/tts/src/commonTest/kotlin/dev/aidos/tts/TtsCoreTest.kt
      - engine/tts/src/jvmAndAndroidMain/kotlin/dev/aidos/tts/OrtPiperGraph.kt
      - engine/tts/src/jvmAndAndroidMain/kotlin/dev/aidos/tts/PiperVoice.kt
      - engine/tts/src/jvmAndAndroidMain/kotlin/dev/aidos/tts/TtsPackages.kt
      - engine/tts/src/jvmAndAndroidMain/kotlin/dev/aidos/tts/VoicePackage.kt
      - engine/tts/src/jvmMain/kotlin/dev/aidos/tts/OnnxTtsBackend.kt
      - engine/tts/src/jvmTest/kotlin/dev/aidos/tts/PiperVoiceTest.kt
      - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/http/EngineHttpServer.kt
      - engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/http/OpenAiSchema.kt
      - engine/enginehost/src/jvmTest/kotlin/fi/italeino/aidos/engine/http/EngineHttpServerSpeechTest.kt
      - engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopEngineHost.kt
      - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/inference/AndroidOnnxTtsBackend.kt
      - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/EngineService.kt
      - sdk/client/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/sdk/client/AidosEngineClient.kt
      - sdk/client/src/jvmTest/kotlin/fi/italeino/aidos/sdk/client/ContractFreezeTest.kt

  - id: "0117.11"
    title: "Text-to-speech fallback: detect a user-installed espeak-ng (GPL-3.0 or later, not shipped) for English quality; never bundled"
    status: todo
    phase: P2
```
