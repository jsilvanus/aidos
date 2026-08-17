# RFC-0022: Inference Backend Architecture

Status: Draft

## Abstract

Define the backend architecture beneath the Aidos AI Engine so that model formats and inference runtimes are pluggable, capability-oriented, and independent of higher-level model routing. The initial implementations are llama.cpp for GGUF and ONNX Runtime for ONNX, while the contract remains open to additional runtimes and modalities.

## Motivation

RFC-0020 requires the AI Engine to be model-agnostic, provider-agnostic, multimodal, and able to discover, load, route, and unload models. The implementation now has a real GGUF/llama.cpp path and format-neutral Hugging Face model discovery. The next step must not turn `ModelRuntime` into an LLM-specific wrapper around llama.cpp.

Different runtimes expose different useful primitives. llama.cpp is optimized for local generative models; ONNX Runtime can execute embeddings, vision, audio, classifiers, and other tensor models and can select platform-specific execution providers. A common interface therefore needs to expose capabilities without pretending every backend is a text generator.

## Goals

1. Define a stable boundary between Aidos model lifecycle/routing and inference runtimes.
2. Make inference backends discoverable by model format and supported capabilities.
3. Support multiple capabilities without creating a single god-shaped `infer()` API.
4. Preserve the existing GGUF/llama.cpp implementation while allowing it to be incrementally improved.
5. Make ONNX Runtime a first-class backend with generic tensor inference.
6. Support streaming, cancellation, batching, model metadata, diagnostics, and accelerator information where the underlying runtime supports them.
7. Allow Android and JVM/Desktop to share the same higher-level contracts while using platform-specific runtime implementations.
8. Keep runtime-specific concepts behind the backend boundary.

## Non-goals

This RFC does not define:

- model discovery or Hugging Face APIs; RFC-0020 and the existing model discovery layer cover those concerns;
- a specific model catalogue or registry;
- training or fine-tuning;
- a mandatory execution provider or accelerator;
- a new daemon protocol;
- a universal multimodal tensor representation for every future modality;
- a requirement that every backend implement every capability.

## Design

### Layers

```text
AI Engine / ModelRuntime
        |
        | model lifecycle, routing, resource policy
        v
Inference Backend Registry
        |
        +-------------------+-------------------+
        |                                       |
LlamaCppBackend                         OnnxRuntimeBackend
        |                                       |
      GGUF                                     ONNX
        |                                       |
   llama.cpp                              ONNX Runtime
        |                                       |
  CPU/Vulkan/CUDA/etc.                  CPU/XNNPACK/NNAPI/QNN/etc.
```

The engine selects a backend from an installed model's format and the backend's advertised capabilities. Hardware execution providers remain implementation details of the backend unless surfaced as diagnostics or selection constraints.

### Backend identity and capabilities

Every backend exposes stable identity and a capability set. Capability names describe what the backend can do, not security permissions.

Initial capability families:

- `TEXT_GENERATION`
- `CHAT`
- `EMBEDDING`
- `VISION`
- `AUDIO`
- `SPEECH_TO_TEXT`
- `TEXT_TO_SPEECH`
- `TOKENIZATION`
- `STREAMING`
- `BATCHING`
- `CANCELLATION`
- `MODEL_METADATA`
- `TENSOR_INFERENCE`

A backend may advertise additional capabilities later. A capability is only advertised when the implementation can actually satisfy its contract.

### Avoiding a god interface

The common backend contract should contain lifecycle, identity, diagnostics, and capability discovery. Functional operations are capability-specific.

Conceptually:

```kotlin
interface InferenceBackend {
    val id: BackendId
    val supportedFormats: Set<ModelFormat>
    val capabilities: Set<InferenceCapability>

    suspend fun load(model: ModelDescriptor): LoadedBackendModel
    suspend fun unload(model: LoadedBackendModel)
    suspend fun inspect(model: ModelDescriptor): BackendModelInfo
    suspend fun health(): BackendHealth
}

interface TextGenerationBackend : InferenceBackend {
    suspend fun generate(request: TextGenerationRequest): TextGenerationResult
    fun stream(request: TextGenerationRequest): Flow<TextGenerationEvent>
}

interface EmbeddingBackend : InferenceBackend {
    suspend fun embed(request: EmbeddingRequest): EmbeddingResult
}

interface TensorBackend : InferenceBackend {
    suspend fun infer(request: TensorInferenceRequest): TensorInferenceResult
}
```

These are conceptual contracts for this RFC; exact Kotlin types are implementation work after acceptance.

