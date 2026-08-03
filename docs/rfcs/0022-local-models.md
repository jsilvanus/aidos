# RFC-0022: Local Models

Status: Accepted 2026-08-03

## Abstract

Local Models are AI models that run on the user's machine, enabling offline-first operation and maximal privacy. The Local Models subsystem manages download, caching, quantization, and inference of models stored locally. It prioritizes Hugging Face as the primary model source and supports formats like GGUF, Whisper, and embedding models. Local models are the default choice when available; remote models (RFC-0023) are fallbacks. The Local Models system makes Aidos privacy-respecting and network-independent.

## Motivation

Cloud-based AI services (OpenAI, Anthropic APIs) require internet connectivity and send data to third-party servers. This creates several problems:

1. **No offline support**: Work stops when internet is unavailable.
2. **Privacy concerns**: Project data is transmitted to external services.
3. **Cost**: Every query incurs API charges.
4. **Latency**: Network round-trips add 100ms+ latency.
5. **Vendor dependency**: Project success depends on provider availability and pricing.

Local models solve this by running inference on the user's hardware:

1. **Offline**: No network required.
2. **Private**: Data never leaves the machine.
3. **Free**: No per-query costs (except compute).
4. **Fast**: No network latency.
5. **Independent**: No vendor dependency.

The tradeoff is **resource consumption** (CPU/GPU, disk space, memory). Modern quantized models (int8, int4) make this tradeoff acceptable for many tasks.

## Goals

1. **Define local model lifecycle**: Download, cache, quantize, serve.

2. **Specify model sources**: Where are models obtained?

3. **Establish format support**: GGUF, Whisper, embeddings, etc.

4. **Define model catalog and discovery**: How do users find models?

5. **Clarify quantization strategies**: How are models optimized for local inference?

6. **Explain caching and storage management**: How is disk space managed?

7. **Specify recommendations and defaults**: Which models should users download?

## Non-goals

This RFC does not specify exact inference engines (llama.cpp, onnxruntime, etc.). The architecture is engine-agnostic.

This RFC does not mandate specific quantization techniques. Different methods (int8, int4, NF4) are acceptable.

This RFC does not address model training or fine-tuning. Only pre-trained model serving.

This RFC does not specify the exact UI for model management. That is a separate design.

## Design

### Model Sources

Primary source: **Hugging Face**

Hugging Face is the de facto hub for open-source AI models. Models are:

- **Discoverable**: Searchable and ranked by popularity.
- **Versioned**: Models can be pinned to specific versions.
- **Documented**: Each model has a model card with capabilities and limitations.
- **Licensed**: Clear licensing information.
- **Community-curated**: Popular models are well-tested by community.

Aidos will:

1. Search Hugging Face for models matching a capability (e.g., "embedding").
2. Filter by size, format, quality, license.
3. Download the recommended model.
4. Cache it for future use.

Alternative sources (future):

- **Ollama model library**: Curated collection of models.
- **Custom model repositories**: User-hosted or organizational.
- **Model zoo**: Dataset-specific models.

### Supported Formats

#### GGUF

**GGUF** (Great-Quantum Universal Format) is the de facto standard for quantized LLMs:

- **Compact**: Supports int8, int4, NF4 quantization.
- **Efficient**: Designed for CPU inference.
- **Compatible**: Supported by llama.cpp and other engines.
- **Examples**: Llama, Mistral, Phi models in GGUF format.

Aidos treats GGUF as the primary LLM format.

#### Whisper Models

**Whisper** (by OpenAI, available open-source) for speech-to-text:

- **Multiple sizes**: tiny (39M), base (74M), small (244M), medium (769M), large (1.5B).
- **Multilingual**: Supports 99+ languages.
- **Format**: PyTorch or ONNX.
- **Quantization**: Can be quantized for efficient inference.

#### Embedding Models

**Sentence-transformers** for semantic embeddings:

- **ONNX export**: Efficient inference format.
- **Small models**: Many models < 300MB.
- **Semantic similarity**: Designed for similarity search and clustering.
- **Multilingual**: Multi-language support available.
- **Examples**: BERT-based, MiniLM, MPNet.

#### Vision Models

Future support for:

- **CLIP**: Image-text matching (for semantic understanding).
- **OCR models**: Text extraction from images.
- **ViT**: Vision transformers for image classification.

### Model Catalog and Discovery

Aidos maintains a **model catalog** with curated recommendations:

```
Model Catalog Entry {
  name: String                      # "Mistral 7B"
  model_id: String                  # "mistralai/Mistral-7B-Instruct-v0.1"
  
  type: String                      # "llm", "embedding", "stt"
  format: String                    # "gguf", "pytorch", "onnx"
  
  size: Bytes                       # Disk size after download
  quantizations: Map<String, Bytes> # int8, int4, etc.
  
  capabilities: List<String>        # What it can do
  
  quality_score: Float              # Community rating (0-100)
  speed_score: Float                # Inference speed (relative)
  accuracy_score: Float             # Accuracy on benchmarks
  
  memory_required: Bytes            # RAM to load
  recommended_hardware: String      # "cpu", "gpu", "either"
  
  license: String                   # Model license
  privacy_notes: String?            # Any privacy considerations
  
  hugging_face_id: String           # HF model identifier
  download_url: String
  checksum: String                  # For integrity verification
  
  release_date: Timestamp
  last_updated: Timestamp
  
  tags: List<String>                # "instruction-following", "fast", "lightweight"
}
```

Catalog enables:

```
User request: "Download a fast embedding model"
Catalog filters:
  - type: "embedding"
  - tags: ["fast"]
  - memory_required < 2GB
Returns: [BERT-small, MiniLM, DistilRoBERTa] ranked by speed
User selects: DistilRoBERTa-small
Download initiates
```

### Model Download and Caching

**Download Process:**

```
User requests: Download model X
Engine:
  1. Checks if already cached
  2. If cached: Use it
  3. If not:
     a. Download from HF
     b. Verify checksum
     c. Cache in project model directory
     d. Index in catalog
```

**Caching Strategy:**

- **Project-local cache**: Each project has its own model cache (in project root).
- **Size management**: Warn when cache exceeds limits; offer cleanup.
- **Sharing**: Models can be shared across projects (future: symlinks, deduplication).

Example storage:

```
project/.aidos/models/
  embedding-models/
    distilroberta-small/
      model.safetensors
      tokenizer.json
      config.json
    all-minilm-l6-v2/
      model.safetensors
      tokenizer.json
      
  llm-models/
    mistral-7b-q4/
      model.gguf
      config.json
      
  stt-models/
    whisper-base/
      model.safetensors
      processor.json
```

### Quantization

**Quantization** reduces model size by representing weights with fewer bits:

- **Full precision** (FP32): 4 bytes per weight (original).
- **FP16**: 2 bytes per weight (50% smaller).
- **Int8**: 1 byte per weight (75% smaller, slight accuracy loss).
- **Int4/NF4**: 0.5 bytes per weight (87% smaller, more accuracy loss).

Aidos supports downloading pre-quantized models:

```
Model: Llama-2-7B
Available quantizations:
  - Full (FP32): 26GB (not practical for most machines)
  - FP16: 13GB (requires good GPU)
  - Int8: 7GB (laptop-friendly)
  - Int4: 3.5GB (mobile-friendly)

User download: Int4 version
```

Quantized models are available from Hugging Face (via GGUF format) or can be quantized by Aidos (future).

### Cookbook and Recommendations

Aidos provides a **cookbook** of recommended models for common tasks:

```
# Embedding
Recommended: all-MiniLM-L6-v2 (22M, 80MB)
  - Fast, high-quality embeddings
  - Good for semantic search
  - Multilingual support

Alternative: all-mpnet-base-v2 (110M, 430MB)
  - Higher quality, slower
  - For when accuracy matters

# Speech-to-Text
Recommended: Whisper Base (74M, 290MB)
  - Good balance of speed and accuracy
  - Supports 99 languages

Lightweight: Whisper Tiny (39M, 140MB)
  - For mobile, very fast

# Instruction-Following LLM (Local)
Recommended: Mistral 7B Instruct (Q4, 3.5GB)
  - Fast, locally runnable
  - Reasonable quality for most tasks

Alternative: Llama 2 7B (Q4, 3.5GB)
  - Similar performance
  - Different strengths/weaknesses
```

Recommendations are:

- **Tested**: Community has validated effectiveness.
- **Balanced**: Good tradeoff between quality and resource usage.
- **Documented**: Clear use cases and limitations.
- **Accessible**: Can run on typical developer machines.

### Storage Management

As models accumulate, disk usage grows. Aidos manages storage:

```
Project model cache: 25GB / 100GB limit

Options:
  1. Delete unused models: Save 5GB
  2. Download smaller quantization: Save 8GB
  3. Increase limit: $$$
  4. Move to external storage: USB drive, cloud

Recommendations:
  - Delete mistral-7b-fp32 (13GB): Not used in 2 months
  - Use llama2-7b-q4 (3.5GB): Equivalent quality
  - Keep embeddings: Always needed (small)
```

