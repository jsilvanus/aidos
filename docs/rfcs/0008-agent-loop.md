# RFC-0008: Agent Loop and Tool-Use Protocol

Status: Accepted 2026-08-03

## Abstract

This RFC defines the core execution cycle of Aidos: how a model is given tools, how its
tool-call output is normalized, validated, authorized, and executed, and how results re-enter
the next model turn. It defines the provider-neutral tool-call envelope that keeps the runtime
independent of any vendor's function-calling format, and the mapping from the loop to the
Execution Graph (RFC-0019).

## Motivation

Aidos is an AI runtime. Its central operation is the loop in which a model's output becomes an
authorized effect on the user's machine. Before this RFC, that loop was described nowhere.
RFC-0020 mentioned "Function calls → [Tool Broker] → Execution" as a diagram fragment;
RFC-0019 defined `TaskKind.MODEL_CALL` and `TaskKind.TOOL_CALL` with no edge binding them.

Leaving the loop unspecified has three consequences:

1. It is the interface most tightly coupled to provider APIs. If it is invented inside the
   first session implementation, it will hard-code one vendor's shape, and "the runtime
   outlives vendors" (RFC-0000) becomes unbacked.
2. It is the convergence point of the Tool Broker, AI Engine, Prompt Construction, Capability
   Model, and Execution Graph. All five have interfaces that only make sense once the loop is
   fixed.
3. It is where authority is granted to model output. Prompt injection (RFC-0027) is dangerous
   precisely at this boundary, and the boundary had no owner.

## Goals

1. Define the provider-neutral representation of a tool.
2. Define the provider-neutral tool-call and tool-result envelopes.
3. Define the loop, including termination conditions.
4. Define the mapping from loop steps to Execution Graph nodes.
5. Define where authorization, taint, and budget checks occur.
6. Define the adapter contract that provider implementations must satisfy.

## Non-goals

This RFC does not define which models exist (RFC-0021) or how one is selected (RFC-0020).
It does not define context assembly (RFC-0025).
It does not define checkpointing mechanics (RFC-0009).
It does not define tool implementations (RFC-0032, RFC-0033, RFC-0034).

## Design

### Tool description

A tool operation is described to a model by a `ToolDescriptor`. The schema language is JSON
Schema (draft 2020-12), because it is what every current model provider and MCP itself
consume. The runtime owns the descriptor; provider adapters translate it.

```kotlin
data class ToolDescriptor(
    val name: String,                  // stable identifier, e.g. "fs.read"
    val title: String,                 // human-readable, for UI
    val description: String,           // shown to the model
    val inputSchema: JsonSchema,       // JSON Schema for parameters
    val effect: EffectKind,            // RFC-0030 typed effect
    val requiredPermission: Permission,
    val availability: ToolAvailability  // RFC-0049 platform profile
)
```

`effect` and `requiredPermission` are not sent to the model. They are used by the runtime to
decide approval, preview, retry, and audit behaviour.

**`ToolDescriptor` stays structurally aligned with MCP's tool shape** (`name`, `description`,
`inputSchema` as JSON Schema). This is a locked decision, not an accident of convenience.

MCP is becoming the de facto standard for tool description, and Aidos speaks it from the MVP
(RFC-0031). If it becomes universal, `ToolDescriptor` degrades gracefully into a thin
translation layer rather than a competing model that must be mapped in both directions. The cost
of the alignment is that the runtime's own fields — `effect`, `requiredPermission`,
`availability` — are kept strictly *additive* and runtime-side, never mixed into the part a
model or an MCP server sees.

Concretely: do not add cleverness to the wire shape. No custom schema dialect, no
Aidos-specific type system, no restructured parameter model. Anything the runtime needs to know
and the model does not goes in a sibling field, not inside `inputSchema`.

Tool descriptors consume token budget. The Prompt Constructor (RFC-0025) treats the rendered
descriptor set as a reserved budget section, because on small local models it is frequently
the largest fixed cost in the prompt.

### The tool-call envelope

Provider formats differ (OpenAI `tool_calls`, Anthropic `tool_use` blocks, Gemini
`functionCall`, and local models via constrained decoding or grammar). The runtime never
handles provider shapes above the adapter boundary.