A backend may implement several capability interfaces. ONNX Runtime is expected to implement `TensorBackend` and then higher-level capability adapters as appropriate for individual model families. llama.cpp implements `TextGenerationBackend` and any additional interfaces supported by its native wrapper.

### Generic tensor inference

ONNX Runtime requires a generic tensor contract because an ONNX graph may have multiple typed inputs and outputs.

Conceptually:

```text
TensorInferenceRequest
  model
  inputs: Map<String, Tensor>
  execution options

Tensor
  name
  element type
  shape
  data

TensorInferenceResult
  outputs: Map<String, Tensor>
  metadata
  timing
```

The abstraction must support at least the common ONNX scalar types and dynamic shapes without converting everything to strings or boxed objects. Large tensors should avoid unnecessary copies where platform APIs permit it.

### Text generation

Text generation remains a higher-level capability. It includes:

- prompt/messages;
- generation parameters;
- context limits;
- stop conditions;
- optional grammar/tool constraints when supported;
- streaming token/text events;
- cancellation;
- usage/timing metadata.

The backend must report unsupported generation features rather than silently ignoring them.

### Model format and backend selection

The existing format-neutral model artifact layer remains the discovery representation:

```text
ModelArtifact
  format: GGUF | ONNX | ExecuTorch | TFLite | OpenVINO | ...
  compatibleBackends: [...]
  download metadata
```

The backend registry provides the runtime side of that relationship:

```text
GGUF  -> llama.cpp
ONNX  -> ONNX Runtime
PTE   -> ExecuTorch (future)
```

A model can have multiple artifacts for different runtimes. Selection must consider format, backend availability, platform profile, resource requirements, requested model kind/capability, and routing policy as specified by RFC-0020.

### Lifecycle

The backend lifecycle is separate from file installation:

```text
Discovered
   -> Downloaded
   -> Installed
   -> Loaded
   -> Active
   -> Unloaded
```

`DownloadManager` obtains verified bytes. The backend does not own downloading. A backend may inspect an installed artifact and then load it, but it must not silently download missing model files.

### Resource and diagnostics

Backends should expose diagnostics sufficient for the engine to make informed decisions:

- runtime name/version;
- supported model formats;
- available execution providers/devices;
- loaded model information;
- memory/resource estimates where available;
- timing and token/tensor throughput where meaningful;
- unsupported-feature reasons.

The engine owns policy. A backend reports facts and executes requests within those constraints.

## Backend 1: llama.cpp / GGUF

The existing backend remains the first implementation.

MVP support:

- GGUF model loading/unloading;
- text generation;
- streaming where the native wrapper supports it;
- generation parameters;
- model metadata;
- context handling;
- cancellation where supported;
- runtime/device diagnostics.

Existing limitations are explicit rather than hidden. Grammar/tool calling, advanced multimodal inputs, and other llama.cpp features should be added only when the Kotlin/native boundary can represent them correctly.

## Backend 2: ONNX Runtime

ONNX Runtime is the first new backend and should be implemented as a genuinely generic tensor runtime rather than as an "ONNX LLM backend".

Target support:

- ONNX model loading/unloading;
- graph/input/output introspection;
- typed tensors;
- multiple inputs and outputs;
- dynamic shapes;
- batching;
- generic tensor inference;
- model metadata;
- execution-provider discovery/selection;
- CPU execution;
- Android and JVM/Desktop support where dependencies permit;
- cancellation/resource lifecycle according to the underlying API;
- timing and diagnostic information.

Higher-level adapters may provide embeddings, vision, STT, classification, or text generation for known model families without changing the generic ONNX contract.

## Security

Backends execute local model code/data and therefore sit inside the runtime trust boundary. A backend must not gain additional filesystem, network, shell, or project permissions merely because it is selected for inference.

Downloaded model artifacts must pass the existing integrity verification before installation. Backends receive an installed artifact reference, not arbitrary user-controlled filesystem paths where the higher-level engine can provide a safer model handle.

Remote provider egress remains governed by RFC-0020 routing policy. A local backend must not initiate network access for model inference unless that behavior is explicitly part of a separate provider/backend contract and permitted by policy.

Resource exhaustion is a security concern on mobile devices. Model loading must participate in the device-wide resource/admission policy from RFC-0020 and RFC-0049.

## MVP

The MVP for this RFC is deliberately split:

1. Refactor the existing `InferenceBackend` boundary to support capability discovery without breaking llama.cpp.
2. Implement `LlamaCppBackend` against the supported text-generation capabilities.
3. Implement `OnnxRuntimeBackend` with generic typed tensor inference and lifecycle management.
4. Add backend registry/discovery and format-to-backend matching.
5. Add CLI diagnostics for available backends and their capabilities.
6. Add deterministic backend tests using small fixture models where licensing and repository size permit.

