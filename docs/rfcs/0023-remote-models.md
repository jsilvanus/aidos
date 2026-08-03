# RFC-0023: Remote Models

Status: Accepted 2026-08-03

## Abstract

Remote Models are AI services accessed via network APIs (OpenAI, Anthropic, Google, etc.). They are optional enhancements to Aidos; projects function fully offline with local models (RFC-0022). Remote models are used when they provide superior quality, capabilities not available locally, or when the user explicitly chooses them. Privacy and usage policies determine whether project data can be sent to remote services. Remote model queries are logged and auditable.

## Motivation

Local models (RFC-0022) have limitations:

- **Capability gaps**: Not all model types have good local options (e.g., vision, speech synthesis).
- **Quality tradeoffs**: Smaller local models may be less accurate than cloud models.
- **Latency**: Some users accept network latency for better results.
- **Specialized tasks**: Some tasks benefit from specialized models only available remotely (e.g., code generation on specific stacks).

Remote models complement local models:

1. **Fallback**: When local model is unavailable or insufficient.
2. **Optional enhancement**: User can choose high-quality cloud model if they want.
3. **Capability expansion**: Access models not suitable for local deployment.
4. **Hybrid pipelines**: Combine local and remote in pipelines (e.g., local embedding, remote LLM).

Key principle: **Remote is optional, not required.** Projects work offline; remote is a choice.

## Goals

1. **Define remote model architecture**: How are remote models accessed?

2. **Establish privacy and approval policies**: When can data be sent to remote services?

3. **Specify redaction and summarization**: How is sensitive data protected?

4. **Clarify routing and fallback**: How does the engine choose remote vs. local?

5. **Define usage tracking and auditing**: How are remote queries logged?

6. **Explain future enterprise support**: How would Aidos support enterprise providers?

## Non-goals

This RFC does not mandate specific cloud providers. Anthropic, OpenAI, Google are examples.

This RFC does not specify exact privacy policies (those are external, user-configurable).

This RFC does not define pricing or billing models. Those are provider-specific.

This RFC does not address real-time collaboration via remote services. Single-user is the design assumption.

## Design

### Remote Model Access Pattern

Sessions access remote models through the AI Engine (RFC-0020), which routes through the appropriate Model Provider (RFC-0021):

```
Session requests: "Generate a summary" (request doesn't specify provider)

AI Engine:
  1. Check capabilities needed: "summarization"
  2. Check local models: None available or prefer remote
  3. Check privacy settings: Can send data to remote?
  4. Route to remote provider (OpenAI, Anthropic, etc.)
  5. Execute query
  6. Log usage and cost
  7. Return result to session
```

Remote models are transparent to sessions: they request capabilities, not specific providers.

### Privacy and Approval

**Critical constraint**: Project data is sensitive. Before sending to remote services, user approval is required.

**Privacy Policy Dialog:**

```
Session wants to query OpenAI API for analysis

Approval dialog:
  "This will send project data to OpenAI's servers.
   - OpenAI retains data for 30 days by default.
   - Data is used for improving OpenAI's services.
   - Your data is not isolated.
   
   Options:
   [Approve once] [Always approve] [Deny] [Learn more]"

User choice: "Always approve for analysis tasks"
```

**Privacy Settings (Project Configuration):**

```
project/.aidos/config.json:
  
  remote_model_policies: {
    "anthropic": {
      enabled: true,
      data_retention: "no_retention",   // Anthropic doesn't retain
      approved_tasks: ["summarization", "analysis"],
      denied_tasks: ["sensitive_review"]
    },
    "openai": {
      enabled: false                     // Disabled by default
    }
  }
```

**Automatic Redaction:**

Before sending project data to a remote service, Aidos can redact sensitive information:

```
Project code contains:
  API_KEY = "sk_prod_abc123..."
  password = "..."
  secret_data = {...}

Before sending to remote model:
  Redact API keys
  Redact passwords
  Redact variables marked "secret"
  
Remote model sees: Code without secrets
```

**Summarization:**

For large projects, Aidos can summarize before sending:

```
Project documentation: 10MB
Query: "Summarize the architecture"

Options:
  1. Send full 10MB (slow, expensive, unnecessary detail)
  2. Summarize to 500KB (cheaper, faster, sufficient for summary task)

Aidos chooses: Option 2
```

### Routing and Fallback

The AI Engine makes routing decisions:

```
Request: "Summarize this research paper"

Step 1: Check privacy policy
  → Remote models allowed for "summarization"? YES

Step 2: Check available models
  → Local: None (no local summarization model)
  → Remote: Anthropic (approved), OpenAI (disabled)

Step 3: Select provider
  → Use Anthropic (first approved option)

Step 4: Execute and log
  → Query Anthropic API
  → Receive result
  → Log: timestamp, model, tokens, cost

Step 5: Return to session
  → Result + metadata (cost, latency)
```

**Fallback Chain (Future):**

```
Try preferred remote provider
  → If rate-limited: Try secondary provider
  → If both unavailable: Fall back to local model (degraded)
  → If no local: Fail with clear error to user
```

### Usage Tracking and Auditing

Every remote query is logged:

```
RemoteQueryLog {
  session_id: UUID
  timestamp: Timestamp
  
  provider: String                  # "anthropic", "openai"
  model: String
  
  task: String                      # User-specified task
  redaction_applied: Boolean
  summarization_applied: Boolean
  
  input_tokens: Int
  output_tokens: Int
  estimated_cost: Currency
  
  result_hash: String               # Hash of result (no logging content)
}
```

Logs enable:

- **Cost tracking**: How much is this project costing?
- **Audit**: What data was sent to which provider when?
- **Privacy verification**: Was redaction applied?
- **Usage analysis**: Which providers are used most?

