# M21 Milestone: Local LLM on Mid-Range Phone

**Status:** MVP infrastructure complete (M20 ✅), JVM implementation ready, real hardware verification pending.

## What's Done (M20 Infrastructure)

The foundation for M21 is complete:

1. **GlobalModelRuntime** ✅
   - Globally serialized admission queue (Mutex)
   - Digest verification (SHA-256)
   - Digest mismatch handling (delete, throw, don't quarantine)
   - Unload is explicit, never automatic

2. **InferenceBackend Interface** ✅
   - Abstract over GGUF model loading
   - Testable with mock implementations
   - Clear contract for catalog, installed, load, unload

3. **LlamaCppInferenceBackend** ✅
   - GGUF format detection and validation (GgufLoader)
   - Model catalog (curated GGUF models)
   - Installed model discovery (`~/.aidos/models/`)
   - Digest computation (SHA-256)
   - Model lifecycle management

4. **ModelAdapter Interface** ✅
   - Uniform inference API (local or remote)
   - Constrained decoding support for tool-calling
   - Used by routing layer and agent loop

5. **Tests** ✅
   - Catalog validation
   - Digest consistency
   - Load failure handling

## What Needs M21+ (Real Hardware)

### Phase: JVM to Real Hardware

**M21 Requirements:**
1. Real mid-range Android phone (e.g., Pixel 6a, Samsung Galaxy A52)
2. llama.cpp JNI binding integrated
3. Cold-start < 10 seconds verified
4. Inference throughput measured

**Tasks for M21:**

1. **JNI/JNA Binding Integration**
   ```
   [ ] Choose binding approach (llama-cpp-java vs hand-written JNI)
   [ ] Add dependency to modelruntime/build.gradle.kts
   [ ] Replace MockLlamaAdapter with real LlamaCppAdapter
   [ ] Call llama.cpp via JNI:
       - createContext(modelPath, contextSize, threads)
       - eval(tokens, grammar)
       - getLogits() for sampling
       - free()
   ```

2. **Constrained Decoding (GBNF)**
   ```
   [ ] Compile GBNF grammars for tool-calling (RFC-0021)
   [ ] Pass grammar to llama.cpp eval()
   [ ] Verify tool calls match expected schema
   [ ] Test with non-native-tool-calling models
   ```

3. **Memory Management**
   ```
   [ ] Use mmap where available
   [ ] Compute KV cache requirements
   [ ] Implement cookbook verdicts (RUNS_WELL, RUNS_TIGHT, EXCEEDS_CONTEXT, WILL_NOT_FIT)
   [ ] Measure on real device:
       - Cold-start time
       - Tokens per second
       - Memory footprint vs estimate
   ```

4. **Hardware Testing**
   ```
   [ ] Build for arm64-v8a (primary) and x86_64 (emulator)
   [ ] Test on real phone:
       - Load time < 10s
       - Inference < 200ms per turn (RFC-0045)
       - Memory doesn't exceed available
   [ ] Measure battery impact
   [ ] Test under memory pressure (other apps running)
   ```

5. **Foreground Service Integration (D24)**
   ```
   [ ] Wire into Android RuntimeServiceHost
   [ ] Require foreground service for inference
   [ ] Park Run if foreground unavailable
   [ ] Do NOT fall back to remote model
   ```

6. **Error Recovery**
   ```
   [ ] Segfault in llama.cpp → Run fails, resumes from checkpoint
   [ ] Corrupt model file → digest mismatch, delete, notify user
   [ ] Out of memory → RUNS_TIGHT verdict, offer shorter context
   [ ] llama.cpp native crash → bounded by RFC-0009 recovery
   ```

### Phase: Model Download (M22+)

After M21 verifies local inference works:

1. **Download from Hugging Face**
   ```
   [ ] User selects model from catalog
   [ ] Display cookbook verdict + resident memory estimate
   [ ] Resumable download to ~/.aidos/models/
   [ ] Compute digest after download
   [ ] Verify before marking installed
   [ ] Show progress in notification
   ```

2. **Cookbook Verdicts**
   ```
   [ ] Device profile (RAM, storage, CPU, threads)
   [ ] Model requirements (weights, KV cache at context length)
   [ ] Compute resident = weights + KV_cache + runtime_overhead
   [ ] Return verdict:
       - RUNS_WELL: fits with headroom
       - RUNS_TIGHT: fits, but app may be killed under pressure
       - EXCEEDS_CONTEXT: weights fit, context doesn't
       - WILL_NOT_FIT: weights alone exceed available
   ```

3. **Storage Management**
   ```
   [ ] Per-model size display
   [ ] Last-loaded timestamp
   [ ] Manual removal (never automatic)
   [ ] Warn before removing models you might want back
   ```

## Gate Criteria for M21

**RFC-0045 Budgets** must be met on a real mid-range phone:

- Cold-start (first load): < 10 seconds
- Inference latency: < 200ms per turn
- Memory: one 3B model + context fits in available RAM
- Battery: sustainable for 2-hour session

One LLM + one embedding model must both work on the same device.

## Testing Strategy

1. **Unit Tests** (done, M20)
   - Mock backend
   - Catalog structure
   - Digest computation

2. **Integration Tests** (M21)
   - Real GGUF files (small models for CI)
   - Load/unload sequence
   - Constrained decoding compilation
   - Error conditions

3. **Device Tests** (M21, requires hardware)
   - Real mid-range phone
   - Foreground service integration
   - Cold-start timing
   - Throughput measurement
   - Battery draw under sustained inference

4. **Regression Tests** (ongoing)
   - Admission queue serialization (don't regress to concurrent loads)
   - Digest verification (don't skip or weaken)
   - Unload on model swap
   - Memory doesn't leak across loads

## Blocked On

- Real mid-range Android phone for testing
- llama.cpp JNI binding (or decision on which one to use)
- Integration of foreground service (Android app component)

## Success Criteria

✅ M21 complete when:
1. One local LLM loads on a real phone
2. Cold-start < 10 seconds
3. Inference throughput meets RFC-0045
4. Agent loop runs a full session using local model
5. No segfaults; crashes are caught and logged
6. Run resumes from checkpoint after crash

## Next Steps

1. Acquire real mid-range phone if not already available
2. Choose llama.cpp JNI binding (llama-cpp-java vs hand-written)
3. Add binding to modelruntime build
4. Replace MockLlamaAdapter with real implementation
5. Test on device
6. Measure and optimize if needed
