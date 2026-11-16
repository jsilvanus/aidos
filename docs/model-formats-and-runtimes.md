# Model formats and runtimes: what Aidos supports and what it would take to add the rest

Status: discovery and planning note, 2026-11-16. Not an RFC and not a decision. Nothing here is built.
Repository paths are relative to the repo root. Claims marked "unverified" could not be confirmed from a primary source in this pass and must be checked before anything is built on them.
Hardware acceleration (GPU, NPU) is designed in [RFC-0128](rfcs/0128-hardware-acceleration.md); this note only says which accelerators each format can reach.
A copy of this note is kept in the project files as `plans/model-formats-and-runtimes.md`.

## Short answer

- **Yes, the Engine already runs two formats.** GGUF through llama.cpp for chat and embeddings, and ONNX through ONNX Runtime for speech-to-text (Whisper), vision (SmolVLM) and decisions (Laya). Both work on the desktop and on Android.
- **Those two formats cover almost everything small and current.** GGUF is where local chat models, embedding models, rerankers and (with a projector file) vision and audio input are published. ONNX is where speech (STT, TTS, voice activity detection, diarisation), encoders and OCR are published.
- **"ONNX support" is per model family, not per format.** ONNX Runtime only executes a graph. For each family the Engine owns the tokenizer, the audio or image front end and the generation loop in Kotlin, about 900 to 1,000 lines each today. A new ONNX family is one to two weeks; a new GGUF chat model is usually nothing.
- **The cheapest gains are inside the two runtimes we have:** an ONNX export step for the Whisper fine-tunes from `liturgos-auditor`, voice activity detection, rerankers (already in the llama.cpp binding, never called), and vision or audio input through llama.cpp projectors, which needs a newer binding.
- **The llama.cpp binding is the main risk.** `de.kherud:llama` has not been released since June 2025. A maintained fork, `net.ladenthin:llama` 6.0.0, publishes projector support, reranking, an Android library and GPU builds for Windows and Android on Maven Central. Moving to it is a migration with a package rename and a stated minimum Android API of 28 against our 26.
- **Formats that need Python cannot run in the Engine.** SafeTensors and PyTorch checkpoints, CTranslate2 (faster-whisper) and MLX are converted to GGUF or ONNX on a desktop or in CI, never on the device.
- **One third runtime is worth watching:** LiteRT-LM, Google's runtime for Gemma, the only other one with artifacts for both Android and the desktop JVM. It is a later step and belongs after RFC-0128.

The ranked order of work is in C.3 and the questions that need the owner are in C.4.

## Worked example: Gemma 4

Asked by the owner on 2026-11-16. Facts read from the Hugging Face API that day.

- Google publishes Gemma 4 as GGUF itself: `google/gemma-4-E2B-it-qat-q4_0-gguf` (3.35 GB plus a 0.99 GB projector), `google/gemma-4-E4B-it-qat-q4_0-gguf` (5.15 GB plus 0.99 GB) and `google/gemma-4-12B-it-qat-q4_0-gguf` (6.98 GB plus 0.18 GB). Community quantisations exist for every size. The same models exist as `.litertlm` under `litert-community`.
- The first Gemma 4 repositories were created in March 2026. The Engine's llama.cpp binding was published in June 2025, so its llama.cpp cannot know the Gemma 4 architecture and a load is expected to fail (not tested).
- Three routes, cheapest first:
  1. **Desktop, no Engine change:** run a current `llama-server` with a GPU build and add it as an OpenAI-compatible provider marked local. Chat works today through `engine/providers`; images do not, because the adapter drops image blocks.
  2. **Both hosts, the real fix:** move to a llama.cpp binding built on a 2026 llama.cpp (the `net.ladenthin:llama` fork, or our own build). This is the binding decision in C.3 item 5 and the spike in RFC-0128. It also brings the projector, so Gemma 4 can take images and audio.
  3. **Later:** LiteRT-LM with the `.litertlm` files, which is where phone GPU and NPU support for Gemma is strongest.
- Fit on the reference hardware: on the desktop's RTX 2060 class card with 8 GB of dedicated video memory, E2B and E4B fit fully on the GPU, and 12B (6.98 GB) is borderline once the context cache is added. This assumes the memory is free: other programs were holding 5 of the 8 GB when the owner read the figure. On the OnePlus 8, E2B on the CPU is the realistic size.

## Part A. What the repository does today (verified in code)

### A.1 Formats that load and run, by modality and host

| Modality | Format | Runtime | Desktop JVM class | Android class | HTTP route |
|---|---|---|---|---|---|
| LLM chat | GGUF | llama.cpp via `de.kherud:llama:4.2.0` (JNI) | `LlamaCppInferenceBackend`, `LlamaCppAdapter` (`engine/modelruntime/src/jvmMain/.../modelruntime/`) | `AndroidLlamaCppInferenceBackend`, `AndroidLlamaCppAdapter` (`engine/androidapp/src/androidMain/.../inference/`) | `/v1/chat/completions` |
| Embeddings | GGUF | same llama.cpp binding, `enableEmbedding()` at load | `LlamaCppAdapter.embed` | `AndroidLlamaCppAdapter.embed` | `/v1/embeddings` |
| Decisions (Laya) | ONNX package | ONNX Runtime 1.22.0 | `OnnxDecisionBackend`, `OrtDecisionRunner`, `LayaPackage` (`engine/decisions`) | `AndroidOnnxDecisionBackend` over the same `DecisionPackages` | `/v1/decisions`, `/v1/systemone` |
| STT (Whisper) | ONNX package | ONNX Runtime 1.22.0 | `OnnxSttBackend`, `SttPackages`, `WhisperPackage`, `OrtWhisperGraphs`, `WhisperSttModel` (`engine/stt`) | `AndroidOnnxSttBackend` over the same `SttPackages` | `/v1/audio/transcriptions` |
| Vision (SmolVLM) | ONNX package | ONNX Runtime 1.22.0 | `OnnxVisionBackend`, `VisionPackages`, `VisionPackage`, `OrtVisionRunner`, `SmolVlmModel` (`engine/vision`) | `AndroidOnnxVisionBackend` | `/v1/chat/completions` with image blocks |
| Remote or sidecar LLM | none (HTTP) | OpenAI-compatible server | `ProviderBackend`, `OpenAiCompatibleModelAdapter` (`engine/providers`) | not available (module is JVM only) | `/v1/chat/completions` |
| TTS | none | none | interfaces only: `SttProvider`, `TtsProvider`, `VoiceProviderFactory`, no-op classes (`engine/voice/.../VoiceProviders.kt`) | same | no route |
| Rerankers, OCR, translation | none | none | none | none | no route |

Notes.

- The backends are stacked decorators. Desktop: `ProviderBackend(OnnxVisionBackend(OnnxSttBackend(OnnxDecisionBackend(LlamaCppInferenceBackend(), ...))))` in `engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/DesktopEngineHost.kt` (line 287). Android: `AndroidOnnxVisionBackend(AndroidOnnxSttBackend(AndroidOnnxDecisionBackend(AndroidLlamaCppInferenceBackend(...))))` in `engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/EngineService.kt` (line 256). Each ONNX decorator claims a model id when the directory `<modelsDir>/<id>/` passes its own `isPackage` test and delegates everything else.
- All three ONNX runners create sessions with the CPU execution provider only (`OrtDecisionRunner`, `OrtWhisperGraphs`, `OrtVisionRunner` set optimisation level and thread count, nothing else). The comments say accelerators are a host choice.
- ONNX Runtime artifacts: `com.microsoft.onnxruntime:onnxruntime:1.22.0` on JVM and `com.microsoft.onnxruntime:onnxruntime-android:1.22.0` on Android (`engine/stt/build.gradle.kts`, `engine/vision/build.gradle.kts`, `engine/decisions/build.gradle.kts`).
- Android ships one native llama library: `engine/androidapp/src/androidMain/jniLibs/arm64-v8a/libjllama.so` (5 174 320 bytes) and a copy directory `aarch64/`. The published `llama-4.2.0.jar` contains `de/kherud/llama/Linux-Android/aarch64/libjllama.so` with exactly the same byte size, and the two files have the same SHA-256 (`849a301f...e610bd`, compared 2026-11-16), so the Android library is the upstream prebuilt one taken out of the jar, not an Aidos build.
- `PackageInstall.isRunnable` (`engine/models/src/commonMain/kotlin/dev/aidos/models/PackageInstall.kt`) is the single statement of what is runnable: `format == "gguf"`, or `format == "onnx"` with kind in DECISION, STT, VISION. An ONNX embedding model, an ONNX LLM, or an ONNX TTS model is therefore reported as not runnable even though ONNX Runtime is linked.
- The HTTP surface (`engine/enginehost/.../http/EngineHttpServer.kt`, routes near line 362) has chat completions, embeddings, audio transcriptions and decisions. There is no `/v1/audio/speech`, no `/v1/rerank`, no image generation route. `ModelKind.RERANKER` maps to a capability string "rerank" (line 1324) but no handler exists.

