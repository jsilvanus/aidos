# RFC-0129: Chat Templates and Tool Calling for Local Models

Status: Implemented 2026-11-16 (built in aidos#138 and aidos#142; this RFC documents it).
Non-MVP Work: **Remaining**

Related: RFC-0020 (AI engine), RFC-0021 (model adapters; "constrained decoding or a documented text protocol"), RFC-0022 (local models; its "Amendment 2026-11-16: prompt formatting and tool calling" is the short form of this RFC), RFC-0008 (agent loop), RFC-0030 (tool broker, argument validation), RFC-0025 (prompt construction), RFC-0103 and RFC-0121 (Engine and API v1, additive only, D63), RFC-0125 (inference backends), RFC-0128 (hardware acceleration).

## Abstract

A `ModelRequest` carries typed turns (system, user, assistant, tool result) and a set of tools. A llama.cpp GGUF model needs one string. This RFC defines how that string is made: the model's own chat template renders the conversation, tools are offered in one text protocol on every local model, a GBNF grammar forces a call when the caller requires one, and the calls are parsed back out of the output. Desktop and Android share one implementation.

RFC-0022's amendment states the rules in ten lines. This RFC is the standalone specification: the protocol, the fallbacks, the failure behaviour, what is tested and what is not.

## Motivation

Before aidos#138 both llama.cpp adapters wrote prompts as `User:` / `Assistant:` lines. No instruction-tuned model is trained on that framing, so models answered worse, ran past the end of their turn, or continued the dialogue themselves. Tools never reached a local model: the desktop adapter compiled a grammar and discarded it, and the Android adapter refused any request with tools. An agent loop (RFC-0008) on a local model therefore could not call a tool at all.

## Goals

1. A chat model is prompted in the framing it was trained on.
2. A local model can take part in the agent loop: tools offered, calls returned as `ToolCall`s.
3. The behaviour is identical on desktop and Android.
4. A template or grammar problem degrades the model, never makes it unusable.
5. No kernel contract and no API v1 change (D63).

## Non-goals

- Model-family tool syntaxes (Llama 3.x `<|python_tag|>`, Qwen/Hermes `<tool_call>`, Mistral `[TOOL_CALLS]`). Listed under Post-MVP Work.
- Remote models. RFC-0021's remote adapters use the provider's own tool API.
- Validating arguments against the tool's schema. That is RFC-0030's job after parsing.
- Multimodal prompt content. Images, audio and resource references are rendered as placeholder text (below).

## Design

### 1. The model's chat template is the prompt format

A GGUF that declares `tokenizer.chat_template` is prompted by evaluating that template with llama.cpp's Jinja engine over the request's turns, with the generation prompt appended (`LlamaModel.applyTemplate`). The adapter loads such a model with Jinja enabled; if the model will not load that way (the template cannot be parsed), it loads without Jinja and logs the reason.

If the template cannot be evaluated at request time, the adapter falls back to a role-labelled prompt (`User:` / `Assistant:` / `Tool result:`, ending in `Assistant:`) and logs that it did. The fallback exists so that a bad template does not make the model unusable; it is not a supported format.

A caller or cookbook entry may supply a template override, which replaces the GGUF's own for that adapter.

### 2. A model without a template gets its content verbatim

A model that declares no template has no notion of roles. Its prompt is the turns' content concatenated in order, with nothing the caller did not send. Any framing would be text the caller did not write and, on a base completion model, would decide what it predicts next. Tools are not offered to such a model by prompt text, because there is no system turn to put them in; a caller that requires a call still gets the grammar (section 5).

### 3. One leading system message

All system turns are merged, in order, into one leading `system` message, joined by a blank line. Several templates accept only that; some reject or silently drop a system message elsewhere. Empty system turns are skipped. The tool instructions (section 4) are appended to this message. Role mapping: `Turn.User` to `user`, `Turn.Assistant` to `assistant`, `Turn.ToolResult` to `tool`.

Content blocks are flattened to text: `Text` as is; `Image`, `Audio` and `ResourceRef` become `[Image: <mime>]`, `[Audio: <mime>]` and `[Resource: <nodeId>, <n> bytes]`. A local text model cannot use the bytes, and the placeholder tells it something was there.

### 4. The tool protocol

Every local model uses the same text protocol, whatever its family. The system message lists the offered tools and asks the model to reply with only JSON objects of this form:

```
{"tool": "<tool name>", "args": {<arguments as JSON>}}
```

one object per call, then stop and wait for the result. Each tool is listed as its name, description and input JSON Schema. The closing sentence depends on `tool_choice`: `Required` says "You must call a tool now."; `Specific` names the tool; `Auto` says to answer normally if no tool is needed.

Family-specific syntaxes are not used, because the binding returns raw text and a family-specific call could not be parsed back reliably across the models the cookbook ships. Earlier assistant tool calls are replayed in the same syntax, one JSON object per call after any text, and tool results go back as `tool` messages, so the model sees the whole exchange in the format it is asked to produce.

### 5. `tool_choice`

| Choice | Tools offered | Decoding |
|---|---|---|
| `None` | none | free |
| `Auto` | all | free. The model may answer in prose; calls are found heuristically and `ToolCall.rawText` keeps the matched text for audit (RFC-0021: a call the runtime guessed at is a call whose arguments may not be what the model meant). |
| `Required` | all | constrained by a GBNF grammar: the output can only be one or more calls. `rawText` is null. |
| `Specific(name)` | that one tool | constrained as `Required`, with a grammar naming only that tool. |

The grammar (`GbnfGrammarCompiler`) constrains the shape `{"tool": <one of the offered names>, "args": <JSON value>}`, repeated. It guarantees well-formed JSON and a valid tool name. It does not enforce the tool's input schema.

### 6. Parsing and the returned text

`ToolCallParser` finds `{"tool": ..., "args": {...}}` objects in the output. A call naming a tool that was not offered is dropped. Arguments that do not parse as a JSON object are dropped. Each call gets a fresh `call_<uuid>` id and `capabilityId = null`, which the agent loop resolves (RFC-0008). Arguments are not schema-validated here; `ToolBroker` does that (RFC-0030).

The text returned to the caller excludes the parsed calls, so a call is not also shown to the user as prose. Under a grammar the text is empty. In heuristic mode each call's `rawText` is cut out and the remainder trimmed. Stop reason is `TOOL_USE` when calls were found and the output was not cut off, `MAX_TOKENS` or `STOP_SEQUENCE` otherwise.

### 7. Prompt-prefix cache

Each request sets `cache_prompt`, so the evaluated prefix of the previous request is kept and only the new tail of a conversation is processed. This works because the template renders earlier turns identically on every request.

### 8. Per-request sampling and unknown roles

A request's `temperature` and `top_p` override the per-model run settings for that request, carried beside the request as the `SamplingOverride` coroutine element because `ModelRequest` is frozen at G0. A message whose role is not `system`, `developer`, `user`, `assistant` or `tool` is refused with a 400; it is never treated as a system turn, so a typo cannot gain system authority. `developer` is read as `system`. (Both from RFC-0022's amendment; they are the Engine side of the same request path.)