The MVP does not require every ONNX model family to have a high-level Aidos adapter. Generic tensor inference is the stable foundation.

## Open Decisions

The following decisions should be settled before implementation is declared complete.

### D1. How broad should the common tensor abstraction be?

**Option A — ONNX-shaped tensors:** optimize the common tensor API for ONNX Runtime. Simple and efficient, but risks making Aidos's future backends conform to ONNX's representation.

**Option B — Aidos-owned tensor model:** define an engine-neutral tensor type and adapt ONNX/other runtimes to it. More work now, but stronger long-term independence.

**Recommendation:** B, if we expect more tensor runtimes; otherwise A is acceptable for the first ONNX implementation.

### D2. Should capability interfaces be Kotlin interfaces or a single sealed operation API?

**Option A — capability interfaces:** `TextGenerationBackend`, `EmbeddingBackend`, `TensorBackend`, etc. Type-safe and easy to discover.

**Option B — sealed operation/request API:** one backend receives typed operation objects. Easier registry/dispatch, but less natural for capability-specific APIs.

**Recommendation:** A for the public/internal engine contract, with a sealed request model underneath if needed for routing.

### D3. Should backend selection be deterministic or allow backend preference policy?

**Option A — deterministic format mapping:** GGUF always means llama.cpp, ONNX always means ONNX Runtime.

**Option B — candidate backends + policy:** multiple backends can support the same format, and platform/resource/routing policy selects one.

**Recommendation:** B. It prevents Aidos from baking today's runtime choices into the model format abstraction.

### D4. Should execution-provider selection belong to Aidos or ONNX Runtime?

**Option A — backend-owned:** ONNX Runtime chooses the best provider.

**Option B — Aidos policy:** Aidos requests constraints/preferences such as CPU, GPU, or accelerator; the ONNX backend translates them into providers.

**Recommendation:** B for user-visible policy, A for provider-specific heuristics. Aidos should express intent; the backend should know provider mechanics.

### D5. Should `InferenceBackend` itself expose streaming?

**Option A — capability interface only:** streaming belongs to `TextGenerationBackend`.

**Option B — common backend primitive:** every backend can expose an event stream.

**Recommendation:** A. Streaming is meaningful for generation and some future workloads but is not a universal inference property.

### D6. How much multimodality belongs in the first backend contracts?

**Option A — text + tensors only:** establish solid primitives first.

**Option B — image/audio types now:** design all modality representations immediately.

**Recommendation:** A. ONNX tensor inference can support multimodal models without prematurely freezing Aidos's image/audio data model.

### D7. Should backend implementations be KMP `commonMain` abstractions with platform implementations?

**Option A — KMP boundary + platform implementations:** shared contracts, JVM/Android native implementations.

**Option B — separate backend modules per platform:** less KMP complexity but more duplicated API surface.

**Recommendation:** A, consistent with the existing engine architecture and RFC-0002.

## Future Work

- ExecuTorch backend for Android/PyTorch Edge models.
- OpenVINO backend for Intel hardware.
- TensorRT backend for NVIDIA deployments.
- Media/vision-specific optimized backends where ONNX is insufficient.
- Backend plugins/extensions after the plugin architecture is settled.
- More sophisticated accelerator and memory-aware routing.
- Zero-copy tensor interchange between compatible backends.

## Relationship to Existing RFCs

- **RFC-0002 Runtime:** establishes the modular, headless runtime and narrow native boundaries.
- **RFC-0020 AI Engine:** establishes model/provider agnosticism, capability taxonomy, lifecycle, routing, and resource policy. This RFC refines the inference-runtime layer without replacing those decisions.
- **RFC-0025 Context:** generation context constraints remain above the backend; the backend reports its actual limits.
- **RFC-0049 Platform Profiles:** backend availability and accelerator/resource constraints are platform inputs to routing.
- **RFC-0054 Model Runtime:** device-wide model storage/loading lifecycle remains authoritative; this RFC defines the inference backend boundary underneath it.

## Alternatives Considered

### One runtime for all local models

Rejected. llama.cpp is excellent for GGUF generative models but is not the right abstraction for arbitrary tensor workloads.

### ONNX Runtime as the only local backend

Rejected. ONNX is broad, but GGUF/llama.cpp is already a strong and efficient local LLM path and has a mature ecosystem.

### Backend-specific APIs with no common contract

Rejected. The AI Engine needs common lifecycle, capability discovery, diagnostics, and routing integration.
