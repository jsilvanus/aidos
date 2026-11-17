# Text-to-speech in the Engine

`POST /v1/audio/speech` speaks text with a Piper voice on ONNX Runtime (RFC-0117, text-to-speech section).
Finnish uses `fi_FI-harri-medium` with the Kotlin rule-based Finnish phonemizer. English uses `en_US-ljspeech-high`
with a CMU Pronouncing Dictionary lookup. No espeak-ng ships with the Engine.

## Fetching a voice package (manual, for now)

Weights are not in this repository. A voice is a directory under the models directory (see `engine/tts`, `VoicePackage`):

```
<modelsDir>/fi-harri/
    aidos-tts.json            {"language": "fi", "phonemizer": "finnish-rules"}
    fi_FI-harri-medium.onnx   from rhasspy/piper-voices, fi/fi_FI/harri/medium
    fi_FI-harri-medium.onnx.json
<modelsDir>/en-ljspeech/
    aidos-tts.json            {"language": "en", "phonemizer": "cmudict"}
    en_US-ljspeech-high.onnx
    en_US-ljspeech-high.onnx.json
    cmudict.dict              from github.com/cmusphinx/cmudict (BSD-2-Clause; keep the licence notice)
```

Voice files: `https://huggingface.co/rhasspy/piper-voices/resolve/main/<path>` (licence and dataset are on each
MODEL_CARD: harri is CC0, LJSpeech is public domain).

Run the opt-in real-voice test with `AIDOS_TTS_MODEL_DIR=<dir of one voice package>`.

## Limits

- Input is at most 4000 characters; long text is spoken sentence by sentence.
- Finnish phonemes are rule-based and approximate; English out-of-dictionary words use a rough letter-to-sound rule.
- Local voices, or a peer that holds the voice when the request's locality allows it.
- The Android host stacks the voice backend, but it is not verified on a device yet.