### 9. One implementation

`LlamaChatPrompt` holds sections 1 to 6 and is used by `LlamaCppAdapter` (desktop) and `AndroidLlamaCppAdapter`. The two hosts cannot differ in how a request becomes a prompt. The Android adapter previously refused requests with tools; it no longer does.

## Security

The tool list, descriptions and schemas are placed in the system message. They come from tool descriptors the runtime already fences (RFC-0025), not from model output. Calls parsed from model output are untrusted input: they are validated against the offered tool set here and against the tool's schema and the caller's capabilities in the broker (RFC-0030, RFC-0018). A heuristic call keeps `rawText` for the audit trail. Prompt text is not logged by the fallback notice; only the reason is.

## Testing

`LlamaChatPromptTest` (JVM) asserts, against real llama.cpp with the echo fixture GGUF, that the rendered prompt is exactly the template's output and that the model's reply shows it ran with that prompt: roles kept and system turns merged; tools described in the syntax the parser reads; earlier calls and results replayed; `tool_choice` deciding offered tools and grammar; calls removed from returned text; the adapter using the model's template; tool instructions inside the templated system turn; a template-less model getting its content verbatim; closing during a generation. `GbnfGrammarAndParsingTest` covers the grammar and the parser.

Not tested here: the Android adapter on a device (it compiles and shares `LlamaChatPrompt`, but the cloud environment has no Android SDK, see `docs/android-fgs-device-test.md`); any specific real chat model's tool-call quality; the template fallback path against a real unparseable template.

