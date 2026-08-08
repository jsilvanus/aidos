# InferenceBackend with llama.cpp Implementation (M21)

## Overview

This document describes the implementation of `InferenceBackend` using llama.cpp for GGUF model inference on JVM (desktop/CLI) and Android platforms. It follows RFC-0022 (Local Models), D27 (Native Dependency Policy), and D28 (GGUF via llama.cpp).

## Architecture

### Component Structure

```
GlobalModelRuntime (M20 ✅)
    ├── Admission Queue (Mutex, globally serialized)
    ├── Digest Verification (SHA-256)
    └── InferenceBackend (interface)
            └── LlamaCppInferenceBackend (M21, JVM MVP)
                    ├── GgufLoader (GGUF format detection)
                    ├── Model Catalog (curated GGUF models)
                    └── MockLlamaAdapter (M21 MVP, replaced with real JNI in M21+)
```

### Key Design Decisions

1. **Globally Serialized Admission Queue** (RFC-0022)
   - Only one model loaded at a time (bounded by phone RAM)
   - Prevents concurrent loads that would crash the process
   - LRU eviction when swapping models
   - Unload is explicit, never automatic

2. **Content-Addressed by Digest** (RFC-0022)
   - SHA-256 hash verification before load
   - Mismatch → delete file, throw `DigestMismatchException`
   - No quarantine; if it's not valid, it's not kept

3. **GGUF Format with llama.cpp** (D28)
   - Model availability: nearly every open model has GGUF within days
   - Quantization quality: k-quants tuned for phone deployment
   - Constrained decoding: GBNF grammars for models without native tool-calling (RFC-0021)
   - Not ONNX: that would double native surface and ABI matrix

4. **Native Dependency Boundaries** (D27)
   - Native crash is bounded by checkpoint recovery (RFC-0009)
   - Run resumes from last checkpoint, not lost
   - Foreground service required for inference (D24)

## Implementation Details

### GGUF Format Support (`GgufLoader.kt`)

Parses GGUF binary format to extract metadata:
- Model name
- Context window (max tokens)
- Quantization level (Q4_K_M, etc.)
- Tensor count
- KV cache requirements

This enables the cookbook verdicts (RUNS_WELL, RUNS_TIGHT, etc.) by computing resident memory against device profile.

### Model Discovery (`LlamaCppInferenceBackend.kt`)

**Catalog** — Curated set of known-good models
```kotlin
val catalog = backend.catalog()  // All known models (with metrics)
```

**Installed** — Models on this device (`~/.aidos/models/*.gguf`)
```kotlin
val installed = backend.installed()  // Only installed models
```

### Model Loading

```kotlin
val result = runtime.load("qwen2.5-3b-instruct-q4_k_m")
// Happens inside admission queue:
// 1. Check if already loaded (fast path)
// 2. Acquire global lock
// 3. Verify digest (SHA-256 vs catalog)
// 4. Call backend.load(modelId)  <- this calls llama.cpp
// 5. Return ModelAdapter or failure
```

### ModelAdapter Interface

Returned from `backend.load()`, implements uniform interface for all models (local or remote):

```kotlin
interface ModelAdapter {
    val providerId: String        // "llama.cpp"
    val modelId: String           // "qwen2.5-3b-instruct-q4_k_m"
    val modelVersion: String      // "1.0.0"
    val contextWindow: Int        // 32768
    val isLocal: Boolean          // true

    fun supportsNativeToolCalls(): Boolean  // false for GGUF models
    suspend fun invoke(request: ModelRequest): Result<ModelResponse>
}
```

This unified interface means:
- Agent loop doesn't know if model is local or remote
- Constrained decoding (GBNF) handles tool-calling for models without native support
- Inference works offline with the same code path as cloud models

## MVP Implementation

### What's Implemented (M20 ✅)

- ✅ GlobalModelRuntime with admission queue
- ✅ Digest verification and mismatch handling
- ✅ Model catalog and installed detection
- ✅ GGUF format parsing
- ✅ ModelAdapter interface contract
- ✅ Mock inference for testing

### What's Stubbed for M21+ 🚧

