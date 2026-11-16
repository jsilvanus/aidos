# Speech-to-text in the Aidos Engine

Status: implemented and tested on the JVM/desktop host with real Whisper weights; Android wiring written but never compiled or run
(the Android targets do not build in the cloud environment). Design home: RFC-0103 (Engine), Dictator plan S3.

## What it is

`POST /v1/audio/transcriptions` now runs a real speech-to-text model when the requested model is an STT package. Before this, the route
handed the audio to the chat model, and the llama.cpp adapters turned it into the placeholder text `[Audio: audio/wav]`; that path is kept
only for non-STT models. Wire shape is unchanged (sdk/CONTRACT.md): JSON with a base64 WAV in `file`, plus optional `language`
(`"fi"`) and `prompt` (spelling/vocabulary hint, e.g. liturgical terms), response `{ "text": "..." }`.

`file` is a WAV: 8/16/24/32-bit PCM or float, any channel count and sample rate (Engine resamples to 16 kHz mono). Anything else is
HTTP 400 on `file`. Audio over 30 s is cut into windows at the quietest point near each boundary, or, with Silero VAD installed
(below), at silences; there are no timestamps and no overlap, so send utterance-sized pieces. Pure silence returns an empty string rather than the invented text Whisper produces for it.

## Why ONNX Runtime Whisper, not faster-whisper or whisper.cpp

- **faster-whisper** is Python on CTranslate2; neither runs in the JVM or in an Android service, so it cannot sit behind the Engine.
- **whisper.cpp via JNI** fits Android, but needs a native build per ABI (not buildable or testable in the cloud) and a second native
  runtime next to llama.cpp.
- **Whisper on ONNX Runtime** reuses the runtime the Laya decision models already ship on both desktop (`onnxruntime`) and Android
  (`onnxruntime-android`), so there is no new native dependency and one code path for both hosts. The pre/post-processing (log-mel,
  tokenizer, KV-cached greedy decode) is plain Kotlin in `engine/stt`, unit-tested against transformers' feature extractor.

## Package layout and install

A model is a directory `<models dir>/<modelId>/` laid out like a Hugging Face `onnx-community/whisper-*` repo, flat:

```
encoder_model[_quantized|_int8|_fp16].onnx
decoder_model_merged[_quantized|...].onnx     (decoder_model*.onnx also works, slower: no KV cache)
tokenizer.json
generation_config.json, config.json, preprocessor_config.json   (optional; 128 mel bins for large-v3 comes from preprocessor_config)
```

```
node scripts/stt/fetch-whisper.mjs ~/.aidos/models/whisper-small onnx-community/whisper-small quantized
```

The Engine lists it as kind `stt` with capability `audio.transcriptions`. On desktop the in-app Hugging Face browser installs a
Whisper repo (`onnx-community/whisper-*` or an Optimum export) as one package: `ModelPackage.detectWhisper` picks one encoder plus
the KV-cached decoder of the same precision (int8 `_quantized` first, then `_int8`, fp32, `_fp16`) and the tokenizer/config
files, and the installer downloads them into a directory, all-or-nothing. The Android model screen has no package install yet
(Laya has the same gap); use the fetch script or copy the directory.

## What was measured (desktop JVM, CPU, int8 "quantized" graphs, this repo's tests)

| Model | Download | English (JFK, 11 s) | Finnish (FLEURS clip, 5 s) |
|---|---|---|---|
| whisper-tiny | ~41 MB | exact | CER 0.14, words garbled ("auttita paukset") |
| whisper-small | ~250 MB | exact | CER 0.04 ("Kuluvankauden ... heinäkun lopulla") |
| whisper-large-v3-turbo | ~1.1 GB | exact | CER 0.00 |

Finnish therefore wants small or larger (tiny mangles word boundaries); large-v3-turbo is perfect on this clip but too big for most phones. Tiny and base are for
smoke tests. The Android path has not been timed on a device.

## Voice activity detection (Silero VAD)

With a Silero VAD graph present, `WhisperSttModel` finds the speech first (`SileroVad`: 512-sample frames at 16 kHz, hysteresis, 250 ms
minimum speech, 500 ms minimum silence, 200 ms padding) and packs whole speech segments into windows of at most 30 s. Windows begin and
end in pauses, silence between windows is never sent to the decoder, and pure silence or noise yields an empty string without running
Whisper. Only one unbroken stretch of speech over 30 s still gets the old quietest-point cut. Without the graph nothing changes.

Install the graph (v5, MIT, ~2 MB) once for all Whisper models, or beside one package; the loader looks for `silero_vad.onnx` in the
package directory first, then in `<models dir>/silero-vad/`:

```
node scripts/stt/fetch-silero-vad.mjs ~/.aidos/models/silero-vad
```

The same ONNX Runtime serves it on desktop and Android (`OrtSileroGraph` in `jvmAndAndroidMain`); no new native dependency. The graph is the
v5 one (`input`, `state`, `sr`); a v4 graph is refused at load. Tests: `SileroVadTest` and `WhisperSttModelVadTest` (scripted, always run);
`SileroRealModelTest` runs the real graph when `AIDOS_VAD_MODEL` points at `silero_vad.onnx`. Not done: the Android model screen and the desktop
installer do not fetch it yet, and VAD parameters are not settable from the API.

## Using liturgos-auditor's fine-tunes

faster-whisper/CTranslate2 weights cannot be loaded here. liturgos-auditor writes the package for you:
`auditor-stt export-onnx --model <run dir> --out <dir> --merge-lora --quantize int8` (jsilvanus/liturgos-auditor, `docs/training-pipeline.md` §3.5b),
and `auditor-stt eval --backend onnx` compares its WER with the CT2 export. Copy the output directory into the models directory.
By hand: `optimum-cli export onnx --model <hf-checkpoint> --task automatic-speech-recognition-with-past out/`, optionally
quantize, then copy `encoder_model*.onnx`, `decoder_model_merged*.onnx` and the tokenizer files into a package directory. Fine-tunes
that change the vocabulary are fine; `tokenizer.json` and the added-token ids are read from the package, nothing is hardcoded.

## Tests

- Always run (`gradle :stt:jvmTest`): WAV decoding, log-mel against transformers golden values, FFT, tokenizer, the decode loop against
  scripted logits (prefix, language detection, prompt, suppression, loop cut, windows, silence), package detection; and in
  `enginehost` the route with a fake STT model.
- Opt in with real weights, skipped otherwise: set `AIDOS_STT_MODEL_DIR` to a package directory.
  `stt` `WhisperRealModelTest` transcribes the bundled public-domain `jfk.wav` (and silence); set `AIDOS_STT_FI_AUDIO` to a WAV plus
  `<wav>.txt` with what it says for a Finnish CER check (`AIDOS_STT_FI_MAX_CER`, default 0.3). `enginehost`
  `EngineHttpServerTranscriptionTest.realWhisperPackageTranscribesOverHttp` does the same through the HTTP route.
  Gradle caches test results, so add `--rerun` when only the environment changed.

## Not done

- Android: `AndroidOnnxSttBackend` mirrors the JVM decorator and the Laya one; unverified on a device. The int8 graphs are
  the intended phone size; whether small is fast enough there is open.
- No streaming/partial results, no timestamps, no GPU/NNAPI provider.
- Android in-app package install (desktop has it).
