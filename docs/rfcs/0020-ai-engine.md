# RFC-0020: AI Engine

Status: Accepted 2026-08-03

## Abstract

The AI Engine is the runtime subsystem that manages all forms of artificial intelligence capabilities. It is model-agnostic and provider-agnostic, supporting not only Large Language Models (LLMs) but also embeddings, speech-to-text (STT), text-to-speech (TTS), vision, optical character recognition (OCR), reranking, translation, and future modalities. The AI Engine handles discovery, download, loading, lifecycle management, resource accounting, and routing of queries to appropriate models. Sessions can use multiple models simultaneously and switch models dynamically, enabling flexible AI pipelines.

## Motivation

Existing "AI" systems conflate "AI" with "LLMs from specific vendors." This creates several problems:

1. **Vendor lock-in**: Switching from OpenAI to Anthropic to local models requires architectural changes.
2. **Limited scope**: LLMs alone cannot solve all problems. Understanding images (vision), summarizing documents (embedding-based), converting speech to text (STT), and other tasks require different model types.
3. **Monolithic reasoning**: A single LLM doing everything leads to inefficiency and wasted compute.
4. **Offline dependency**: Cloud LLMs require network. Local models enable offline AI.
5. **Inflexibility**: Real-world workflows benefit from pipelines (STT → understanding → planning → execution) rather than single-model queries.

The AI Engine solves this by treating "AI" as a set of capabilities, not a single service. It manages:

- **Capability diversity**: LLMs, embeddings, speech, vision, and future modalities coexist.
- **Provider diversity**: Claude, GPT, Gemini, Ollama, local GGUF models, and custom providers are all first-class.
- **Dynamic routing**: Sessions request capabilities (not specific models), and the engine routes to the best available model.
- **Offline-first**: Local models are primary; remote models are optional enhancements.
- **Resource management**: The engine tracks compute, memory, and quota usage.

The architecture is inspired by:

- **Operating system device drivers**: Hardware abstraction (filesystem, network, etc.) via standard interfaces.
- **Kubernetes**: Abstract workloads from execution infrastructure.
- **Microservices**: Separate concerns (LLM reasoning, embedding search, speech processing).
- **Plugin systems**: Extensibility without core changes.

## Goals

1. **Define AI capability taxonomy**: What model types does Aidos support?

2. **Establish provider abstraction**: How do sessions query AI without knowing which provider is used?

3. **Specify lifecycle management**: How are models discovered, downloaded, loaded, and unloaded?

4. **Define capability negotiation**: How does the engine match requests to models?

5. **Clarify resource management**: How are compute, memory, and quota tracked?

6. **Explain pipelines**: How can sessions compose multiple AI operations?

7. **Establish multimodal support**: How do vision, speech, and text coexist?

## Non-goals

This RFC does not specify exact model formats, quantizations, or compression schemes. Those are implementation details.

This RFC does not mandate specific inference engines (llama.cpp, vLLM, etc.). The architecture is engine-agnostic.

This RFC does not address training or fine-tuning. The AI Engine consumes pre-trained models.

This RFC does not specify the user-facing API for querying the AI Engine. That is a separate design.

This RFC does not address security beyond capability-based access control. Authentication and encryption are separate concerns.

## Design

### AI Capability Taxonomy

The AI Engine manages the following capability classes:

#### Large Language Models (LLMs)

General-purpose reasoning and text generation. Capabilities:

- **Text completion**: Generate text given a prompt.
- **Instruction following**: Follow user instructions.
- **In-context learning**: Learn from examples in the prompt.
- **Tool use**: Understand and invoke external tools.
- **Multi-turn conversation**: Maintain context across exchanges.

Examples: Claude, GPT-4, Llama, Gemini.

#### Embeddings

Convert text or documents into vector representations for semantic search and similarity.

- **Text embedding**: Embed a sentence or paragraph.
- **Document embedding**: Embed a full document.
- **Cross-modal embedding**: Match text and images in shared space (future).

Examples: sentence-transformers, OpenAI embeddings, Nomic embeddings.

#### Speech-to-Text (STT)

Convert audio to text.

- **Transcription**: Convert speech to text with confidence scores.
- **Speaker diarization**: Identify who spoke (future).
- **Language detection**: Identify language (future).

Examples: Whisper, Google Speech-to-Text.

#### Text-to-Speech (TTS)

Convert text to audio.

- **Speech generation**: Generate natural-sounding speech.
- **Voice cloning**: Generate speech in specific voice (future).
- **Emotion synthesis**: Add emotional tone (future).

Examples: TTS engines, Piper, Eleven Labs.

#### Vision

Understand images and video.

- **Image classification**: Identify objects in images.
- **Object detection**: Locate and label objects.
- **OCR**: Extract text from images.
- **Visual understanding**: Answer questions about images.

