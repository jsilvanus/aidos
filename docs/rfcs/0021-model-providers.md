# RFC-0021: Model Providers

Status: Draft — body not audited against settled decisions (see docs/decisions.md)

## Abstract

Model Providers are abstract interfaces to different sources of AI models. A provider can be a cloud API (Anthropic, OpenAI), a local inference engine (llama.cpp, Ollama), a container service, or any other model source. Providers advertise their capabilities, manage authentication and credentials, handle quotas and policies, and translate generic AI requests into provider-specific operations. The provider abstraction decouples the AI Engine (RFC-0020) from specific vendors, enabling seamless switching and multi-provider composition.

## Motivation

Different sources of AI models have different interfaces, policies, and characteristics:

- **OpenAI API**: REST endpoints, token-based billing, rate limits, no offline support.
- **Anthropic Claude API**: Different REST API, different models, different token pricing.
- **Llama.cpp**: Local inference, offline, no authentication, single-machine.
- **Ollama**: Local inference, simple API, multiple models, pull/run model.
- **Hugging Face**: Download models for local inference, permission system.
- **Azure OpenAI**: OpenAI API but hosted on Azure, enterprise features.
- **LM Studio**: Desktop app with API, local inference, simple UI.
- **Groq**: API with fast inference, token-based, specific models.

Without abstraction, the runtime would need to:

1. Know about every provider's API.
2. Handle authentication for each.
3. Manage different pricing models.
4. Understand provider-specific concepts (tokens, rate limits, quotas).
5. Change code every time a new provider emerges.

The Provider abstraction solves this by defining a standard interface that all providers implement. The AI Engine uses this interface; providers vary beneath it.

## Goals

1. **Define provider interface**: What methods must a provider implement?

2. **Specify capability advertisement**: How do providers declare what models they offer?

3. **Establish authentication model**: How are credentials managed?

4. **Define quota and policy enforcement**: How are rate limits, costs, and policies enforced?

5. **Clarify privacy and data handling**: How do providers handle project data?

6. **Establish provider discovery**: How does Aidos find and load providers?

7. **Specify versioning and compatibility**: How do providers evolve?

## Non-goals

This RFC does not specify exact authentication mechanisms (OAuth, API keys, etc.). That is provider-specific.

This RFC does not define the pricing model for cloud providers. That is external.

This RFC does not mandate a specific protocol for provider communication. REST, gRPC, local libraries are all acceptable.

This RFC does not address provider licensing or legal terms. Those are external agreements.

## Design

### Provider Interface

All providers implement a standard interface:

```
Provider {
  id: String                        # "anthropic", "openai", "ollama", etc.
  name: String                      # Human-readable name
  version: String                   # Provider version (e.g., "0.1.0")
  
  /// List models available from this provider
  list_models() -> List<ModelDescriptor>
  
  /// Indicate what capabilities this model has
  get_model_info(model_id: String) -> ModelInfo
  
  /// Download a model (if applicable)
  download_model(model_id: String, progress_cb: Callback?) -> Path
  
  /// Query a model
  query(
    model_id: String,
    request: QueryRequest,
    session_id: UUID
  ) -> QueryResult
  
  /// Stream responses (for long-running queries)
  stream_query(
    model_id: String,
    request: QueryRequest,
    session_id: UUID,
    callback: StreamCallback
  ) -> void
  
  /// Check quota and rate limits
  check_quota(session_id: UUID) -> QuotaStatus
  
  /// Get resource usage for billing
  get_usage(session_id: UUID, time_range: Range) -> UsageReport
}

ModelDescriptor {
  id: String                        # Model identifier
  name: String
  type: String                      # "llm", "embedding", "vision", etc.
  description: String?
  
  properties: Map<String, Any> {
    context_window: Int?
    max_output_tokens: Int?
    supported_languages: List<String>?
    offline_capable: Boolean
    requires_authentication: Boolean
    estimated_cost_per_1m_tokens: Float?
  }
  
  capabilities: List<Capability>    # What this model can do
}

Capability {
  type: String                      # "text_generation", "embedding", "vision"
  config: Map<String, Any>          # Type-specific configuration
}

QueryRequest {
  model_id: String
  input: Any                        # Type depends on model
  parameters: Map<String, Any>      # Temperature, max_tokens, etc.
  system_prompt: String?            # For LLMs
  user_context: String?             # For relevance
}

QueryResult {
  output: Any
  usage: UsageInfo {
    input_tokens: Int?
    output_tokens: Int?
    compute_time_ms: Float
  }
  metadata: Map<String, Any>
}

QuotaStatus {
  remaining_tokens: Int?
  remaining_requests: Int?
  reset_at: Timestamp?
  is_exceeded: Boolean
}

UsageReport {
  queries: Int
  tokens_used: Int
  estimated_cost: Money
  time_period: Range
}
```