```kotlin
data class ToolCall(
    val callId: String,        // provider-supplied or runtime-generated; unique within a turn
    val toolName: String,
    val arguments: JsonObject, // NOT yet validated against inputSchema
    val rawText: String?       // original text, for audit when parsing was heuristic
)

data class ToolCallResult(
    val callId: String,
    val outcome: ToolOutcome,
    val content: List<ContentBlock>,   // text, image, or resource reference
    val trustLevel: TrustLevel         // RFC-0027; almost always UNTRUSTED
)

sealed interface ToolOutcome {
    object Ok : ToolOutcome
    data class Denied(val reason: DenialReason) : ToolOutcome   // capability refused
    data class Failed(val error: AidosError) : ToolOutcome      // RFC-0029
    object Cancelled : ToolOutcome
}
```

`Denied` and `Failed` are returned **to the model**, not raised as runtime exceptions. A model
that asks for something it may not have must be told so it can adapt. This is the difference
between an agent and a crash.

### Provider adapter contract

```kotlin
interface ModelAdapter {
    suspend fun invoke(request: ModelRequest): ModelResponse
    fun supportsNativeToolCalls(): Boolean
}

data class ModelRequest(
    val messages: List<Turn>,
    val tools: List<ToolDescriptor>,
    val toolChoice: ToolChoice,          // AUTO | NONE | REQUIRED | SPECIFIC(name)
    val maxOutputTokens: Int,
    val stopConditions: List<String>
)

data class ModelResponse(
    val text: String?,
    val toolCalls: List<ToolCall>,
    val stopReason: StopReason,          // END_TURN | TOOL_USE | MAX_TOKENS | STOP_SEQUENCE | REFUSAL
    val usage: TokenUsage,
    val modelId: String,
    val modelVersion: String
)
```

Adapters for models without native tool-calling (many local GGUF models) implement the same
interface using constrained decoding or a documented text protocol, and set
`supportsNativeToolCalls() = false`. The loop above the adapter is identical. This is what
makes the offline path a first-class citizen rather than a degraded one: **the loop does not
know whether the model is local or remote.**

### The loop

One iteration is a **step**. Every step boundary is a checkpoint (RFC-0009).

```
step:
  1. Resolve model            → RFC-0020 (must precede budgeting; see RFC-0025)
  2. Assemble PromptPackage   → RFC-0025, including rendered ToolDescriptors
  3. Checkpoint               → RFC-0009 (before the expensive, non-idempotent call)
  4. Invoke adapter           → ModelResponse
  5. Record usage, charge budget → RFC-0028
  6. Checkpoint               → the model response is the most costly thing to reproduce
  7. If stopReason != TOOL_USE and toolCalls is empty → terminate (COMPLETED)
  8. For each ToolCall, in order:
       a. Resolve descriptor; unknown tool → ToolCallResult(Failed(UNKNOWN_TOOL))
       b. Validate arguments against inputSchema → invalid → Failed(INVALID_ARGUMENTS)
       c. Resolve capability; not held or attenuated away → Denied
       d. If effect requires approval (RFC-0030) → yield for user approval
       e. Execute via Effect Broker
       f. Checkpoint
  9. Append assistant turn and all ToolCallResults to the transcript
 10. Increment step counter; go to 1
```

Steps 8a–8c are the authorization boundary. **Model output is untrusted input until it has
passed all three.** The runtime never executes an argument object it has not validated against
the declared schema.

### Ordering and parallelism

In v1, tool calls within one turn execute **sequentially, in the order the model emitted
them**, even when the provider supports parallel calls. Rationale: sequential execution is the
only order that is reproducible in the audit trail, it avoids intra-turn write conflicts on
the working tree, and on Android it matches the short-burst execution model (RFC-0049).

Parallel execution is a post-v1 optimization gated on RFC-0019's `DEPENDS_ON` edges. When
introduced, only `Read` effects may run in parallel.

### Termination

A Run terminates when any of the following holds:

| Condition | Resulting state |
|---|---|
| Model returns no tool calls and `stopReason = END_TURN` | COMPLETED |
| `maxSteps` reached (default 24, configurable per Run) | FAILED(`STEP_LIMIT`) |
| Budget exhausted (RFC-0028) | FAILED(`BUDGET_EXHAUSTED`) |
| Cancellation requested (RFC-0006) | CANCELLED |
| Unrecoverable adapter error after retry policy exhausted | FAILED(`MODEL_ERROR`) |
| Capability denied and the model does not adapt within 3 steps | FAILED(`CAPABILITY_DENIED`) |

