# RFC-0022: Inference Backend Architecture

**Status:** Draft — decisions D36–D44 settled; implementation pending

## Abstract

Define the inference-runtime boundary beneath the Aidos AI Engine so model formats and runtimes are pluggable, capability-oriented, and independent of higher-level routing. The first runtimes are llama.cpp for GGUF and ONNX Runtime for ONNX.

This RFC deliberately treats Aidos as a general AI runtime, not an LLM wrapper. Text generation, embeddings, vision, audio, and arbitrary tensor inference are capabilities of models/backends rather than assumptions baked into the base interface.

## Goals

1. Provide a stable backend lifecycle and capability-discovery contract.
2. Keep model format, backend, and routing policy separate.
3. Support capability-specific operations without a god-shaped `infer()` API.
4. Preserve and improve the existing GGUF/llama.cpp path.
5. Make ONNX Runtime a first-class generic tensor backend.
6. Make streaming a first-class capability without requiring every backend to implement it.
7. Generalize kernel `ModelResponse` around typed outputs rather than text alone.
8. Share contracts through KMP while allowing platform-specific runtime implementations.
9. Expose diagnostics and execution constraints without leaking runtime-specific mechanics upward.

## Non-goals

This RFC does not define model discovery/Hugging Face APIs, training, a universal representation for every future modality, a daemon protocol, or a requirement that every backend support every capability.

## Architecture

```text
                     Aidos Engine / ModelRuntime
                               |
                       Backend Registry
                               |
              +----------------+----------------+
              |                                 |
       LlamaCppBackend                    OnnxRuntimeBackend
              |                                 |
             GGUF                               ONNX
              |                                 |
          llama.cpp                        ONNX Runtime
```

The engine owns model lifecycle, routing, admission/resource policy, and backend selection. Backends own runtime-specific execution and accelerator mechanics.

An installed model artifact declares its format and compatible backends. Selection is policy-driven: format, available backend, requested capability, platform profile, resource constraints, and user/routing preference may all participate.

## Backend contract

The base contract contains lifecycle, identity, capability discovery, inspection, and diagnostics. Operations are capability-specific.

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
    suspend fun generate(request: TextGenerationRequest): ModelResponse
    fun stream(request: TextGenerationRequest): Flow<ModelOutputEvent>
}

interface EmbeddingBackend : InferenceBackend {
    suspend fun embed(request: EmbeddingRequest): ModelResponse
}

interface TensorBackend : InferenceBackend {
    suspend fun infer(request: TensorInferenceRequest): ModelResponse
}
```

These are architectural contracts; exact Kotlin types are implementation work.

Initial capability families include `TEXT_GENERATION`, `CHAT`, `EMBEDDING`, `VISION`, `AUDIO`, `SPEECH_TO_TEXT`, `TEXT_TO_SPEECH`, `TOKENIZATION`, `STREAMING`, `BATCHING`, `CANCELLATION`, `MODEL_METADATA`, and `TENSOR_INFERENCE`.

A backend advertises a capability only when it can satisfy its contract. Unsupported features must be reported rather than silently ignored.

The `capabilities` set is the discovery/routing surface; the capability-specific Kotlin interfaces are the executable type-safe contracts. Implementations must keep these consistent: a backend must not advertise a capability for which it cannot provide the corresponding contract.

`InferenceCapability` is intentionally distinct from Aidos security `Capability`. The two concepts must not share a generic `Capability` type or namespace.

## Streaming

Streaming is a **capability**, not a requirement of every backend. This avoids forcing embeddings or one-shot classifiers to invent a meaningless stream while allowing generation, STT, video, and future workloads to stream incremental results.

A completed operation returns a `ModelResponse`. A streaming operation returns an event stream whose events are typed model outputs and completion/error events. For example:

```text
ModelStream
  -> TextDelta
  -> TextDelta
  -> ToolCallDelta
  -> Completed(ModelResponse)
```

The stream abstraction must not assume that every stream consists of tokens. Tensor/audio/frame chunks can be supported later without changing the base inference model.

Cancellation semantics are part of the streaming contract: when the consumer cancels collection, the backend should propagate cancellation to the underlying inference operation when the native/runtime API permits it. A backend must not continue expensive generation merely because its output stream is no longer being consumed.

## Generalized ModelResponse

The kernel response model is generalized from a text-centric structure to a typed-output structure. This is an intentional kernel change; the kernel is allowed to evolve and should not preserve a text-only limitation merely for compatibility.

Conceptually:

```kotlin
data class ModelResponse(
    val outputs: List<ModelOutput>,
    val stopReason: StopReason?,
    val usage: Usage?,
    val model: ModelRef?,
    val metadata: Map<String, String> = emptyMap()
)