Providers implement only the methods relevant to them:

- **Local providers** (Ollama, llama.cpp): Implement `query`, skip authentication and quota methods.
- **Cloud providers** (OpenAI, Anthropic): Implement all methods.
- **Hugging Face**: Implement `list_models`, `download_model`, `query`.

### Capability Advertisement

Providers advertise capabilities in a structured way:

```
Provider: Anthropic
  Model: claude-opus
    Capabilities:
      - text_generation { max_tokens: 4096, context_window: 200k }
      - vision { formats: ["jpeg", "png", "gif"] }
      - tool_use
      
  Model: claude-haiku
    Capabilities:
      - text_generation { max_tokens: 4096, context_window: 200k }
      - tool_use

Provider: Ollama
  Model: mistral:7b
    Capabilities:
      - text_generation { max_tokens: 8192 }
    Properties:
      - offline_capable: true
      - requires_authentication: false

Provider: OpenAI
  Model: gpt-4v
    Capabilities:
      - text_generation { max_tokens: 4096 }
      - vision { formats: ["jpeg", "png", "webp"] }
```

The AI Engine uses these advertisements to route requests:

```
Request: "Embed this text"
AI Engine queries all providers
Anthropic: No embedding capability
OpenAI: embedding { model: "text-embedding-3-small" }
Ollama: embedding available (if local model loaded)
Selection: Local embedding if available, else OpenAI
```

### Authentication and Credentials

Providers manage authentication differently:

**API Key-based (OpenAI, Anthropic, Groq):**

```
Provider stores: API key in encrypted config
Provider uses: Key to authenticate requests
Session: Invisible to session (provider handles internally)
```

**No authentication (Local providers):**

```
Ollama, llama.cpp: No credentials needed
```

**OAuth (future for enterprise):**

```
Provider redirects: User logs in via provider's OAuth
Provider stores: Refresh token securely
Provider uses: Token for API requests
```

Credentials are stored in project configuration (RFC-0010), encrypted at rest. The provider framework ensures credentials are never logged or exposed.

### Quota and Policy Enforcement

Providers enforce quotas and policies:

```
Query arrives at provider
Provider checks quota: tokens_used_today < quota
Provider checks rate: requests_per_minute < limit
If exceeded: Return QuotaExceeded error
If within limit: Process query
Track usage: Increment token count
```

The AI Engine can be configured to:

- **Strict enforcement**: Fail if quota exceeded.
- **Graceful degradation**: Switch to alternative provider or local model.
- **Queuing**: Queue requests until quota resets.

Example policy:

```
Project configuration:
  anthropic_provider:
    quota: 1_000_000 tokens/month
    on_quota_exceeded: "use_openai_fallback"
    
  openai_provider:
    quota: 500_000 tokens/month
    rate_limit: 100 requests/minute
    on_quota_exceeded: "fail"
```

### Privacy and Data Handling

Providers have different privacy models:

**Data Retention:**

- **OpenAI**: Retains input data by default (can be disabled).
- **Anthropic**: Does not retain input data.
- **Ollama**: No data leaves the machine.

**Data Redaction (Future):**

Sessions can request sensitive data be redacted before sending to remote providers:

```
Session has: Project source code with secrets
Request to OpenAI: Redact API keys, credentials
OpenAI sees: Code without secrets
```

**Summarization (Future):**

Large contexts can be summarized before sending:

```
Project has: 10MB of documentation
Request to expensive LLM: Summarize to 1MB
LLM processes: Summary (cost reduced)
```

### Provider Versioning and Compatibility

Providers version independently:

```
Provider version: 1.0.0 → Model "gpt-4" available
Provider version: 1.1.0 → Model "gpt-4-turbo" added
Provider version: 2.0.0 → Model "gpt-4" deprecated (breaking change)
```

The runtime can:

- Pin to specific provider versions.
- Support multiple versions in parallel.
- Migrate gradually to new versions.

Compatibility is managed per model:

```
Aidos minimum provider version: 1.0
Provider version 1.0: Supports models A, B, C
Provider version 1.1: Supports models A, B, C, D (backward compatible)
Provider version 2.0: Supports models B, D, E (breaks support for A, C)

If project uses model A:
  Require provider version 1.x
```