### Offline-First Priority

Local models are preferred by default:

```
Request: "Embed this text"

Engine logic:
  1. Is embedding model downloaded? YES
     → Use local model
  
  If local unavailable:
  2. Can download embedding model? 
     Disk space available? Network available?
     YES → Download, then use
  
  If cannot download locally:
  3. Fallback to remote provider
     → Ask permission, record fallback
```

This ensures maximum offline capability.

## Data Model (Conceptual)

```
LocalModelRepository {
  project_id: UUID
  
  cached_models: Map<ModelId, CachedModel>
  catalog: ModelCatalog
  download_queue: List<DownloadJob>
  
  configuration: LocalModelConfig {
    cache_directory: Path
    max_cache_size: Bytes
    auto_quantize: Boolean
    preferred_quantizations: List<String>
  }
  
  storage_usage: StorageUsage {
    total_used: Bytes
    by_model: Map<ModelId, Bytes>
    by_type: Map<String, Bytes>
  }
}

CachedModel {
  id: UUID
  model_id: String                  # HF identifier
  
  local_path: Path
  format: String                    # "gguf", "pytorch"
  quantization: String?             # "int4", "int8"
  
  size_bytes: Int
  checksum: String
  
  downloaded_at: Timestamp
  last_used: Timestamp
  access_count: Int
  
  inference_engine: String          # "llama.cpp", "transformers"
}

ModelCatalog {
  entries: Map<String, ModelCatalogEntry>
  last_updated: Timestamp
  version: String
}
```

## Security

Local models have security advantages:

1. **No data transmission**: All inference happens locally.
2. **No authentication required**: Models are files on disk.
3. **No external logging**: Aidos controls logging.
4. **Privacy by design**: No vendor sees project data.

Security risks:

1. **Malicious models**: A compromised model could execute arbitrary code (future mitigation: sandboxing, signing).
2. **Model extraction**: Can users copy models (they can, models are files).

## MVP Scope

The MVP Local Models system includes:

1. **GGUF LLM support**: Download and run quantized LLMs (Mistral, Llama, Phi).
2. **Embedding models**: Download and run sentence-transformers.
3. **Whisper integration**: Speech-to-text (one model size).
4. **Model catalog**: Curated list of recommended models.
5. **Download management**: Download, cache, verify, delete models.
6. **Quantization support**: Allow users to choose quantization levels.
7. **Storage warnings**: Warn when cache is full.

The MVP does not include:

- Quantization performed by Aidos (use pre-quantized from HF).
- Vision models (future).
- Model fine-tuning (future).
- Deduplication across projects (future).

## Future Work

### Cookbook Recommendations

Expand cookbook with use-case-specific models:

```
# Coding Assistance
- Code generation: Recommended model X
- Code review: Recommended model Y

# Content Analysis
- Sentiment: Model A
- Summarization: Model B

# Creative Writing
- Story generation: Model C
```

### Automatic Quantization

Quantize models on-device:

```
User downloads: Mistral 7B (26GB)
Aidos offers: "Quantize to int4? (Would save 22GB)"
User accepts: Quantization runs locally, converts to 3.5GB
```

### Model Performance Profiling

Benchmark models on the user's hardware:

```
User downloads: Embedding model
Aidos profiles: Latency on this GPU
Reports: "This model: 50ms per embedding on your hardware"
User decides: Accept or choose faster/slower alternative
```

### Knowledge Graph of Models

Model relationships and comparisons:

```
Mistral 7B
  Similar to: Llama 2 7B
  Faster than: Llama 2 13B
  Slower than: Phi 2
  Better at: Reasoning
  Better than: Llama 2 on benchmarks
```

### Collaborative Model Curation

Community votes on models:

```
Model: Mistral 7B
Community rating: 4.7/5 (1000 votes)
Use cases: Coding (4.9★), Chat (4.6★), Creative (4.3★)
```

### Model Versioning

Track model versions and updates:

```
Downloaded: Mistral-7B v0.1
Update available: Mistral-7B v0.2 (better quality)
Auto-upgrade: No
Manual upgrade: Download v0.2
```

## Open Questions

- Should Aidos support model training/fine-tuning on local hardware?
- How should model copyright and licensing be handled (what requires attribution)?
- Should there be a "trusted models" concept (models vetted by Aidos team)?
- How should Aidos handle models with safety concerns (automatically flag unsafe models)?
- Should model recommendations be personalized (learn from user's preferences)?
- Should Aidos support quantization as a service (quantize models in background)?
- How should model updates be handled (automatic, manual, semantic versioning)?