interface ModelOutput

data class TextOutput(val text: String) : ModelOutput
data class ToolCallOutput(val call: ToolCall) : ModelOutput
data class TensorOutput(
    val name: String,
    val tensor: Tensor
) : ModelOutput
```

`outputs` is an ordered sequence. Ordering is semantically meaningful and must be preserved by backends; this permits future multimodal results to express the order in which heterogeneous outputs belong together.

The response retains model identity/version information through `ModelRef` (or the equivalent kernel type). Existing token accounting also remains first-class: `Usage` must preserve at least input/prompt token count and output/completion token count when the backend can provide them, with optional total-token and modality-specific usage fields. Token counts are informational and may be unavailable for runtimes that cannot report them.

Additional output types such as image and audio may be added when their contracts are mature. `TensorOutput` is the generic extensibility primitive for model results that do not yet have a higher-level Aidos semantic type.

The kernel should retain ergonomic accessors such as `response.text()` and `response.toolCalls()` so existing agent code remains simple. Raw untyped `Any` output bags are explicitly avoided. During migration, compatibility accessors/properties may be retained or deprecated rather than forcing unrelated consumers to change simultaneously.

This makes an embedding response, for example, a normal model response containing a named tensor output rather than requiring an unrelated top-level result hierarchy. Higher-level adapters can interpret tensors as embeddings, detections, classifications, or other semantics.

## Tensor inference

The tensor contract must support typed inputs and outputs, multiple named tensors, dynamic shapes, and common scalar types without stringification or unnecessary boxing. Where platform APIs permit it, implementations should avoid unnecessary copies.

Conceptually:

```text
TensorInferenceRequest
  model
  inputs: Map<String, Tensor>
  execution constraints

Tensor
  element type
  shape
  layout / strides
  storage

Tensor output
  named tensors
```

A tensor's shape is not sufficient to describe its memory interpretation. The contract therefore exposes layout/strides as an extensibility point so contiguous, strided, transposed, sliced, and view-backed tensors can be represented without copying merely to normalize them.

`storage` is an engine-neutral memory abstraction rather than a required `ByteArray`. It must be possible for implementations to use CPU buffers, native memory, memory-mapped storage, or future platform/device-backed storage without forcing a native → byte-array → Kotlin → native copy cycle. Ownership/lifetime semantics for borrowed and owned storage must be defined by the concrete tensor implementation.

The tensor contract must not require all implementations to support device-resident or zero-copy storage in the MVP. It must, however, avoid an API shape that prevents those implementations later.

`TensorOutput` always carries a stable output `name`. Multiple outputs from one inference are represented as multiple named `TensorOutput` values. This is required for ONNX graphs and other runtimes where output names carry semantic meaning.

Tensor element types should cover the common numeric/scalar types required by the initial backends without committing the API to a particular provider's type system. Unsupported types must fail explicitly rather than silently converting through strings or lossy scalar boxing.

Aidos owns the engine-neutral tensor contract; ONNX Runtime is adapted to it rather than defining the Aidos API. This preserves backend independence.

## Backend selection and hardware

The registry may have several candidates for a format. Selection is not permanently hard-coded to one runtime. Aidos expresses user-visible constraints/preferences such as CPU/GPU/accelerator and resource limits; the backend translates those into runtime-specific execution providers.

Hardware/provider selection is deliberately not exposed as backend-specific methods such as `loadWithCuda()` or `loadWithNNAPI()`. Those mechanics remain behind the backend boundary.

For ONNX Runtime, provider mechanics remain inside the ONNX backend. Diagnostics may expose providers/devices so routing can make informed decisions.

## Model lifecycle

Downloading/installing model artifacts and loading them into a runtime are separate responsibilities:

```text
Artifact lifecycle:
Discovered -> Downloaded -> Installed -> Verified