## Open Questions

- How reliable is the shared text protocol on small models (1B to 3B) under `Auto`? It is measured only on the fixture. A tool-call benchmark across the cookbook models would decide whether family syntaxes are worth their complexity.
- Should `Auto` use a grammar that allows either prose or a call (a lazy grammar), so that calls are always well-formed without forcing one?

## Implementation Files

```yaml
files:
  - engine/modelruntime/src/jvmMain/kotlin/dev/aidos/modelruntime/LlamaChatPrompt.kt
  - engine/modelruntime/src/jvmMain/kotlin/dev/aidos/modelruntime/LlamaCppAdapter.kt
  - engine/modelruntime/src/jvmMain/kotlin/dev/aidos/modelruntime/ToolCallParser.kt
  - engine/modelruntime/src/jvmMain/kotlin/dev/aidos/modelruntime/GbnfGrammarCompiler.kt
  - engine/modelruntime/src/commonMain/kotlin/dev/aidos/modelruntime/SamplingOverride.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/inference/AndroidLlamaCppAdapter.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/inference/AndroidLlamaCppInferenceBackend.kt
  - engine/modelruntime/src/jvmTest/kotlin/dev/aidos/modelruntime/LlamaChatPromptTest.kt
  - engine/modelruntime/src/jvmTest/kotlin/dev/aidos/modelruntime/GbnfGrammarAndParsingTest.kt
```

## Post-MVP Work

Checklist for work beyond the MVP. Each item has a stable id; set `status` to `partial` or `done` and list the implementing `files` (checked by `scripts/rfc/postmvp.py`).

```yaml
post_mvp:
  - id: "0129.1"
    title: "Parser reads arguments nested deeper than two levels of braces"
    status: todo
    phase: P1
    done_when: "a call whose args hold objects nested four levels deep parses, with a test; the regex in ToolCallParser is replaced by a brace-balancing scan"
  - id: "0129.2"
    title: "Grammar requires args to be a JSON object and escapes tool names"
    status: todo
    phase: P1
    done_when: "GbnfGrammarCompiler rejects a non-object args value and a tool name containing a quote or backslash compiles correctly, with tests"
  - id: "0129.3"
    title: "Grammar enforces each tool's input schema (per-tool argument rules)"
    status: todo
    phase: P2
    depends_on: ["0129.2"]
    done_when: "under Required a model cannot emit an argument of the wrong type or a missing required property for the chosen tool"
  - id: "0129.4"
    title: "Tool-call reliability measured on the cookbook models under Auto and Required"
    status: todo
    phase: P1
    done_when: "an env-gated benchmark reports the share of well-formed, correct calls per cookbook chat model and the result is recorded in this RFC"
  - id: "0129.5"
    title: "Model-family tool syntaxes where measured to beat the shared protocol"
    status: todo
    phase: P3
    depends_on: ["0129.4"]
    done_when: "a family syntax is used for a model only where 0129.4 shows it better, with a parser for it"
  - id: "0129.6"
    title: "Android adapter exercised on a physical device with a templated model and a tool call"
    status: todo
    phase: P1
    done_when: "docs/android-fgs-device-test.md has a step that sends a tool request to a loaded model on the reference phone and records the parsed call"
```