### Enterprise Providers (Future)

For enterprise, Aidos could support:

- **Self-hosted models**: Organization-internal inference servers.
- **VPC endpoints**: Private network access to cloud providers.
- **Data residency**: Models only use data in specific region.
- **Audit logging**: Comprehensive logging of all model queries.
- **Compliance**: HIPAA, GDPR, SOC2 support.

Example configuration (future):

```
enterprise_provider: {
  name: "OpenAI-Enterprise",
  endpoint: "https://enterprise-internal.company.com/api",
  data_residency: "us-east-1",
  data_retention: "no_retention",
  audit_logging: true
}
```

### Rate Limiting and Quotas

Remote providers enforce quotas:

```
Anthropic quota: 1M tokens/month
OpenAI quota: 500K tokens/month

Session creates artifact that costs 100K tokens
Remaining budget: 900K (Anthropic)

If query would exceed quota:
  → Warn user: "Exceeds budget"
  → Offer options: Wait for reset, pay overage, use local
  → Let user decide
```

## Data Model (Conceptual)

```
RemoteModelConfiguration {
  project_id: UUID
  
  providers: Map<ProviderId, RemoteProviderConfig>
  
  privacy_policies: PrivacyPolicies {
    approved_providers: List<ProviderId>
    allowed_tasks: List<String>
    denied_tasks: List<String>
    
    redaction_rules: List<RedactionRule> {
      pattern: Regex                # Match sensitive data
      action: "redact" | "reject"   # What to do with matches
    }
    
    max_monthly_cost: Currency?
    automatic_approval_expiry: Duration?
  }
  
  usage_log: List<RemoteQueryLog>
  cost_tracking: CostTracker
}

RemoteProviderConfig {
  id: String
  api_key: String?                 # Encrypted
  
  enabled: Boolean
  data_retention: String           # "no_retention", "30_days", etc.
  privacy_policy_url: String?
  
  quota: QuotaConfig {
    monthly_tokens: Int?
    daily_requests: Int?
    cost_budget: Currency?
  }
}

RemoteQueryLog {
  timestamp: Timestamp
  provider: String
  model: String
  task: String
  
  input_tokens: Int
  output_tokens: Int
  cost: Currency
  latency_ms: Int
  
  data_redacted: Boolean
  data_summarized: Boolean
  
  session_id: UUID
}
```

## Security

Remote models present privacy risks:

1. **Data transmission**: Sending project data to external servers.
2. **Data retention**: Providers may retain data longer than expected.
3. **Unauthorized use**: Data might be used to train models (without explicit consent).

Mitigations:

1. **Explicit approval**: Users must approve before any data is sent.
2. **Redaction**: Automatic removal of sensitive data.
3. **Summarization**: Reduce data sent to minimum needed for task.
4. **Audit trail**: Log all remote queries.
5. **Privacy policies**: Display provider policies to users.

## MVP Scope

The MVP remote model system includes:

1. **Anthropic Claude API** as primary remote provider.
2. **Privacy approval dialog**: User approves before first use.
3. **Simple privacy policy**: Allow/disallow remote models per project.
4. **Usage logging**: Track queries and costs.
5. **Fallback**: If remote unavailable, attempt local model.

The MVP does not include:

- Automatic redaction (future).
- Summarization (future).
- Multi-provider fallback (future).
- Enterprise providers (future).
- Rate limiting (future, rely on provider's limits).

## Future Work

### Automatic Privacy Detection

Detect sensitive patterns automatically:

```
Before sending to remote:
  Scan for: API keys, passwords, PII, health data
  Found: 3 API keys, 1 password
  Redact: Yes (automatically)
  Proceed with redacted content
```

### Cost Optimization

Choose providers based on cost vs. quality:

```
Task: Summarization
Providers available:
  - Anthropic: $0.01/query
  - OpenAI: $0.005/query (cheaper)
  - Local (free)
  
Cost-sensitive: Choose OpenAI
Quality-sensitive: Choose Anthropic
Default: Use local
```

### Provider Comparison

UI showing provider differences:

```
Task: Code generation
Provider A: 95% accuracy, $0.10, 2s latency
Provider B: 92% accuracy, $0.05, 3s latency
Provider C (local): 80% accuracy, free, 0.5s latency

Recommendation: Provider A (best quality)
Budget option: Provider B
Fastest: Provider C
```

### Usage Reporting

Monthly reports on remote model usage:

```
Month of August 2025
Anthropic Claude:
  - Queries: 150
  - Tokens: 500,000
  - Cost: $5.00
  
OpenAI GPT-4:
  - Queries: 20
  - Tokens: 100,000
  - Cost: $3.00

Total: $8.00
Trend: Usage up 20% from July
```

### Audit Dashboard

UI for reviewing what data was sent where:

```
Recent remote queries:
  1. 2025-08-01 10:30 → Anthropic Claude
     Cost: $0.02, Tokens: 100
     Data redacted: Yes
     
  2. 2025-08-01 11:15 → OpenAI GPT-4
     Cost: $0.05, Tokens: 200
     Data redacted: No (approved by user)
     
  3. 2025-08-01 12:00 → Ollama (local)
     Cost: $0, Tokens: 300
     Data redacted: N/A
```

## Open Questions

- Should there be a "privacy score" for remote providers (rating their practices)?
- How should users handle provider policy changes (if they change terms, notify user)?
- Should Aidos support provider-specific instruction files (e.g., custom Claude instructions for OpenAI)?
- How should the engine handle provider API incompatibilities (different input/output formats)?
- Should there be a "low-bandwidth" mode that always prefers local models?
- How should tiered model selection work (fast and cheap vs. slow and expensive)?
- Should users be able to set spending alerts (notify at $X/month)?