Runtime lifecycle:
Verified -> Loaded -> Active -> Unloaded
```

`DownloadManager`/`ModelRuntime` obtains, installs, and verifies model bytes. Backends receive installed model references and do not silently download missing artifacts.

`ModelRuntime` remains responsible for device-wide admission/resource policy, loaded-model tracking, integrity verification, and backend selection. The backend owns the runtime-specific mechanics of loading/unloading its model representation and releasing native/provider resources.

This separation preserves the existing global admission queue and digest-verification model while allowing different runtimes to implement loading efficiently. A backend must not independently invent a competing device-wide eviction or admission policy.

## Backend: llama.cpp / GGUF

The existing backend remains supported and is incrementally brought behind the new capability contract.

Initial supported capabilities:

- GGUF load/unload;
- text generation and chat;
- streaming where the native wrapper supports it;
- generation parameters and context handling;
- model metadata;
- cancellation where supported;
- runtime/device diagnostics.

Other llama.cpp features are added only when the Kotlin/native boundary can represent them correctly.

## Backend: ONNX Runtime

ONNX Runtime is the first new backend and is implemented as a generic tensor runtime, not as an ONNX LLM wrapper.

Target capabilities include:

- ONNX load/unload;
- graph/input/output introspection;
- typed tensors;
- named multiple inputs/outputs;
- dynamic shapes;
- layout-aware tensor interchange;
- batching;
- generic tensor inference;
- model metadata;
- execution-provider discovery and selection;
- CPU execution;
- Android and JVM/Desktop implementations where dependencies permit;
- lifecycle/cancellation according to the underlying API;
- timing and diagnostics.

Higher-level adapters may expose embeddings, vision, STT, classification, or generation for known model families without changing the generic ONNX contract.

## Security and resources

Backends are inside the runtime trust boundary. Selecting an inference backend must not grant arbitrary filesystem, network, shell, or project permissions. Installed model artifacts must pass existing integrity verification.

Local inference must not initiate network access merely because a model is missing or a backend is selected. Remote egress remains governed by existing routing policy.

Model loading participates in device-wide resource/admission policy; memory exhaustion is especially important on mobile.

## MVP

1. Generalize the existing backend boundary without breaking llama.cpp.
2. Add capability discovery and backend registry/format matching.
3. Implement llama.cpp against text-generation and streaming capabilities supported by the current wrapper.
4. Implement ONNX Runtime generic typed tensor inference and lifecycle management.
5. Generalize kernel `ModelResponse` to typed outputs and migrate all existing consumers.
6. Preserve input/output token usage in the generalized response when the runtime can report it.
7. Add backend and capability diagnostics to the engine CLI.
8. Add deterministic fixture-based tests for lifecycle, tensor inference, text generation, streaming, cancellation, token usage, and generalized responses.

## Settled architectural decisions

The following are recorded in `docs/decisions.md` as D36–D44:

- **D36:** inference backends are capability-oriented;
- **D37:** functional operations use capability-specific interfaces;
- **D38:** backend selection uses candidates plus policy rather than permanent format mapping;
- **D39:** Aidos owns execution constraints while backends own accelerator/provider mechanics;
- **D40:** streaming is a first-class capability;
- **D41:** tensor output is the generic extensible multimodal primitive;
- **D42:** contracts are shared through KMP with platform-specific implementations;
- **D43:** `ModelResponse` is generalized to typed outputs;
- **D44:** ONNX Runtime is the second inference backend.

These decisions supersede the earlier open questions in the draft RFC. The amendments in this revision further constrain D41/D43 so that tensor outputs are named and storage is capable of zero-copy implementations, while response usage retains token accounting. Implementation details remain subject to normal code review and tests.

## Relationship to existing RFCs

- **RFC-0002 Runtime:** modular, headless runtime and native boundaries.
- **RFC-0020 AI Engine:** model/provider agnosticism, capability taxonomy, lifecycle, routing, and resource policy.
- **RFC-0025 Context:** generation context remains above the backend; backends report actual limits.
- **RFC-0049 Platform Profiles:** platform availability and accelerator/resource constraints feed routing.
- **RFC-0054 Model Runtime:** model storage/loading lifecycle remains authoritative; this RFC defines the inference boundary underneath it.

## Future work

ExecuTorch, OpenVINO, TensorRT, specialized media backends, zero-copy tensor interchange, device-resident tensors, and richer accelerator-aware routing remain future backend work. They must implement the same capability-oriented architecture rather than expanding the base backend into a god interface.