### A.2 What an ONNX "package" is, and how detection works

A package is a directory of files that only work together, installed flat (base names only) into `<modelsDir>/<safe id>/`. `ModelPackage` (`engine/huggingface/src/commonMain/kotlin/dev/aidos/huggingface/ModelPackage.kt`) describes the remote side: the repo files, the folder the graph lives in, and three boolean flags (`isDecisionPackage`, `isSpeechPackage`, `isVisionPackage`).

Detection runs twice, once against the Hugging Face file list and once against the installed directory.

| Family | Remote detection (`ModelPackage`) | Local detection | Loader |
|---|---|---|---|
| Laya decision model | `detectOnnx`: any `.onnx` graph with `tokenizer.json` and `rl_agent_config.json` among its companions | `LayaPackage.isPackage`: any single `*.onnx`, `tokenizer.json`, `rl_agent_config.json` | `LayaPackage.open` builds `GemmaBpeTokenizer` + `OrtDecisionRunner` + `LayaDecisionModel`. The runner feeds five fixed input names (`input_ids`, `attention_mask`, `marker_pos`, `marker_mask`, `qtype`) and reads `logits` and `act_logits`. |
| Whisper | `detectWhisper`: `encoder_model<suffix>.onnx` beside `decoder_model_merged<suffix>.onnx`, plus `tokenizer.json`; suffix preference `_quantized`, `_int8`, none, `_fp16`; `.onnx_data` sidecars included | `WhisperPackage.isPackage`: `tokenizer.json`, an `encoder_model*.onnx`, and a `decoder_model_merged*.onnx` or `decoder_model*.onnx` | `WhisperPackage.open` builds `WhisperTokenizer` + `OrtWhisperGraphs` + `WhisperSttModel`. Mel bins (80 or 128) come from `preprocessor_config.json`. |
| SmolVLM | `detectVision`: `vision_encoder`, `embed_tokens`, `decoder_model_merged` with a shared suffix `_uint8` or `_quantized`, in `onnx/` or the root, plus `tokenizer.json`, `config.json`, `processor_config.json`, `generation_config.json` | `VisionPackage.isPackage`: the three graphs with a common suffix plus tokenizer and configs | `VisionPackage.open` refuses any `config.json` whose `model_type` is not `idefics3`, then builds `ByteLevelBpeTokenizer` + `OrtVisionRunner` + `SmolVlmModel`. |

`PackageInstall.choose` picks, for a repo with no GGUF quantisation, the first decision package, then the Whisper package, then the vision package. `detectOnnx` also produces one generic package per `.onnx` graph (with tokenizer and config companions), but a generic package has all three flags false, so `choose` returns null and nothing is installed.

What is hard-coded per family, in Kotlin:

- Whisper (`engine/stt`): WAV decoding and resampling to 16 kHz (`WavDecoder`), log-mel features (`WhisperFeatures`), the tokenizer and special tokens (`WhisperTokenizer`), the forced prefix `<|startoftranscript|><|lang|><|transcribe|><|notimestamps|>`, language detection, 30 second windowing with a cut at the quietest point, token suppression, greedy decoding, and the KV-cache plumbing for the merged decoder (`use_cache_branch`, `past_key_values.*` to `present.*`). No timestamps, no beam search, no VAD.
- SmolVLM (`engine/vision`): image decode (`JvmImageDecoder`, `AndroidImageDecoder`), resize and tiling (`ImagePreprocessor`), byte-level BPE (`ByteLevelBpeTokenizer`), the Idefics3 chat template written by hand in `SmolVlmModel.buildPrompt`, image-token splicing, greedy decoding with KV cache (`OrtVisionRunner`).
- Laya (`engine/decisions`): `GemmaBpeTokenizer`, `LayaEncoder`, `LayaDecoder`, temperature calibration from `rl_agent_config.json`.

There is no shared ONNX "generation loop", no shared tokenizer library, and no generic "run any ONNX graph" path. Each family owns its pre-processing, loop and post-processing. This is the main cost driver for every new ONNX family.

Effort to add another ONNX family, judged from the existing code size (stt is about 930 lines of main code, vision about 990, decisions about 890):

| New family | What already exists | Kotlin work needed | Estimate |
|---|---|---|---|
| ONNX embedding model (BERT, e5, bge, mmBERT style) | ORT sessions; a WordPiece or Unigram tokenizer does not exist (only BPE variants) | tokenizer from `tokenizer.json` (WordPiece for BERT, Unigram/SentencePiece for XLM-R and e5), attention mask, mean or CLS pooling, L2 normalise; implement `EmbeddingModelAdapter`; add EMBEDDING to `onnxKinds` and a detector | S to M (the tokenizer is most of it) |
| ONNX cross-encoder reranker | same as embeddings | pair encoding, one logit out; needs a new `/v1/rerank` route and a kernel adapter interface | M |
| Silero VAD | ORT sessions | single small graph, 16 kHz frames in, probability out, carry the recurrent state between calls; no tokenizer | S |
| Moonshine (encoder-decoder STT) | Whisper loop structure, WAV decoder | no mel front end (the model takes raw audio), different tokenizer and decoder input names, variable-length input instead of 30 s windows | M |
| Parakeet / Canary (NeMo transducer or CTC exports) | WAV decoder | different mel settings, a transducer (TDT) greedy loop with encoder, decoder and joiner graphs, SentencePiece vocabulary, no KV cache | M (or S through sherpa-onnx, see B and C) |
| Piper TTS (VITS) | ORT sessions | text to phoneme ids needs espeak-ng (a GPL-3 native library) or pre-phonemised input; single graph out to PCM; WAV writer; new `/v1/audio/speech` route and adapter | M, and the phonemiser is the hard part |
| Kokoro TTS | ORT sessions | same phonemiser problem (misaki/espeak), voice style vectors loaded from files, single graph | M |
| Another VLM (Qwen-VL, Gemma 3, Moondream, Florence-2) | SmolVLM structure | each has its own image pre-processing, token layout and chat template; `VisionPackage.readConfig` rejects everything that is not `idefics3` | M per family |
| ONNX LLM (Phi, Gemma, Qwen exports) | KV-cache loop pattern from vision | chat template rendering (no Jinja engine in Kotlin here), sampling, stop handling, tool-call grammar; llama.cpp already does all of this for GGUF | L if hand-written; see ORT GenAI in Part B |

### A.3 Embeddings, rerankers and mmproj

- Embeddings run through llama.cpp only. `LlamaCppInferenceBackend.isEmbeddingModel` turns embedding mode on when the catalog says EMBEDDING or the GGUF architecture is one of `bert`, `nomic-bert`, `jina-bert-v2`, `modern-bert`. The Android backend decides by catalog kind only (`AndroidLlamaCppInferenceBackend`, line 173). A decoder-style embedding GGUF (for example Qwen3-Embedding or EmbeddingGemma, whose architecture is not in that list) would only be treated as an embedding model if the catalog entry says so.
- Rerankers do not run anywhere. No code calls a rerank function of the binding, and there is no route.
- mmproj is recognised and deliberately not used. `ArtifactRole.PROJECTOR` exists so that `mmproj-*.gguf` is not offered as a standalone model (`ModelArtifact.kt`), and `HuggingFaceClient.parseModel` filters projectors out of `quantizations`. Nothing downloads or loads a projector. `engine/vision/build.gradle.kts` states the reason: "de.kherud:llama 4.2.0, the newest published, is built without libmtmd, so a GGUF + mmproj pair cannot be driven from the JVM or Android hosts". `LlamaChatPrompt` renders an image block as the literal text `[Image: <mime>]` (line 161). The claim about 4.2.0 is checked against upstream in Part B.

### A.4 What the Hugging Face browser and installer accept