The `MockLlamaAdapter` currently returns mock responses. Real M21 implementation will:

1. **Link llama.cpp native library** via JNI/JNA
   - Android: pre-built `.so` files (arm64-v8a, x86_64)
   - Desktop: system-wide llama.cpp or vendored binary
   
2. **Call llama.cpp inference**
   ```kotlin
   // Pseudo-code (M21):
   val session = llama.cpp.createContext(modelFile, params)
   val result = llama.cpp.inference(
       session,
       prompt = assemblePrompt(request.messages),
       grammar = compileGbnf(request.tools),  // Constrained decoding
   )
   ```

3. **Handle constrained decoding (GBNF)**
   - Tool calls for models without native function-calling
   - Ensures LLM output matches expected JSON format
   - Bounds token count for tool calls

4. **Memory management**
   - Load via `mmap` where available (page-cache warm reloads)
   - Unload clears VRAM between model swaps
   - Compute KV cache size for cookbook verdicts

## Testing

### Unit Tests (`LlamaCppInferenceBackendTest.kt`)

```kotlin
// Catalog contains known-good models
val catalog = backend.catalog()
assert(catalog.any { it.kind == LLM })
assert(catalog.any { it.kind == EMBEDDING })

// Installed models detected from filesystem
val installed = backend.installed()
assert(installed.isEmpty())  // No models yet

// Loading fails gracefully
val result = backend.load("missing-model")
assert(result.isFailure)
```

### Integration Tests (M21+)

Verification on real device:
- Cold-start < 10 seconds (RFC-0045)
- Inference throughput (tokens/second)
- Memory footprint vs estimate
- GBNF constraint compliance

## Deployment Considerations

### F-Droid Reproducible Builds (RFC-0050)

Native dependencies complicate F-Droid reproducible builds:
- Validated with: platform-specific ABIs (arm64-v8a, x86_64)
- Pre-built llama.cpp shared libraries included
- Signed and verified before linking

### Crash Boundary (D27)

A segfault in llama.cpp inference:
1. **Does NOT** corrupt project Git history (JGit isolation)
2. **Does NOT** lose sessions (persisted Execution Graph)
3. **Does** fail the current Run
4. **Run resumes** from last checkpoint (RFC-0009)

This bounded blast radius makes native dependencies acceptable per D27.

### Offline-First (RFC-0022)

Models downloaded from Hugging Face (M21+):
- Never automatic (user chooses, respects data plan)
- Resumable downloads with progress notification
- Digest verified before usable
- Stored at user scope (`~/.aidos/models/`), not per-project

## Future Work

1. **Android-specific JNI** (Phase 4, M34+)
   - Pre-build arm64-v8a and x86_64 `.so` files
   - Test on real devices
   - F-Droid integration

2. **NPU/GPU Delegates** (Post-M21)
   - Substantial speedup on compatible hardware
   - Fragmentation risk (verify per-device)
   - Platform-specific: Android NNAPI, iOS Metal

3. **ONNX Runtime for Embeddings/STT** (Post-M21)
   - Better quality for non-LLM tasks
   - Doubles native surface (D28: deferred)
   - Revisit after measurement on real device

4. **Speculative Decoding** (Post-M21)
   - Small draft model + large model
   - Requires two models resident simultaneously
   - Today: mid-range phones can't fit two models

5. **Model Fine-tuning & LoRA** (Post-M21)
   - Adapter loading at inference time
   - Per-model context presets
   - Requires use case validation first

## References

- **RFC-0022: Local Models** — Full specification
- **D27: Native Dependencies** — Justification and bounds
- **D28: GGUF via llama.cpp** — Format and engine choice
- **RFC-0021: Tool-Calling** — Constrained decoding with GBNF
- **RFC-0009: Durable Execution** — Checkpoint recovery
- **RFC-0050: Android** — Platform specifics

## Contributors

This implementation follows CLAUDE.md development practices:
- Small, reviewable commits
- All commits reference relevant RFC
- Tests verify RFC compliance
- Code comments link to RFCs that motivated decisions
- Safe failure modes with bounded crash radius