Examples: GPT-4V, Claude Vision, CLIP.

#### Optical Character Recognition (OCR)

Extract text from documents and images.

- **Document OCR**: Convert scanned documents to text.
- **Handwriting recognition**: Recognize handwritten text (future).
- **Layout preservation**: Maintain document structure (future).

Examples: Tesseract, PaddleOCR.

#### Reranking

Re-rank search results or candidate lists by relevance.

- **Query-document reranking**: Improve search result relevance.
- **Passage reranking**: Score passage relevance (for RAG).

Examples: Cross-encoder models, Cohere rerank.

#### Translation

Translate text between languages.

- **Machine translation**: Translate documents.
- **Multilingual understanding**: Understand text in any language.

Examples: MarianMT, Noto, NLLB.

#### Future Modalities

The architecture should support:

- **Audio classification**: Identify sounds and events.
- **Music generation**: Generate music or audio.
- **3D generation**: Generate 3D models.
- **Symbolic reasoning**: Formal logic, constraint solving.
- **Custom modalities**: Domain-specific AI (forecasting, anomaly detection, etc.).

### Provider Abstraction

Sessions request a **model kind** and let the router select. (The word "capability" is reserved
for security grants — RFC-0018. Model classes are `ModelKind`.)

```
Session requests: ModelKind.EMBEDDING
Router evaluates candidates against the routing policy
Routes to: local sentence-transformers
```

### Routing is user-owned policy, not an engine heuristic

Model selection decides whether the user's code leaves the device. That is a privacy decision
and it belongs to the user, expressed as declared policy:

```toml
[routing.llm]
order          = ["local", "remote"]
remote_egress  = "ask"        # "never" | "ask" | "always"
max_cost_units = 5000
```

`remote_egress = "ask"` is the default. An earlier design had the engine "prefer local, fall
back to remote if unavailable" as built-in behaviour — silent egress by fallback, in a product
whose first principle is offline-first. Falling back across the network boundary is never
automatic unless the user has said `always`.

### Degradation states

Every request resolves to one of five outcomes, and the state is surfaced rather than hidden:

| State | Meaning |
|---|---|
| `LOCAL` | satisfied by a local model |
| `REMOTE_APPROVED` | satisfied remotely, egress permitted by policy or approval |
| `REMOTE_PENDING_APPROVAL` | Run parked awaiting user approval to send |
| `UNAVAILABLE_OFFLINE` | no local model satisfies it, and there is no network |
| `DISABLED_BY_POLICY` | routing policy or taint (RFC-0027) forbids the only viable route |

`UNAVAILABLE_OFFLINE` is a first-class, expected state on MOBILE, not an error. The user is
told which model kind is missing and offered the download.

### Selection inputs

Routing considers: model kind; platform profile and available memory (RFC-0049); network
availability; the routing policy; remaining budget (RFC-0028); Run taint (RFC-0027); and
**context length** — a candidate that cannot fit the assembled prompt is not a candidate.

Model selection therefore happens **before** prompt assembly, because the token budget derives
from the selected model's context window (RFC-0025). Assembly may report that the prompt cannot
fit, which returns to the router for a larger-context candidate; this is a bounded two-phase
negotiation, not a loop.

### Pinning and reproducibility

Provider and model version are recorded on every Attempt (RFC-0019). Dynamic routing and
auditability coexist because the audit records what was actually used, not what policy would
select today. A session may pin a specific model when reproducibility matters more than
availability.

Providers abstract:

- **Where the model runs** (local, remote, cloud).
- **Model download and caching** (where is it stored, when to update).
- **Authentication** (API keys, credentials).
- **Usage policies** (rate limits, quotas, privacy policies).

### Lifecycle Management

Models progress through states:

```
Discovered → Downloaded → Loaded → Active → Unloaded → Discarded
```

#### Discovery

The AI Engine discovers available models:

1. **Built-in models**: Models shipped with Aidos (e.g., small Whisper model for offline STT).
2. **Provider registries**: Models available from providers (Hugging Face, OpenAI API, etc.).
3. **Local models**: Models the user has downloaded manually.
4. **Project-specific models**: Models configured for a specific project.

Discovery yields a catalog of available models with metadata (name, type, size, capabilities, privacy, cost).

#### Download

Models are downloaded explicitly (never automatically):

```
User requests: "Download claude-embedding-small"
Engine downloads from Hugging Face / provider
Stores in project cache
Verifies integrity (hash)
```

Downloads may be pausable/resumable. Large models (e.g., 7B parameters) can be quantized (reduced size) for storage.

#### Loading

A model is loaded into memory before use:

```
Session requests: "Embed this text"
If model not in memory:
  Load model from disk
  Allocate GPU/CPU resources
  Initialize inference engine
Once loaded:
  Route request to model
  Cache stays loaded (until unloading)
```

Loading is resource-intensive. The engine manages memory and decides when to unload models.

#### Active Use

A loaded model is used to answer queries. The engine:

- Routes requests to the model.
- Collects metrics (latency, tokens, cost).
- Monitors resource usage.
- Handles errors and retries.

#### Unloading

Models are unloaded to free memory:

```
Trigger: No requests for T seconds, or memory pressure
Action: Unload from memory (keep on disk)
Effect: Next use requires reload (slight delay)
```

Unloading is a tradeoff between memory and latency. Small models might stay loaded; large models are unloaded aggressively.

#### Discarding

Models can be deleted from disk to free storage:

```
User requests: "Delete model X"
Model is unloaded (if loaded)
Model files are deleted
Requires re-download if needed later
```

### Capability Negotiation

Sessions request capabilities, not specific models. The engine negotiates the best model:

```
Request: {
  capability: "embed_text",
  query: "Describe this concept",
  preferred_properties: { offline: true, latency_budget: 100ms }
}

Engine evaluates:
  - Local embedding model: offline=true, latency=50ms ✓ (best match)
  - Remote API: offline=false (fails requirement)
  
Route to: Local model
```

Capability negotiation considers:

- **Functional requirements**: Must be offline, must support language X.
- **Performance requirements**: Latency budget, throughput, accuracy.
- **Resource constraints**: Available memory, compute.
- **Cost**: Token usage, API costs.
- **Privacy**: Can query remote provider?
- **Quality**: Accuracy of model on this task.

### Resource Accounting

The AI Engine tracks resource usage:

```
ModelResourceUsage {
  model_id: String
  
  memory: Bytes              # RAM usage
  compute: CPUSeconds        # CPU time
  tokens: Int                # Tokens processed (for LLMs)
  latency: Duration          # Per-query latency
  
  quota_used: Percentage     # Provider quota (if applicable)
}
```

Sessions are aware of their resource usage (for decision-making):

```
Session queries AI: "Generate a comprehensive report"
AI returns: Result, plus metadata {
  model: "claude-opus",
  tokens_used: 5000,
  estimated_cost: $0.05,
  latency: 2.3s
}
Session decides: Accept result or request shorter version
```

### AI Pipelines

Sessions can compose multiple AI operations into pipelines:

#### Example 1: Voice-to-Action

```
User speaks → [STT model] → Text
Text → [LLM] → Intent, plan
Plan → [LLM] → Function calls
Function calls → [Tool Broker] → Execution
Execution result → [LLM] → Response
Response → [TTS model] → Audio
Audio → [Play on device]
```

Each stage uses the most appropriate model. The pipeline is coordinated by the session.

#### Example 2: Semantic Search

```
Query → [Embedding model] → Query vector
Repository → [Embedding model] → Document vectors (cached)
Vectors → [Similarity search] → Top K documents
Top K → [Reranking model] → Reranked documents
Reranked → [LLM] → Final answer (based on top results)
```

This pipeline improves search quality by combining embeddings and LLM reasoning.

#### Example 3: Multimodal Analysis

```
Image → [Vision model] → Description, objects, text
Text from OCR → [Embedding model] → Semantic representation
Semantics → [Knowledge Engine] → Related documents
Related → [LLM] → Analysis and insights
```

Pipelines are first-class: sessions don't just query individual models, they orchestrate workflows.

### Multimodal Support

The AI Engine supports requests that span modalities:

```
Session requests: "Analyze this screenshot and suggest improvements"

Engine orchestrates:
  Screenshot → [Vision model] → Detected elements, text
  Detected elements → [LLM] → Analysis and suggestions
  Suggestions + screenshot → [LLM] → Detailed report
  Report → [TTS] → Narration (optional)
```

Multimodal support requires:

- **Format conversion**: Convert between image, text, audio.
- **Cross-modal reasoning**: Combine understanding from multiple models.
- **Latency management**: Pipelines may be slow; expose latency to sessions.

## Data Model (Conceptual)

The AI runtime splits into two components at different scopes. Modelling it as one per-project
object was wrong on both axes: weights are device resources, and routing is a per-request
decision.

```
ModelRuntime {                           # USER SCOPE — one per device (RFC-0054)
  catalog: Map<ModelId, ModelDescriptor>       # what exists and could be obtained
  installed: Map<ModelId, InstalledModel>      # what is on disk, content-addressed by digest
  loaded: Map<ModelId, LoadedModel>            # what is in memory right now

  weights_dir: DirectoryRef              # ~/.aidos/models — shared by every project
  memory_budget: Bytes                   # device-wide, not per project
  admission_queue: Queue<LoadRequest>    # loading is globally serialized
}

InferenceRouter {                        # PER REQUEST
  select(request: ModelCapabilityRequest, context: RoutingContext): RoutingDecision
}

RoutingContext {
  profile: PlatformProfile               # RFC-0049
  networkAvailable: Boolean
  policy: RoutingPolicy                  # user-owned, see below
  budgetRemaining: Budget                # RFC-0028
  runTaint: TrustLevel                   # RFC-0027
}
```