`maxSteps` is mandatory, not advisory. An agent loop without a hard step ceiling is an
unbounded spend loop (RFC-0028).

### No-progress detection

A Run that emits the same tool call with identical arguments three times consecutively, with
no intervening successful effect, is terminated with `FAILED(NO_PROGRESS)`. This is the
cheapest available guard against the most common agent failure mode and costs one comparison
per step.

### Mapping to the Execution Graph

The loop is not logged beside execution; it *is* the Execution Graph (RFC-0019).

| Loop element | Execution Graph node |
|---|---|
| One step's model call | `Task(kind = MODEL_CALL)` |
| One retry of that call | `Attempt` under that Task |
| One tool call from the response | `Task(kind = TOOL_CALL)` |
| Approval wait | `Task(kind = CAPABILITY_REQUEST)`, state `AWAITING_APPROVAL` |
| The whole loop | `Run` |

A `MODEL_CALL` Task that produces tool calls appends the resulting `TOOL_CALL` Tasks to the
Run. The edge `PRODUCED_CALL` (added to `EdgeKind` in RFC-0019) links them. This is what makes
"what exactly did the AI do to produce this file?" answerable by a query rather than by
reading logs.

### Transcript versus session memory

The loop operates on a **transcript**: the ordered turns of the current Run. The transcript is
not the session's `conversation_history`. When a Run completes, the transcript is summarized
into session memory (RFC-0011); the raw transcript is retained per the retention policy
(RFC-0056) and is reachable from the Run record.

This separation matters: a 24-step Run may produce 200k tokens of transcript, and none of it
belongs in the next Run's prompt verbatim.

## Data Model

```sql
-- One row per tool call issued by a model, linking the Execution Graph to the loop.
CREATE TABLE tool_calls (
    call_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    model_task_id TEXT NOT NULL,     -- the MODEL_CALL Task that emitted it
    tool_task_id TEXT,               -- the TOOL_CALL Task that executed it; NULL if rejected
    tool_name TEXT NOT NULL,
    arguments_json TEXT NOT NULL,
    schema_valid INTEGER NOT NULL,
    outcome TEXT NOT NULL,
    step_index INTEGER NOT NULL,
    FOREIGN KEY (run_id) REFERENCES runs(id),
    FOREIGN KEY (model_task_id) REFERENCES tasks(id),
    FOREIGN KEY (tool_task_id) REFERENCES tasks(id)
);

CREATE INDEX idx_tool_calls_run ON tool_calls(run_id, step_index);
```

## Security

The loop is the primary authority boundary in Aidos.

1. Arguments from a model are untrusted input. They are validated against JSON Schema before
   any capability resolution, and capability resolution happens before any effect.
2. Tool results are `UNTRUSTED` content (RFC-0027) and attenuate the Run's authority for
   subsequent steps.
3. `Denied` outcomes are returned to the model as data. The runtime must never escalate a
   denial into a grant without user approval, and must never re-run a denied call
   automatically.
4. `rawText` is retained when tool calls were parsed heuristically (non-native adapters),
   because parsing ambiguity is a security-relevant event.
5. Step and budget ceilings are enforced by the runtime, not by the model's cooperation.

## Platform notes (RFC-0049)

The loop is identical on all profiles. What differs is the tool set passed in `ModelRequest.tools`,
which is filtered by `ToolAvailability` against the device profile. A model on Android is never
told about `shell.exec`, so it never proposes it, so the user never sees a denial for a tool
that could not have worked. Availability filtering is a UX mechanism as much as a security one.

## MVP

1. `ToolDescriptor`, `ToolCall`, `ToolCallResult` envelopes.
2. One adapter with native tool calls; one adapter without (local model).
3. Sequential tool execution, `maxSteps`, no-progress detection.
4. Schema validation of arguments before capability resolution.
5. `tool_calls` table and `PRODUCED_CALL` edges.
6. Denial and failure returned to the model as data.

Not in MVP: parallel tool calls, `toolChoice = REQUIRED`, streaming tool-call deltas,
model-side caching of tool descriptors.

## Future Work

Parallel execution of `Read`-effect calls under `DEPENDS_ON` edges.

Tool descriptor caching and prompt-prefix reuse to reduce per-step cost on remote providers.

Sub-agent tools: exposing a worker session as a tool to a driver session, which unifies
RFC-0011's worker model with the tool-call mechanism.