- Search (`ModelBrowser.search`, `engine/models/src/commonMain/kotlin/dev/aidos/models/ModelBrowser.kt`) always sends the Hub filter `gguf`, plus a task filter derived from `ModelKind`. A repo without the `gguf` tag does not appear in search results at all. ONNX-only repos (Whisper exports, SmolVLM, Laya) are reached by opening a model id directly or through the curated suggestions.
- Model detail (`ModelBrowser.getDetail`, and `LocalModelDetailControl` on desktop, `ModelDetailViewModel` on Android): picks a GGUF quantisation (`Q4_K_M` first, else the first with a size); when there is none it calls `PackageInstall.choose(remote.packages)`.
- `HuggingFaceClient.parseArtifact` keeps every file whose extension maps to a known `ModelFormat` (including `.safetensors`, `.bin`, `.pt`, `.tflite`, `.pte`, `.xml`, `.mlmodel`), so these appear as artifacts. Note that the extension map is coarse: any `.bin` is PYTORCH and any `.xml` is OPENVINO.
- A SafeTensors-only repo: it is not found by search (no `gguf` tag). Opened directly, it has no quantisations and no package, so `PackageInstall.choose` returns null; the comment there says "this Engine has no backend for it and installing would leave a file it can never load". `ModelFormat.SAFETENSORS` and `PYTORCH` have empty `defaultBackends`. There is no conversion step anywhere in the Engine.
- Split GGUF (`-00001-of-00003.gguf`) is classified as `SHARD` and excluded from quantisations; there is no multi-part GGUF install.
- `kind` inference (`HuggingFaceClient.inferModelKind`) is by tag and pipeline strings and defaults to LLM.

### A.5 What `engine/providers` supports

- One adapter: `OpenAiCompatibleModelAdapter`, which posts to `<baseUrl>/v1/chat/completions`. Its doc comment names Ollama, vLLM, LocalAI and LM Studio as intended targets. There is no Ollama-native API client, no Anthropic or Gemini adapter.
- Chat only: no embeddings, no audio transcription, no speech, no rerank through a provider. `ProviderBackend.descriptor` hard-codes `kind = ModelKind.LLM`.
- Requests are non-streaming (`"stream": false`), and user turns are sent as text only (image blocks are dropped by the `filterIsInstance<ContentBlock.Text>()` join). Tool calls are supported natively.
- Models are listed by hand in the provider config (`ProviderConfig.models`); there is no discovery through `/v1/models`.
- Locality: a provider is `remote` unless `treatAsLocal` is set and the base URL is loopback (`ProviderConfig.servedLocally`).
- The module is `kotlin("jvm")` only ("JVM only for now (the desktop host)" in `engine/providers/build.gradle.kts`). The Android Engine has no provider backend.
- The Engine does not start or supervise a sidecar process for providers. (`engine/aidin/.../SplitProcessSupervisors.kt` supervises `llama-server` and `rpc-server` binaries for split inference; that is a different feature, but it shows a supervisor pattern exists.)

Consequence: "use it through a sidecar or remote provider" is a real option today only for chat LLMs on desktop. Using a sidecar for STT, TTS, embeddings or vision needs new adapter code in `engine/providers`.

### A.6 Memory admission

`GlobalModelRuntime` (`engine/modelruntime/src/commonMain/.../GlobalModelRuntime.kt`) serialises loads and evicts LRU models against a resident-bytes budget using a host-supplied `residentEstimate(descriptor, settings)`. For GGUF the estimate uses GGUF metadata (`GgufLoader`, weights plus KV cache). ONNX packages report `sizeBytes` as the sum of graph files. Any new runtime has to supply a descriptor size and an estimate, and has to free native memory on `unload`; a sidecar process or a system service (AICore) is outside this accounting.

## Part B. Survey of formats and runtimes

All version numbers and dates below were read on 2026-11-16 from the cited page or from the Maven metadata file of the artifact. Where a page showed a day and month without a year, that is stated. "Unverified" marks statements that come from general knowledge or a secondary source and were not confirmed on a primary page in this pass.

### B.1 GGUF and llama.cpp