Model weights are multi-gigabyte and one loaded instance can saturate a phone's memory.
Loading is therefore a **globally serialized, device-wide** operation with an admission queue,
and two projects using the same model share one download and one loaded instance. A per-project
`model_cache` and `memory_budget` would have downloaded the same weights once per project and
attempted concurrent loads against a single pool of RAM.

Usage accounting is persisted in the budget ledger (RFC-0028), not held in an in-memory
`Map<SessionId, UsageMetrics>` that vanishes on eviction — which, on Android, is constantly.

ModelDescriptor {
  id: UUID
  name: String
  type: CapabilityType                   # "llm", "embedding", "stt", etc.
  
  provider: ProviderId
  remote_url: String?                    # Where to download
  local_path: String?                    # Where it's cached
  
  properties: Map<String, Any> {
    size_bytes: Int
    quantization: String?                # "int8", "int4", etc.
    supported_languages: List<String>?
    offline_capable: Boolean
    privacy_policy: String?
  }
}

LoadedModel {
  descriptor: ModelDescriptor
  loaded_at: Timestamp
  memory_usage: Bytes
  access_count: Int
  last_used: Timestamp
}

UsageMetrics {
  session_id: UUID
  
  queries: Int                           # Number of queries
  tokens: Int                            # Tokens used (if applicable)
  compute_time: Duration
  memory_peak: Bytes
  cost: Currency?
}
```

## Security

The AI Engine enforces security through capabilities (RFC-0003):

- **Model access**: Sessions must have permission to use specific models.
- **Privacy**: Remote model usage is controlled by privacy settings.
- **Data redaction**: Sensitive data can be redacted before sending to remote providers.
- **Usage accounting**: Costs and quotas are tracked per session.

## MVP Scope

The MVP AI Engine includes:

1. **LLM support**: At least one remote (Claude API) and one local (Ollama or similar) LLM.
2. **Embeddings**: Local embedding model (sentence-transformers).
3. **Capability routing**: Route requests to appropriate models.
4. **Lifecycle management**: Download, load, unload models.
5. **Resource accounting**: Track token usage and costs.
6. **Simple pipelines**: Support sequential model composition.

The MVP does not include:

- Speech models (STT/TTS) (future).
- Vision models (future, beyond API-based only).
- Advanced pipeline composition (future).
- Model fine-tuning or training (future).
- Multi-provider failover (future).

## Future Work

### Adaptive Routing

AI Engine learns from usage patterns:

```
Query type: "summarization"
Historically fast models: Model A (5s), Model B (8s)
Current resources: Model A loaded, Model B not
Decision: Use Model A
```

### Cost Optimization

Automatically select cheaper models when accuracy is sufficient:

```
Request: "Classify sentiment"
Available: claude-opus ($0.05), claude-haiku ($0.001)
Accuracy need: "95%"
Both achieve 96%
Selection: claude-haiku (cost optimization)
```

### Speculative Decoding

Use smaller models for drafting, larger models for refinement:

```
Draft with claude-haiku (fast, cheap)
Evaluate quality with claude-opus (slower, expensive)
Iterate only if necessary
```

### Ensemble Models

Combine multiple models for improved accuracy:

```
Query embedding models: 3 different models
Average results
Higher confidence via ensemble
```

### Model Distillation

Create smaller models from larger ones:

```
Claude-opus teaches claude-haiku on specific tasks
Distilled model: smaller, faster, specialized
```

### Voice-First AI

Full voice workflow with natural conversation:

```
User speaks → STT → Understanding → Action → Response → TTS
Multi-turn conversation with speech
```

### Real-Time Reasoning

Stream model outputs (tokens, intermediate reasoning) to sessions:

```
LLM generates tokens → Stream to session in real-time
Session sees reasoning unfold
Interactive refinement possible
```

## Open Questions

- Should the AI Engine support model ensembles (multiple models voting on answer)?
- How should the engine handle model failures gracefully (fallback to alternative)?
- Should there be a "trusted models" concept (models that have been validated for this project)?
- How should the engine handle models that are computationally expensive (warn before use)?
- Should the engine support batching multiple requests to the same model for efficiency?
- How should inference caching work (cache model outputs for identical inputs)?
- Should sessions be able to specify model preferences (faster but less accurate vs. slower but more accurate)?