### Built-in Providers

MVP includes providers for:

1. **Anthropic**: Claude models via API.
2. **Ollama** (or equivalent): Local models (Mistral, Llama, etc.).
3. **Hugging Face**: Download local models.

Future providers:

- OpenAI (GPT models).
- Google (Gemini).
- OpenRouter (multi-model aggregator).
- LM Studio (desktop local inference).
- Azure OpenAI (enterprise).
- Groq (fast inference).
- Custom providers (via plugin system, RFC-0060).

## Data Model (Conceptual)

```
Provider {
  id: String
  name: String
  version: String
  implementation: ProviderImplementation
  
  configuration: ProviderConfig {
    authentication: AuthConfig?
    endpoint: String?              # For remote providers
    credentials_encrypted: Boolean
    
    features: Map<String, Boolean> {
      supports_streaming: Boolean
      supports_batch: Boolean
      supports_quota_tracking: Boolean
    }
  }
  
  models: Map<ModelId, ModelDescriptor>
  
  status: ProviderStatus           # "initialized", "ready", "error"
  last_sync: Timestamp
}

ProviderConfig {
  id: String
  provider_id: String
  
  api_key: String?                 # Encrypted
  oauth_token: String?             # Encrypted
  endpoint_url: String?
  
  quota_config: QuotaConfig {
    monthly_token_limit: Int?
    daily_request_limit: Int?
    monthly_budget: Money?
  }
  
  privacy_settings: PrivacySettings {
    allow_data_retention: Boolean
    redact_sensitive_fields: List<String>
    summarize_large_contexts: Boolean
  }
}
```

## Security

Providers enforce security through:

1. **Credential encryption**: API keys are encrypted at rest and never logged.
2. **Quota enforcement**: Prevent runaway usage or cost overruns.
3. **Privacy settings**: Respect user preferences for data handling.
4. **Audit logging**: Track all provider queries for auditing (RFC-0003).

## MVP Scope

The MVP provider system includes:

1. **Anthropic provider**: Claude API integration.
2. **Ollama provider**: Local model support.
3. **Provider interface**: Standard abstraction.
4. **Credential management**: Encrypt and store API keys.
5. **Quota tracking**: Monitor usage against limits.
6. **Basic fallback**: If primary provider unavailable, try alternative.

The MVP does not include:

- OAuth authentication (future).
- Multi-provider failover (future).
- Data redaction (future).
- Custom provider plugins (future).
- Real-time quota monitoring (future).

## Future Work

### Provider Plugins

Allow custom providers via the plugin system (RFC-0060):

```
User implements: ProviderPlugin interface
Registers: "my-custom-provider"
Aidos loads: Provider at runtime
Models from custom provider available alongside built-in
```

### Provider Marketplace

Curated list of verified, community-built providers:

```
Aidos marketplace
- Provider X (4.8★, 1000 downloads)
- Provider Y (4.5★, 500 downloads)
Install provider → Download and integrate
```

### Smart Provider Selection

AI learns which provider is best for each task:

```
Query type: "summarization"
Provider A (claude): 90% accuracy, $0.10, 2s latency
Provider B (gpt): 92% accuracy, $0.15, 3s latency
Provider C (local): 85% accuracy, $0.00, 0.5s latency

Decision criteria: speed + cost
Selection: Provider C (acceptable accuracy, fastest, free)
```

### Provider Composition

Combine multiple providers in a single query:

```
Query: "Fact-check this claim"
1. OpenRouter (aggregates multiple LLMs) → draft fact-check
2. Local model (verify offline) → confirm facts
3. Groq (fast) → summarize
Result: Consensus from multiple providers
```

### Usage Analytics

Track provider usage and recommend optimizations:

```
Monthly report:
- Anthropic: 50% queries, $100 cost
- OpenAI: 30% queries, $150 cost
- Ollama: 20% queries, $0 cost

Recommendation: Switch more queries to Ollama to reduce costs
```

## Open Questions

- Should providers have "trust levels" (verified, community, experimental)?
- How should the runtime handle provider API changes (model deprecation, breaking changes)?
- Should providers support batch operations (process multiple queries efficiently)?
- How should the runtime handle provider outages (fallback strategies, retry logic)?
- Should there be a "provider agreement" users sign (privacy policy, terms)?
- How should pricing be handled for hybrid cloud/local setups?
- Should providers expose their model training data and biases?