**What it is.** GGUF is the single-file container of the ggml project: tensors, quantisation, tokenizer and chat template in one file. llama.cpp is the reference runtime (MIT licence, https://github.com/ggml-org/llama.cpp).

**Modalities in practice.**

- Chat LLMs: the default distribution format for local LLMs on Hugging Face (the Hub has a `gguf` library tag, which is what `ModelBrowser` filters on).
- Embeddings and rerankers: supported by llama.cpp (BERT-family and decoder-style embedding models; reranking mode). The upstream README page fetched did not list them explicitly; the Java binding release notes do (see below).
- Vision, audio and video input through a second GGUF, the projector (`mmproj`), handled by `libmtmd`. The upstream multimodal document (https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md) lists, among others: Gemma 3 (4B, 12B, 27B), Gemma 4 (E2B, E4B, larger), SmolVLM and SmolVLM2 (256M, 500M, 2.2B), Qwen2-VL, Qwen2.5-VL, InternVL 2.5 and 3, Pixtral 12B, Moondream2; audio models Ultravox 0.5, Voxtral Mini 3B, Qwen3-ASR (0.6B, 1.7B); mixed audio and vision models Qwen2.5-Omni, Qwen3-Omni and Gemma 4 E2B/E4B. The document says it supports "image, audio and video input".
- TTS: the README links a "Text-to-speech (TTS) models" document (`docs/multimodal/tts.md`). OuteTTS support through `tools/tts` is unverified in this pass; a maintained Java fork exposes a Qwen3-TTS pipeline (below).
- Conversion scripts shipped with llama.cpp: `convert_hf_to_gguf.py`, `convert_lora_to_gguf.py`, `convert_llama_ggml_to_gguf.py` (README). They are Python scripts that need PyTorch-side dependencies, so conversion is a desktop or CI step, not an on-device step.

**JVM and Android bindings.**

| Artifact | State seen | Notes |
|---|---|---|
| `de.kherud:llama:4.2.0` (what Aidos uses) | Maven Central metadata last updated 2025-06-20. On GitHub v4.2.0 is flagged pre-release and v4.1.0 (18 Mar, year not shown) is flagged Latest, built on llama.cpp b4916 (https://github.com/kherud/java-llama.cpp/releases). | README lists prebuilt natives for Windows, Linux and macOS and says Android needs a self-built native, but the 4.2.0 jar itself (12.2 MB, inspected) contains natives for Windows x86 and x86_64, Linux x86_64 and aarch64, macOS x86_64 and aarch64, and `Linux-Android/aarch64`. Inspected with `javap`: `LlamaModel` has `embed(String)`, `rerank(String, String...)`, `rerank(boolean, String, String...)` and `applyTemplate`; `ModelParameters` has `enableReranking()` and `setPoolingType(...)`; no method name contains `mmproj`. v4.1.0 notes say "Re-ranking and chat-template support". No mention of multimodal or mtmd on the releases page or README. No release in about 17 months. |
| `net.ladenthin:llama:6.0.0` | Maven Central metadata last updated 2026-10-26 (https://github.com/bernardladenthin/java-llama.cpp). | A fork of kherud's binding, MIT. README states llama.cpp b12445; `setMmproj` for images, audio projectors (Ultravox, Qwen2.5-Omni), video frames, a `TextToSpeech` class (Qwen3-TTS), `enableReranking()`, `enableEmbedding()`, Jinja templates, tool calling. Prebuilt natives: Linux x86-64/aarch64, macOS aarch64, Windows x86-64/aarch64, Android aarch64 and x86-64, with separate GPU natives (CUDA, Vulkan, OpenCL incl. Android Adreno, ROCm, SYCL). The Maven Central directory for 6.0.0 confirms the classifier jars, among them `vulkan-windows-x86-64`, `cuda13-windows-x86-64`, `vulkan-linux-x86-64`, `metal-macos-aarch64`, `cpu-android-aarch64` and `opencl-android-aarch64` (listed 2026-11-16). An Android AAR (`llama-android`) exists; the README states minimum API 28. Java package is `net.ladenthin.llama`, not `de.kherud.llama`. Only the first 100 000 characters of the README were read. |

Consequences for Aidos: the statement in `engine/vision/build.gradle.kts` that 4.2.0 is "the newest published" is true for the `de.kherud` coordinates, but a maintained fork with mtmd exists. Its stated minimum API (28) is above the Aidos Engine's minSdk 26, and its package name differs, so adopting it is a migration, not a version bump. The alternative is to keep `de.kherud` and build `libjllama.so` against a newer llama.cpp with mtmd, which requires JNI glue that the 4.2.0 Java classes do not have. Reranking, by contrast, is already present in the 4.2.0 Java API on both hosts and is simply not called by Aidos.

**Platforms.** Windows, Linux, macOS, Android, iOS. **Accelerators reached:** CUDA, Vulkan, Metal, OpenCL (Adreno), SYCL, ROCm; the fork also names OpenVINO as opt-in. **Binary size:** the Android CPU library in the 4.2.0 jar is 5.2 MB and the Windows x86_64 DLL 3.2 MB; GPU builds are larger (not measured). **Maturity:** very high for chat; multimodal is newer and moves fast.

**GGUF-using siblings.**

- whisper.cpp (MIT, https://github.com/ggml-org/whisper.cpp): Whisper only. Its README says models are in the "ggml" custom binary format, not GGUF. Platforms include Windows, Linux, macOS, Android and iOS; accelerators Metal, Core ML, OpenVINO, CUDA, Vulkan, ROCm. Built-in VAD option with a Silero model. Java: an official binding directory `bindings/java`, whose Maven artifact `io.github.ggerganov:whispercpp` is at 1.4.0 from 2023-09-15 (stale), and the third-party `io.github.givimad:whisper-jni:1.7.1` (2025-01-03). No current Android Maven artifact was found; the repo has an Android example app.
- stable-diffusion.cpp (MIT, https://github.com/leejet/stable-diffusion.cpp): image and video generation (SD1.x to SD3.5, FLUX, Qwen Image, Z-Image, Wan and others). Reads `.safetensors`, `.ckpt` and `.gguf` directly. Backends CUDA, Vulkan, Metal, OpenCL, SYCL. No Java or Kotlin binding is listed; Android only through Termux or a third-party app.
- GGML legacy (`.bin` files from before GGUF, 2023): llama.cpp keeps `convert_llama_ggml_to_gguf.py`. No reason to support the old container directly.
- Ollama model store: GGUF blobs addressed by digest with a manifest and a template layer (unverified in this pass; from general knowledge). Aidos reaches Ollama models through Ollama's HTTP API, not by reading its store.

### B.2 ONNX and ONNX Runtime

**What it is.** ONNX is a graph format (`.onnx`, with external weights in `.onnx_data`). ONNX Runtime (ORT, MIT) executes it. It is a general tensor runtime: the caller owns tokenisation, feature extraction and any generation loop.

**Versions.** Maven Central: `com.microsoft.onnxruntime:onnxruntime` 1.32.0, `onnxruntime-android` 1.32.0 and `onnxruntime_gpu` 1.32.0 (metadata updated 2026-11-13); `onnxruntime-android-qnn` 1.29.0 (2026-08-12); `onnxruntime-extensions-android` 0.13.0 (2024-10-31, stale). Aidos pins 1.22.0 (2025-05), ten minor versions behind.

**Modalities in practice.** The broadest of all formats for small non-LLM models: Whisper and Moonshine exports, Parakeet and other NeMo exports, Silero VAD, Piper and Kokoro TTS, embedding models and rerankers (most sentence-transformers repos carry an `onnx/` folder), OCR (PaddleOCR-derived exports; unverified in this pass), VLMs exported for transformers.js (`onnx-community/*`). Example checked: `onnx-community/whisper-large-v3-turbo` has `encoder_model*.onnx` and `decoder_model_merged*.onnx` in eight precisions; the quantised pair is 645 MB + 440 MB and the fp32 encoder keeps 2.55 GB in `encoder_model.onnx_data` (https://huggingface.co/onnx-community/whisper-large-v3-turbo/tree/main/onnx). This layout is exactly what `ModelPackage.detectWhisper` matches.

**Export tools.** `optimum-cli export onnx --model <id> <dir>` from `optimum-onnx` (https://github.com/huggingface/optimum-onnx), Python on a desktop or CI. Microsoft Olive is the other route (not checked in this pass). Whether the merged decoder is produced by default by current Optimum versions is unverified; the `onnx-community` repos contain it.

**Platforms.** Windows, Linux, macOS, Android, iOS. **Accelerators reached:** CUDA, TensorRT, DirectML, OpenVINO, QNN, NNAPI, XNNPACK, Core ML, WebGPU through execution providers; on JVM the GPU providers come in the separate `onnxruntime_gpu` artifact and QNN on Android in `onnxruntime-android-qnn`. **Binary size:** the Android AAR adds a `libonnxruntime.so` of the order of 15 to 20 MB per ABI (unverified, not measured here). **Maturity:** very high.

**ONNX Runtime GenAI (ORT GenAI).** A layer on ORT that owns tokenisation, chat templates, the KV-cache loop and sampling for LLMs, VLMs and Whisper. Latest release v0.20.0 dated 14 Nov 2026 (https://github.com/microsoft/onnxruntime-genai/releases); release notes mention Gemma 4 vision and audio, Qwen2.5-VL and Qwen3-VL, Phi-4, QNN, DirectML, CUDA and WebGPU. A Java API exists (package `ai.onnxruntime.genai`: `Model`, `Tokenizer`, `TokenizerStream`, `Generator`, `GeneratorParams`), but the Java API page says publication is pending and the only documented route is building from source (https://onnxruntime.ai/docs/genai/api/java.html). No artifact exists under `com.microsoft.onnxruntime:onnxruntime-genai` or `onnxruntime-genai-android` on Maven Central (both 404 on 2026-11-16). Published packages are pip and NuGet only (https://onnxruntime.ai/docs/genai/howto/install.html). Models need a `genai_config.json` beside the graphs (unverified detail), produced by its model builder. Maturity: active, but pre-1.0 and without JVM distribution.

**sherpa-onnx.** A speech toolkit on ONNX Runtime (Apache-2.0, https://github.com/k2-fsa/sherpa-onnx). Functions listed in its README: streaming and non-streaming ASR, TTS, VAD, speaker diarisation, speaker identification and verification, spoken language identification, keyword spotting, punctuation, audio tagging, speech enhancement, source separation, forced alignment. Model families named: Whisper, Moonshine, Parakeet (NeMo transducer), SenseVoice, Paraformer, Zipformer, Qwen3-ASR, Piper, Matcha, Kokoro, Kitten, Supertonic, Silero VAD. Bindings include Java and Kotlin. Distribution: JitPack, `com.github.k2-fsa.sherpa-onnx:sherpa-onnx-jvm:<tag>` plus `sherpa-onnx-native-lib-<linux-x64|linux-aarch64|osx-x64|osx-aarch64|win-x64|win-arm64>:<tag>` for desktop, and `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:<tag>` for Android; the documentation examples use tag v1.13.5 (https://k2-fsa.github.io/sherpa/onnx/java-api/non-android-java.html). The newest numbered GitHub release seen is v2.0.1 (26 Oct, year not shown). It is not on Maven Central. NPU paths named: QNN, RKNN, OpenVINO and others. Whether its Android AAR bundles its own `libonnxruntime.so`, and whether that collides with the `onnxruntime-android` 1.22.0 that Aidos already ships, is not stated on the pages read and must be tested (two copies of `libonnxruntime.so` in one APK conflict by file name).

### B.3 SafeTensors and PyTorch files

- SafeTensors (`.safetensors`) is a weight container: a JSON header and raw tensors, no code. It is the default format of original model releases on Hugging Face. It is not runnable by itself: it needs the model code (PyTorch and Transformers, MLX, Candle, Burn) or a conversion to GGUF, ONNX, `.pte`, `.tflite` or CTranslate2. stable-diffusion.cpp is an exception that reads diffusion SafeTensors directly.
- PyTorch `.bin`, `.pt`, `.pth` and `.ckpt` are Python pickle archives. Loading one can execute arbitrary code, which is the reason SafeTensors exists. Aidos should never parse these on a user device; conversion belongs in a sandboxed desktop or CI job, and the installer should keep refusing them.
- There is no JVM or Android runtime for raw SafeTensors that is worth considering. DJL (Deep Java Library) can host PyTorch natively on desktop JVM, at the cost of shipping libtorch (hundreds of MB; unverified size).
- Candle (Hugging Face) and Burn are Rust frameworks that load SafeTensors. They have no JVM binding; using them means writing and maintaining a Rust JNI library per model family. Not checked in detail in this pass.

### B.4 MLX

An array framework for Apple silicon (MIT, https://github.com/ml-explore/mlx); the README also lists Linux packages for CUDA and CPU. Windows is not mentioned. APIs: Python, C++, C, Swift. Models are SafeTensors plus config in MLX layout (the `mlx-community` organisation on Hugging Face; unverified in this pass), used by mlx-lm and mlx-whisper. No JVM or Android path. Relevant to Aidos only if a macOS or iOS host becomes a goal.

### B.5 LiteRT (TFLite), MediaPipe, LiteRT-LM, and Android system models

- LiteRT is the renamed TensorFlow Lite; files are `.tflite`. Google Maven: `com.google.ai.edge.litert:litert` 2.3.2. Classic use: small vision, audio and classification models on Android. Converters: ai-edge-torch (PyTorch to `.tflite`, Python; not checked in this pass).
- LiteRT-LM (Apache-2.0, https://github.com/google-ai-edge/LiteRT-LM) is Google's LLM runtime on LiteRT with its own bundle format `.litertlm`. README: Android, iOS, web, desktop, Raspberry Pi; Kotlin API marked stable "for Android and JVM", C++ and Python stable, Swift early preview. Models named: Gemma 3n, Gemma 4 (for example `gemma-4-E4B-it.litertlm`), Llama, Phi-4, Qwen; speech models Qwen3-ASR, Kokoro, Qwen3-TTS. Vision and audio input. Google Maven: `com.google.ai.edge.litertlm:litertlm-android` and `litertlm-jvm`, both at 0.19.1; the Android guide lists Linux, macOS and Windows for the JVM artifact (https://developers.google.com/edge/litert-lm/android). API classes: `Engine`, `EngineConfig`, `Conversation`, `Message`, `Content`, tools; backends `Backend.CPU()`, `Backend.GPU()`, `Backend.NPU(...)`. Version 0.19.0 had a breaking C API change, so the API is still moving. Minimum SDK is not stated on the page.
- MediaPipe LLM Inference API (`.task` bundles): `com.google.mediapipe:tasks-genai` is still published (0.10.35 on Google Maven). That LiteRT-LM replaces it is the common understanding but neither page read says so (unverified).
- Model availability: Google publishes Gemma in `.litertlm` under the `litert-community` organisation (unverified in this pass). Gemma weights are under the Gemma terms of use, not an OSI licence, and repos are gated (seen on the EmbeddingGemma card: "License: gemma", login and acceptance required). Gemma 4 is different: the Hugging Face API reports `google/gemma-4-*` and `litert-community/gemma-4-*` as `apache-2.0` and not gated (checked 2026-11-16).
- Android AICore, Gemini Nano and ML Kit GenAI (https://developer.android.com/ai/gemini-nano): not a format. The model is owned and updated by the system service AICore; apps call ML Kit GenAI APIs. The page lists direct model access (`GenerativeModel`), agent workflows, speech recognition, speech translation, text to speech, image description, summarisation, proofreading, rewriting and an embedding API. Google Maven shows `com.google.mlkit:genai-prompt` 1.0.0-beta5 and `genai-speech-recognition` 1.0.0-alpha2, so these are pre-release. Inference is on device. Constraints: only on supported devices with AICore, ML Kit GenAI additional terms apply, the model and its version are not under the app's control, and Finnish support for the speech APIs was not confirmed (unverified). It needs Google Play services components, which matters for an F-Droid style distribution (the Aidos repo has `fastlane/` and `metadata/` directories that suggest one; unverified intent).

### B.6 ExecuTorch

PyTorch's on-device runtime (BSD-style licence; unverified exact licence text). Files are `.pte`, produced ahead of time by a Python export and lowered to one specific backend, so a `.pte` is tied to the backend it was exported for. GitHub release v1.6.0 dated 12 Nov (year not shown; https://github.com/pytorch/executorch/releases): backends XNNPACK, Vulkan, Qualcomm QNN, Core ML, MLX, OpenVINO, CUDA; TensorRT in beta; MediaTek "temporarily unsupported"; MPS deprecated since 1.2.0; Windows wheels with C++ libraries. Earlier notes mention Voxtral Realtime, Parakeet, Sortformer and Silero VAD support and VLMs on QNN (SmolVLM-500M, InternVL3-1B). Maven Central: `org.pytorch:executorch-android` 1.5.1 (2026-10-01), plus `executorch-android-qnn` and `executorch-android-vulkan` at 1.5.1. The Android page says the AAR has two ABIs, arm64-v8a and x86_64, and shows `Module`, `EValue`, `Tensor` (https://docs.pytorch.org/executorch/stable/using-executorch-android.html). An LLM runner class for Android exists in the demo app (class name unverified). There is no desktop JVM artifact. Hugging Face availability is thin compared with GGUF and ONNX (an `executorch-community` organisation exists; unverified). Maturity: 1.x and actively released; strongest where a vendor NPU matters.

### B.7 MLC LLM and TVM

Compiles each model to a model library for a target with TVM. The Android guide (https://llm.mlc.ai/docs/deploy/android.html) requires the NDK, CMake, Rust (to cross-compile tokenizers) and JDK 17, and `mlc_llm package` to produce a local Gradle subproject `mlc4j` with `libtvm4j_runtime_packed.so` and `tvm4j_core.jar`. No prebuilt Maven artifact is mentioned; only arm64-v8a is shown; no desktop JVM binding is described. Weights can be downloaded at run time, but the compiled model library is bound into the app, so a user cannot install an arbitrary new architecture without a new app build. Licence Apache-2.0 (unverified). GPU reach on Android is OpenCL (unverified; the page only says "mobile GPUs").

### B.8 CTranslate2

Inference engine for Transformer encoder-decoder models (MIT; unverified licence), the engine under faster-whisper and a common format for NLLB, Opus-MT and M2M translation models. Release v4.9.0 dated 10 Nov, with steady releases through the year (https://github.com/OpenNMT/CTranslate2/releases); notes mention Python wheels for CPU, CUDA, ROCm and Apple silicon, and Windows. Android and Java bindings are not mentioned. Converter: `ct2-transformers-converter`, which `liturgos-auditor` already drives in `python-packages/liturgos-auditor-stt/auditor_stt/training/export_ct2.py`. Using CTranslate2 from Aidos would mean a self-built C++ library with self-written JNI per ABI. D62 already rules this out.

### B.9 Other runtimes

| Runtime / format | What it is | JVM / Android binding seen | Platforms | Accelerators | Licence | Note |
|---|---|---|---|---|---|---|
| OpenVINO (IR `.xml` + `.bin`) and OpenVINO GenAI | Intel runtime; GenAI pipelines for LLM, VLM, Whisper, SpeechT5 TTS, image and video generation, embeddings, rerank (https://github.com/openvinotoolkit/openvino.genai) | Python, C++, Node.js only. No Java artifact found (`org.openvino:openvino` is 404 on Maven Central) | Windows, Linux, macOS; Android not mentioned | Intel CPU, GPU, NPU | Apache-2.0 | Reachable today as an ONNX Runtime execution provider or a sidecar (OpenVINO Model Server; unverified) |
| TensorRT, TensorRT-LLM | NVIDIA engine plans, built per GPU model and driver | none checked | Windows, Linux, NVIDIA only | NVIDIA GPU | proprietary runtime (unverified terms) | Engine files are not portable between machines. Reachable as an ORT execution provider |
| Core ML (`.mlpackage`) | Apple format | none on JVM or Android | Apple only | Apple GPU and Neural Engine | system framework | Not relevant to Windows or Android |
| Windows ML / Foundry Local | Foundry Local is a local model service on ONNX Runtime with an OpenAI-compatible API, chat and Whisper transcription; SDK 2.2.1, CLI preview 0.11.0 (https://github.com/microsoft/Foundry-Local) | SDKs in C, C++, C#, JavaScript, Python, Rust; no Java | Windows, macOS, Linux | picks NPU, GPU or CPU provider itself | SDK MIT, CLI Microsoft licence terms | Usable from Aidos today as an OpenAI-compatible provider for chat |
| Qualcomm AI Hub, QNN context binaries, Genie | Precompiled per-chipset bundles for the Hexagon NPU; Genie is the GenAI layer of the QAIRT SDK (2.49.0, July 2026 per Qualcomm revision history) | A community project "GenieX" (BSD-3-Clause, developer preview) is reported to have a Kotlin SDK on Maven Central (secondary source, unverified) | Snapdragon Android and Windows on ARM | Hexagon NPU | QAIRT is proprietary (unverified terms) | Bundles are per chipset (for example SM8750), so one download does not fit all phones |
| MNN, MNN-LLM (Alibaba) | General mobile runtime with its own model format and converter from ONNX, TFLite, TorchScript; LLM, diffusion, ASR and TTS demos (https://github.com/alibaba/MNN, 3.6.1 dated 2026-07-22) | Android apps exist; no Java or Kotlin library artifact is mentioned in the README | Android, iOS, Linux; Windows not mentioned in README | OpenCL, Vulkan, Metal, CUDA, QNN, NNAPI and others | Apache-2.0 | Good phone performance claims; integration would be a self-built JNI |
| ncnn (Tencent) | Small mobile CNN runtime with Vulkan | not checked | Android, others | Vulkan | BSD-3 (unverified) | Used for small vision and some OCR models; no LLM story. Not checked in this pass |
| Vosk / Kaldi | Classic Kaldi ASR models, small and streaming | Maven Central `com.alphacephei:vosk-android` 0.3.75 (2025-12-08) and `com.alphacephei:vosk` | Android, desktop | CPU | Apache-2.0 (unverified) | A Finnish Vosk model was not confirmed (unverified); accuracy is below Whisper-class models |
| exl2 / ExLlamaV2, AWQ, GPTQ, vLLM, TGI | GPU server formats and servers | none; Python servers | Linux servers, NVIDIA | NVIDIA GPU | mixed | Only reachable as an OpenAI-compatible remote provider (vLLM and TGI expose one) |
| Candle, Burn | Rust ML frameworks reading SafeTensors | none | all | CPU, CUDA, Metal, wgpu | Apache-2.0 / MIT (unverified) | Would need a custom Rust JNI per model family |

### B.10 By modality: what the best small on-device models ship in

| Modality | Typical small models (2026) | Formats they actually ship in | Fits Aidos today? |
|---|---|---|---|
| LLM chat | Gemma 3/3n/4 small variants, Qwen small variants, Phi-4 mini, SmolLM | GGUF first (community quantisations within days), then `.litertlm` (Google models), ONNX for ORT GenAI (Phi, Qwen), MLX on Apple, `.pte` for a few | Yes, GGUF |
| Embeddings | EmbeddingGemma 300M (Gemma licence, float16 not supported per its card), nomic-embed, bge, e5, Qwen3-Embedding | SafeTensors original; GGUF and ONNX conversions are common (the card links "324 quantized models" without naming formats) | GGUF yes; ONNX no (no tokenizer, not in `onnxKinds`) |
| Rerankers | bge-reranker, Qwen3-Reranker, jina rerankers | SafeTensors original; ONNX and GGUF conversions | No route and no adapter |
| STT, Whisper family | whisper-small, large-v3-turbo, Finnish fine-tunes | SafeTensors original; ONNX (`onnx-community`), CTranslate2 (faster-whisper), ggml (whisper.cpp), MLX | ONNX yes |
| STT, other | Parakeet TDT 0.6B v3 (25 European languages incl. Finnish, CC BY 4.0, 600M parameters, FLEURS Finnish WER 13.21% per its card, https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3); Moonshine (no Finnish in its language list, https://github.com/moonshine-ai/moonshine); Canary; Qwen3-ASR; wav2vec2 fine-tunes | NeMo/SafeTensors original; ONNX exports via sherpa-onnx; a GGUF for "NeMo-Speech.cpp" is listed on the Parakeet card; ONNX for Moonshine (unverified format detail) | No (needs a transducer loop or sherpa-onnx) |
| Finnish STT specifically | Finnish-NLP/whisper-large-finnish-v3 reports normalised WER 8.23 (Common Voice 11) and 8.21 (FLEURS) against 10.82 and 9.63 for stock large-v3, read from a mirror of the model card (https://huggingface.co/mpasila/faster-whisper-large-finnish-v3; secondary). Parakeet v3 reports 13.21% on FLEURS, not directly comparable (normalisation differs) | SafeTensors and CTranslate2; ONNX needs an export | After an Optimum export, yes |
| TTS | Piper (VITS, one ONNX + JSON per voice; Finnish voice `fi_FI-harri` in https://huggingface.co/rhasspy/piper-voices); Kokoro 82M (Apache-2.0, 8 languages in v1.0, Finnish not confirmed and believed absent; https://huggingface.co/hexgrad/Kokoro-82M); Chatterbox Multilingual (MIT, 0.5B, 23 languages including Finnish, PyTorch; https://huggingface.co/ResembleAI/chatterbox); Qwen3-TTS; Supertonic, Matcha, Kitten via sherpa-onnx | ONNX for Piper, Kokoro, Matcha, Kitten; PyTorch for the larger LLM-based ones; GGUF for the few llama.cpp can drive | No engine at all |
| Vision / VLM | SmolVLM/SmolVLM2, Gemma 3/3n/4, Qwen-VL small, Moondream2, InternVL small | GGUF + mmproj for nearly all (llama.cpp list above); ONNX for transformers.js exports; `.litertlm` for Gemma | SmolVLM ONNX only; mmproj not reachable |
| OCR | PaddleOCR-family detectors and recognisers, small VLM-based OCR models, Florence-2 | ONNX (RapidOCR-style exports; unverified), GGUF + mmproj for VLM OCR (llama.cpp notes OCR models need specific prompts) | No |
| Translation | NLLB-200 (CC-BY-NC weights; unverified), Opus-MT/Marian (permissive per model), MADLAD-400 | SafeTensors original; CTranslate2 and ONNX conversions; MADLAD as GGUF (T5 support in llama.cpp; unverified) | No route; a chat LLM can translate |
| Image generation | SD 1.5/SDXL-Turbo class, FLUX, Z-Image | SafeTensors, GGUF (stable-diffusion.cpp), ONNX, MNN, QNN bundles | No |
| VAD / wake word | Silero VAD (ONNX), openWakeWord (ONNX/TFLite; unverified), sherpa-onnx keyword spotting | ONNX | No, but smallest possible addition |
| Speaker diarisation | pyannote segmentation + speaker embedding models | PyTorch original; ONNX exports through sherpa-onnx | No |
| Audio-in multimodal LLMs | Gemma 3n/4 E2B/E4B, Qwen2.5-Omni, Ultravox, Voxtral Mini | GGUF + audio mmproj (llama.cpp), `.litertlm` (Gemma) | No (needs mtmd or LiteRT-LM) |

Summary of the survey: two formats cover almost everything small and current. GGUF covers chat, embeddings, rerankers and (with mtmd) vision and audio input. ONNX covers speech (STT, TTS, VAD, diarisation), encoders and OCR. `.litertlm` is the only third format with both a Maven artifact for Android and for desktop JVM including Windows, and it is the native format of Google's Gemma releases.

## Part C. Difficulty and route for each format

Scale: S = days, M = one to two weeks, L = a month or more, X = not sensible. Routes: (1) native runtime in process through a Maven artifact or JNI, (2) convert to GGUF or ONNX offline, (3) sidecar process or remote provider behind `engine/providers`, (4) do not support.

### C.1 Ratings

| Format / runtime | Desktop | Android | Route | Main problems |
|---|---|---|---|---|
| GGUF chat, embeddings (today) | done | done | 1 | Binding is stale (4.1.0 was built on llama.cpp b4916; the tag for 4.2.0 is not stated); newer architectures (Gemma 3n/4, Qwen3 family) may fail to load, which was not tested here |
| GGUF rerankers | S | S | 1 | `LlamaModel.rerank` and `ModelParameters.enableReranking()` exist in the 4.2.0 jar. Needs a load-time mode like `embeddingMode`, a kernel adapter interface, a `/v1/rerank` route and tests with a real reranker GGUF. Runtime behaviour on Android was not exercised |
| GGUF + mmproj (vision, audio in) via the `net.ladenthin:llama` fork | M | M to L | 1 | Package rename across `LlamaCppAdapter`, `AndroidLlamaCppAdapter`, `LlamaChatPrompt`; behaviour differences in templates and grammar must be re-tested; fork min API 28 against Aidos minSdk 26; single-maintainer fork; installer must fetch the projector with the model (the `PROJECTOR` role exists already); memory estimate must add the projector |
| GGUF + mmproj by rebuilding the `de.kherud` native with mtmd | L | L | 1 | Aidos would own a JNI fork on two hosts (Windows build included) |
| GGUF + mmproj via `llama-server` sidecar | S to M | X | 3 | Desktop only. Needs image blocks and streaming in `OpenAiCompatibleModelAdapter`, a supervisor (pattern exists in `engine/aidin/.../SplitProcessSupervisors.kt`), and memory accounting for a process the runtime does not own |
| ONNX, new encoder families (embeddings, rerankers) | S to M | S to M | 1 | WordPiece and Unigram tokenizers in Kotlin (or `ai.djl.huggingface:tokenizers` 0.40.0, a JNI wrapper of Hugging Face tokenizers, whose Android support was not checked); pooling rules differ per model |
| ONNX, Silero VAD | S | S | 1 | State handling only. Removes the "cut at the quietest point" heuristic in `WhisperSttModel` |
| ONNX, Parakeet / Moonshine / other STT, hand-written | M each | M each | 1 | A new feature front end and decode loop per family; no shared code today |
| sherpa-onnx (STT families, TTS, VAD, diarisation, keyword spotting) | S to M | M | 1 | JitPack rather than Maven Central (supply-chain review); a second copy of ONNX Runtime may collide with `onnxruntime-android` on Android; model layouts are sherpa-specific exports, so the installer needs new package detectors; APK size |
| ONNX Piper TTS, hand-written | M | M to L | 1 | espeak-ng phonemisation: GPL-3.0 native library, per-ABI build. Without it there is no text front end for Finnish. The current Piper repo is itself GPL-3.0 (https://github.com/OHF-Voice/piper1-gpl); Aidos is EUPL-1.2, and compatibility of linking GPL-3.0 code needs the owner's legal view |
| ONNX Kokoro TTS | M | M | 1 | Same phonemiser problem; Finnish not available, so low value for the stated goals |
| ONNX LLMs through ORT GenAI | L | L | 1 | No published Java or Android artifact; Aidos would build and host the native library for Windows and arm64 itself. Duplicates what llama.cpp already does |
| ONNX LLMs, hand-written loop | L | L | 1 | Chat templates (no Jinja engine), sampling, tool-call constraints. Not sensible while GGUF exists |
| SafeTensors / PyTorch checkpoints | S (docs and CI) | X on device | 2 | Conversion needs Python (`convert_hf_to_gguf.py`, `optimum-cli export onnx`). Never on device. Pickle files must not be loaded |
| Whisper fine-tunes from liturgos-auditor | S | S (same package) | 2 | An ONNX export step does not exist in liturgos-auditor today; only `export_ct2.py`. The merged fp32 checkpoint it leaves in `_merged_hf` is the right input for `optimum-cli export onnx`. Quantisation choice and accuracy parity with the CT2 gate need checking. Weights must not be committed to that public repo |
| LiteRT-LM (`.litertlm`) | M | M | 1 | New native runtime beside llama.cpp and ORT; API still changing (0.19 breaking change); Gemma licence terms and gated downloads; memory admission needs a size estimate for a bundle; chat template and tools are owned by the runtime (less work, less control); Windows support claimed for the JVM artifact but untested here |
| LiteRT plain `.tflite` | M | S to M | 1 | Same per-family pre and post-processing cost as ONNX with fewer available models; desktop JVM artifact for Windows not confirmed |
| MediaPipe `.task` | X | X | 4 | Superseded by LiteRT-LM in practice (unverified as an official statement) |
| Android AICore / ML Kit GenAI | not applicable | M | 1 (system API) | Pre-release APIs; device allow-list; Google Play services dependency; model identity and version not attestable (conflicts with the digest-based model identity the Engine reports); Finnish support unconfirmed; outside memory admission |
| ExecuTorch (`.pte`) | X (no JVM artifact) | M to L | 1 | Android only through Maven; `.pte` is backend specific, so the catalogue would need one file per backend; few ready-made models; Aidos owns pre and post-processing except for the LLM runner |
| MLC LLM | X | L | 1 | Per-model compiled library inside the app, no Maven artifact, heavy toolchain. Does not fit "user installs any model" |
| CTranslate2 | L | X | 1 | No Java or Android distribution; D62 already excludes it. Use route 2 (re-export to ONNX) |
| whisper.cpp | M | M to L | 1 | Stale Maven artifacts; per-ABI build; duplicates the ONNX Whisper path. D62 excludes it. Its only advantages are beam search, timestamps and built-in VAD |
| stable-diffusion.cpp | L | L | 1 | No Java binding; new modality with no kernel type, no route; memory heavy |
| OpenVINO (IR) | M (as ORT execution provider) / L (native) | X | 1 or 3 | No Java artifact for the native runtime. Acceleration question, defer to RFC-0128 |
| TensorRT | defer to RFC-0128 | X | 1 (ORT EP) | Engine plans are machine specific; not a distribution format |
| Core ML, MLX | X | X | 4 | Apple only; no host in scope |
| Foundry Local, Ollama, LM Studio, vLLM, TGI, llama-server | S (chat works today) | X (providers module is JVM only) | 3 | Chat only today. STT, embeddings and vision through a provider need adapter work (S to M each). Not offline-first unless the server is on the same machine; the Engine does not manage its lifecycle or memory |
| QNN context binaries / Genie / GenieX | L | L | 1 | Per-chipset bundles, proprietary SDK terms, preview community SDK. Acceleration topic, defer to RFC-0128 |
| MNN | L | L | 1 | No library artifact; own format; self-built JNI; Windows not listed |
| ncnn, Vosk, Candle, Burn, exl2, GGML legacy | X | X | 4 | No model that Aidos needs is exclusive to them |

### C.2 Cross-cutting problems

1. **Native build per ABI.** Every runtime without a Maven artifact that covers both Windows x64 and Android arm64 means Aidos builds and ships native code for both. Today only ORT has that property out of the box; llama.cpp has it on desktop only with `de.kherud`, and on both with the fork; LiteRT-LM claims both.
2. **APK size.** The Engine APK carries `libjllama.so` (5.2 MB) and ORT. Each further runtime adds its own native library (ORT-class libraries are tens of MB; unverified exact figures). Three or more general runtimes in one APK needs a deliberate decision, or dynamic feature delivery.
3. **Pre-processing and loops in Kotlin.** On ORT, Aidos owns tokenizers, feature extraction, chat templates and KV-cache loops. This is about 900 to 1000 lines per family today and it is the real cost of "ONNX support". A shared tokenizer module (BPE, WordPiece, Unigram from `tokenizer.json`) and a shared greedy/KV-cache loop would lower the cost of each later family.
4. **Chat templates.** GGUF carries a Jinja template and llama.cpp evaluates it. ONNX and SafeTensors repos carry it in `tokenizer_config.json` or `chat_template.jinja`, and Kotlin has no Jinja engine in this repo, which is why `SmolVlmModel` hard-codes its template.
5. **Memory admission.** `GlobalModelRuntime` needs a resident-bytes estimate per model. Sidecars, remote providers and AICore sit outside it.
6. **Installer.** Search is GGUF-only; packages are three hard-coded detectors. Every new family needs a detector, a role in `PackageInstall.choose` and `kindOf`, an entry in `onnxKinds`, and (for new modalities) a kernel adapter interface and an HTTP route. A declarative package manifest (which files, which family) would replace the boolean flags on `ModelPackage`.
7. **Licences.** Runtime licences are mostly MIT or Apache-2.0. The problems are model licences (Gemma terms, CC-BY-NC for NLLB, CC BY 4.0 for Parakeet) and GPL-3.0 for Piper and espeak-ng against EUPL-1.2.
8. **Format security.** Pickle-based PyTorch files must never be opened on device. ONNX, GGUF and SafeTensors are data formats, but each parser is native code handling untrusted downloads; digests are checked at install (`sha256Digest` on `RepoFile`), and that should stay mandatory for new formats.
9. **Maintenance.** `de.kherud:llama` has not been released since June 2025 and ORT is pinned ten minor versions back. Staying current on the two existing runtimes is itself work that competes with adding new ones.

### C.3 Recommended order of work

Tied to the stated goals: a working Engine on Windows desktop and Android, Dictator (Finnish dictation, STT first) as the first user, models fine-tuned in liturgos-auditor, offline-first.

1. **ONNX export of liturgos-auditor Whisper fine-tunes (route 2, S).** Add an `export_onnx` step beside `export_ct2.py` that runs `optimum-cli export onnx` on the merged checkpoint and writes exactly the layout `WhisperPackage` expects (`encoder_model*.onnx`, `decoder_model_merged*.onnx`, `tokenizer.json`, `generation_config.json`, `preprocessor_config.json`). Gate it with the existing evaluation so ONNX accuracy is compared with the CT2 result. This is the shortest path from "fine-tuned model" to "Dictator on a phone", needs no new runtime, and respects D62. Weights go to external storage, not to the public repo.
2. **Silero VAD on ONNX Runtime (route 1, S).** Dictation quality depends on utterance segmentation more than on decoder details; `WhisperSttModel` currently cuts at the quietest sample near 30 s and has no speech detection. One small graph, both hosts, no new native code.
3. **Update the two existing runtimes (S to M).** Move ORT from 1.22.0 towards 1.32.0 and decide the llama.cpp binding question (item 5) before more code is written against stale APIs. This is also the precondition for whatever RFC-0128 chooses for execution providers.
4. **Whisper decoding improvements in Kotlin (S to M).** Timestamps and overlap or VAD-driven chunking for long dictation, optionally beam search. These are the features whisper.cpp and faster-whisper have and the Aidos loop lacks, and they are cheaper than a second runtime.
5. **Rerankers on the current binding (S), then the llama.cpp binding decision for mmproj (M).** Reranking needs no new dependency. Evaluate `net.ladenthin:llama` 6.0.0 on Windows and on Android (min API 28 against minSdk 26 is the first question). If acceptable, it brings current architectures, vision and audio input through mmproj, and reranking in one move, and the installer already knows the `PROJECTOR` role. If not acceptable, the fallback on desktop is a supervised `llama-server` sidecar and on Android the existing SmolVLM ONNX path.
6. **Shared tokenizer module and ONNX embeddings (S to M).** WordPiece and Unigram from `tokenizer.json`, then an ONNX `EmbeddingModelAdapter`. This serves the mmBERT-based encoder work as well and lowers the cost of every later ONNX family.
7. **Decide on sherpa-onnx as the speech toolkit (S to M after a spike).** It would give Parakeet v3 (Finnish, streaming-capable family), TTS engines, diarisation and keyword spotting at once, on both hosts, with Kotlin bindings. The spike must answer: ORT library collision on Android, JitPack acceptability, APK size, and whether Parakeet v3 beats a fine-tuned Whisper for Finnish dictation on the liturgos-auditor test set. Until the spike says yes, keep STT on the hand-written path.
8. **TTS (M).** Blocked on a licence decision, not on engineering: the only confirmed small Finnish voice is Piper `fi_FI-harri`, and Piper plus espeak-ng are GPL-3.0. Options are sherpa-onnx's Piper support (which still needs espeak-ng data; unverified how it is licensed and linked there), a non-espeak model with Finnish (Chatterbox Multilingual is PyTorch-only and 0.5B, so desktop sidecar only), or the Android system TTS as a provider.
9. **Providers on Android and for more modalities (S to M).** Make `engine/providers` multiplatform or add an Android equivalent, and extend the adapter with streaming, image blocks, `/v1/embeddings` and `/v1/audio/transcriptions`. This turns Foundry Local, Ollama, LM Studio and a desktop Aidos Engine on the LAN into fallbacks for everything a phone cannot run. It is not offline-first by itself, so it ranks below the in-process items.
10. **LiteRT-LM (M), only if Gemma 3n/4 on Android becomes a goal.** It is the one additional runtime with artifacts for both hosts. It should wait for RFC-0128, because its main advantage over GGUF is GPU and NPU reach on Android.
11. **Not now:** ExecuTorch, MLC LLM, MNN, QNN/Genie, OpenVINO native, TensorRT (all acceleration-driven, RFC-0128 territory), ORT GenAI (no JVM distribution), whisper.cpp and CTranslate2 (D62), image generation, Core ML and MLX.

### C.4 Open questions for the owner

1. Is raising the Android minSdk from 26 to 28 acceptable if that is the price of a maintained llama.cpp binding with mmproj and reranking? If not, is owning a JNI build of llama.cpp for Windows and Android acceptable?
2. Is a JitPack dependency (sherpa-onnx) acceptable, or must every dependency come from Maven Central or Google Maven, or be vendored and built in CI?
3. GPL-3.0 components (Piper, espeak-ng) and EUPL-1.2: may they be linked into the Engine, shipped as a separate optional process, or not used at all?
4. Should the Engine ever run a conversion (SafeTensors to GGUF or ONNX) on the user's desktop, for example in a managed Python environment, or is conversion strictly a publisher-side step (liturgos-auditor, CI, Hugging Face repos)?
5. Where do liturgos-auditor ONNX exports live (private Hugging Face repo, own storage), and how does the Engine installer authenticate to a private or gated repo? `HuggingFaceClient` sends no token today as far as this pass saw (unverified; only the search and model calls were read).
6. Which Whisper size is the Dictator target on Android: small (Finnish minimum per D62), or large-v3-turbo quantised (645 MB + 440 MB in the `onnx-community` export)? This decides whether Parakeet v3 (600M parameters) is worth a spike.
7. Is Parakeet v3 under CC BY 4.0 acceptable as a shipped or suggested model, and can it be fine-tuned in liturgos-auditor (a NeMo pipeline, not the current Transformers one)?
8. Are system-provided models (AICore, ML Kit GenAI, Android system TTS and speech recogniser) in scope at all, given that they are unattestable, device-dependent and tied to Google services?
9. How many general-purpose native runtimes may the Android APK carry (today two: llama.cpp and ORT)? Is a size budget for the APK defined?
10. Should gated or non-OSI model licences (Gemma terms, CC-BY-NC) be installable with a licence acceptance step (`LicenseAcceptanceStore` exists in `enginehost`), or filtered out of the browser?
11. Should the installer move from three hard-coded package detectors to a declarative manifest (for example an `aidos-package.json` in the repo or a curated catalogue entry) before more families are added?
12. Should `engine/providers` supervise local sidecars (start, stop, health, memory accounting), or only talk to servers the user started?

## Sources

- https://github.com/kherud/java-llama.cpp and /releases; Maven metadata https://repo1.maven.org/maven2/de/kherud/llama/maven-metadata.xml
- https://github.com/bernardladenthin/java-llama.cpp; https://repo1.maven.org/maven2/net/ladenthin/llama/maven-metadata.xml
- https://github.com/ggml-org/llama.cpp and docs/multimodal.md
- https://github.com/ggml-org/whisper.cpp; https://github.com/leejet/stable-diffusion.cpp
- https://repo1.maven.org/maven2/com/microsoft/onnxruntime/ (onnxruntime, onnxruntime-android, onnxruntime-android-qnn, onnxruntime_gpu, onnxruntime-extensions-android)
- https://github.com/microsoft/onnxruntime-genai/releases; https://onnxruntime.ai/docs/genai/api/java.html; https://onnxruntime.ai/docs/genai/howto/install.html
- https://github.com/k2-fsa/sherpa-onnx and /releases; https://k2-fsa.github.io/sherpa/onnx/java-api/non-android-java.html; https://k2-fsa.github.io/sherpa/onnx/java-api/anroid-java.html
- https://github.com/pytorch/executorch/releases; https://docs.pytorch.org/executorch/stable/using-executorch-android.html; https://repo1.maven.org/maven2/org/pytorch/executorch-android/maven-metadata.xml
- https://github.com/google-ai-edge/LiteRT-LM; https://developers.google.com/edge/litert-lm/android; Google Maven metadata under https://dl.google.com/android/maven2/com/google/ai/edge/ and /com/google/mediapipe/tasks-genai/ and /com/google/mlkit/
- https://developer.android.com/ai/gemini-nano
- https://llm.mlc.ai/docs/deploy/android.html
- https://github.com/OpenNMT/CTranslate2/releases
- https://github.com/openvinotoolkit/openvino.genai; https://github.com/alibaba/MNN; https://github.com/microsoft/Foundry-Local; https://github.com/ml-explore/mlx
- https://www.qualcomm.com/developer/software/qualcomm-ai-runtime-sdk-qairt; https://geniex.aihub.qualcomm.com/ (search results only)
- https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3; https://github.com/moonshine-ai/moonshine; https://huggingface.co/mpasila/faster-whisper-large-finnish-v3
- https://github.com/OHF-Voice/piper1-gpl; https://huggingface.co/rhasspy/piper-voices/tree/main/fi/fi_FI; https://huggingface.co/hexgrad/Kokoro-82M; https://huggingface.co/ResembleAI/chatterbox
- https://huggingface.co/google/embeddinggemma-300m; https://huggingface.co/onnx-community/whisper-large-v3-turbo/tree/main/onnx; https://github.com/huggingface/optimum-onnx
- https://repo1.maven.org/maven2/com/alphacephei/vosk-android/maven-metadata.xml; https://repo1.maven.org/maven2/ai/djl/huggingface/tokenizers/maven-metadata.xml; https://repo1.maven.org/maven2/io/github/givimad/whisper-jni/maven-metadata.xml
