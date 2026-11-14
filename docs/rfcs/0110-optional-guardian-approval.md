# RFC-0110: Optional Guardian Approval for Tainted Runs

Status: Draft (2026-11-14). Partially amends [RFC-0027](0027-trust-and-taint.md).

Related: RFC-0027 (trust and taint), RFC-0008 (agent loop), RFC-0009 (durable execution), RFC-0018 (capability model), RFC-0020 (AI engine), RFC-0022 (inference backend / Decision capability), RFC-0025 (prompt construction), RFC-0030 (tool broker), RFC-0031 (MCP & Binder transport), RFC-0036 (settings), RFC-0046 (identity & audit), D7, D30, D31, D45, D51.

## Abstract

RFC-0027 attenuates tainted Runs so that any `Egress`, `SHELL_EXEC`, `APP_IPC`, or `UNSAFE` tool call parks for per-call human approval (`DenialReason.TAINT_REQUIRES_APPROVAL`). This RFC introduces an opt-in, user-scope-only **Guardian** mode (`TaintApprovalPolicy.GUARDIAN`, off by default) built as a two-gate evaluator: an inbound System One (`/v1/systemone` / Laya) one-way injection suspicion latch (Gate A) paired with a stateless, blind, zero-tool outbound LLM verifier (Gate B) that may grant a single-use taint exercise only for tool calls semantically aligned with the user's original trusted prompt, failing closed to human approval everywhere else.

## Motivation

Taint attenuation (RFC-0027, D7) stops indirect prompt injection at the authority layer: reading untrusted content does not prevent local in-project work, while dangerous or outward effects (`NETWORK_EGRESS`, `SHELL_EXEC`, `APP_IPC`, and `RecoveryClass.UNSAFE` operations) require per-call confirmation.

That model is safe, but in multi-step workflows that touch untrusted data early and legitimate effects immediately afterward, `MANUAL` escalation creates friction that trains users to click through prompts (the exact failure mode RFC-0027 Security warns against):

1. **Cross-app Android workflows (`Permission.APP_IPC`).** Under D51 and RFC-0031, other Android apps on the device act as MCP servers over Binder (`Permission.APP_IPC`, `McpEffect.READ`). Although Binder IPC stays on-device, an agent reading an untrusted calendar invite or incoming message from `aidos-calendar` and then creating a follow-up item in `aidos-tasks` crosses an app authority boundary. Attenuating `Permission.APP_IPC` to `TAINT_REQUIRES_APPROVAL` protects against a malicious invite driving another app, yet asking the user to manually approve every `aidos-tasks` call after reading a calendar entry breaks hands-free and mobile usability.
2. **Legitimate search-then-act coding and research loops.** A Run that fetches documentation or inspects `node_modules/` and then runs an allowed test command (`SHELL_EXEC`) or queries a known endpoint (`NETWORK_EGRESS`) currently stalls on every step, even when the call obviously fulfills the user's original request.
3. **Why a naive "trusted LLM filter" fails.** Asking the main Run's model—or any verifier that can see untrusted tool outputs or hold tool handles—to judge safety violates RFC-0027's "no trusted model" invariant because the untrusted payload would directly influence the verifier's own context.

A two-gate architecture resolves this without weakening RFC-0027's defaults: `MANUAL` stays the default, inbound non-generative classification catches overt instruction-like payloads before any Guardian check runs, and the outbound Guardian LLM never sees untrusted content at all.

## Goals

1. Keep `MANUAL` human approval as the default and fail-closed posture (`TaintApprovalPolicy.MANUAL`), configurable only in user scope (`ScopeClass.SECURITY`).
2. Add **Gate A**, an inbound System One (`noul`) screening pass over `UNTRUSTED` tool outputs that acts strictly as a monotonic, durable suspicion latch per Run (`injectionSuspected`).
3. Add **Gate B**, an outbound blind, stateless, single-turn, zero-tool Guardian LLM verifier that sees only the user's `TRUSTED` prompt (`runs.user_message_summary`) and the candidate tool invocation (`toolName`, descriptor metadata, `argumentsJson`), never conversation history or untrusted tool outputs.
4. Preserve all hard capability and taint boundaries (`Permission.SECRETS_READ`, `Mutate(OUT_OF_PROJECT)`, `CapabilityConstraints.requiresApprovalPerUse`, missing/revoked/expired/budget-exhausted grants), and record every suspicion latch, bypass, approval, and escalation in `audit_log`.
5. Explicitly cover `Permission.APP_IPC` under taint attenuation (`TAINT_REQUIRES_APPROVAL`) so cross-app Android workflows benefit from opt-in Guardian verification without leaving cross-app Binder calls unguarded when tainted.

## Non-goals

- Removing or lowering `runs.taint_level`. A Run that enters `UNTRUSTED` remains `UNTRUSTED` for its entire lifetime.
- Bypassing `ATTENUATED_BY_TAINT` denials (`Permission.SECRETS_READ`, `Mutate(OUT_OF_PROJECT)`), `REQUIRES_APPROVAL` explicit grant constraints, or `PolicyInferenceRouter`'s `RemotePendingApproval` gate on the main Run's tainted `MODEL_CALL`s.
- Scanning or filtering `TRUSTED` user prompts, `PROJECT` repository files, or structural `<context>` sandboxing (RFC-0025).
- Adding new SQLite tables or columns for Guardian state; durable execution reuses existing Execution Graph tables (`runs`, `tasks`, `attempts`, `continuations`, `audit_log`, `settings`).
- Training or fine-tuning Laya checkpoints (tracked separately in `docs/system-one.md`).

## Design

### Opt-in policy setting (`trust.taint_approval_policy`)

A new user-scope-only security setting controls whether tainted `TAINT_REQUIRES_APPROVAL` calls escalate directly to the user or pass through Gate B:

```kotlin
enum class TaintApprovalPolicy {
    /** Default: every TAINT_REQUIRES_APPROVAL call parks for manual human approval (RFC-0027). */
    MANUAL,

    /** Opt-in: Gate B may grant a single-use taint exercise when aligned and Gate A has not latched. */
    GUARDIAN,
}
```

Declared in `Settings` (RFC-0036):

- **Property:** `Settings.trustTaintApprovalPolicy`
- **Key:** `"trust.taint_approval_policy"`
- **Scope class:** `ScopeClass.SECURITY` (user-scope only, stored in `user.db`'s `settings` table)
- **Default:** `TaintApprovalPolicy.MANUAL`
- **Most restrictive:** `TaintApprovalPolicy.MANUAL`

Per RFC-0036, any `aidos.toml` (project scope) or session-scope attempt to set `trust.taint_approval_policy` is refused with file and line diagnostics, audited as `SCOPE_VIOLATION` in `settings_audit` (`user.db`), and never applied. Any invalid or unrecognized stored value fails closed to `mostRestrictive` (`MANUAL`).

### `Permission.APP_IPC` in Taint Attenuation

Under D51 and RFC-0031, Android apps acting as local MCP servers over Binder require `Permission.APP_IPC`. Although Binder MCP operations carry `EffectKind.Read` and make no direct network egress from Aidos itself, the target app holds its own Android permissions and state (for example, `aidos-calendar` reading events, `aidos-tasks` creating/completing tasks).

Therefore, `TaintAttenuation.decide` treats `Permission.APP_IPC` as `Decision.REQUIRES_APPROVAL` (`DenialReason.TAINT_REQUIRES_APPROVAL`) whenever `run.taint == TrustLevel.UNTRUSTED`, alongside `EffectKind.Egress`, `Permission.NETWORK_EGRESS`, `Permission.SHELL_EXEC`, and `RecoveryClass.UNSAFE`. In `MANUAL` mode, cross-app calls after reading untrusted data park for user confirmation; in `GUARDIAN` mode, coherent cross-app workflows (e.g., `aidos-calendar` -> `aidos-tasks`) can proceed smoothly when aligned with the user's request while unrelated cross-app calls still escalate.

### Two-Gate Verification Architecture

```
  ToolCallResult (trustLevel == UNTRUSTED)
        │
        ▼
  ┌─────────────────────────────────────────────────────────────────────┐
  │ Gate A: Inbound Decision Model Screening (TaintInputScreener)       │
  │  POST /v1/systemone (Laya `noul` >= 0.80, model "laya-multilingual")│
  │  • noul >= threshold  ──► injectionSuspected = true (one-way latch) │
  │                           audit: TaintInjectionSuspected            │
  │  • noul < threshold / unavailable ──► Run stays UNTRUSTED; no latch │
  └─────────────────────────────────────────────────────────────────────┘
        │
        ▼  (Later, when a TOOL_CALL hits DenialReason.TAINT_REQUIRES_APPROVAL)
  ┌─────────────────────────────────────────────────────────────────────┐
  │ Policy & Latch Check                                                │
  │  • policy == MANUAL ──────────────────────────────► Park for Human  │
  │  • hasSuspectedInjection(runId) == true ──────────►                 │
  │      audit: GuardianBypassedSuspectedInjection ───► Park for Human  │
  │  • policy == GUARDIAN && !hasSuspectedInjection ──► Gate B          │
  └─────────────────────────────────────────────────────────────────────┘
        │
        ▼
  ┌─────────────────────────────────────────────────────────────────────┐
  │ Gate B: Outbound Blind Guardian LLM (GuardianApprovalVerifier)      │
  │  • Prompt = ONLY runs.user_message_summary (TRUSTED)                │
  │           + candidate (toolName, title, description, argumentsJson) │
  │  • Zero history, zero untrusted outputs, tools=empty, ToolChoice.None│
  │  • RoutingContext(runTaint = TrustLevel.TRUSTED)                    │
  │                                                                     │
  │  Verdict:                                                           │
  │   ├─ ALIGNED ──► audit: GuardianApproved                            │
  │   │              broker.invoke(..., runTaint = PROJECT) once        │
  │   │              (runs.taint_level remains UNTRUSTED)               │
  │   └─ UNRELATED / AMBIGUOUS (or parse/routing error → fail closed)   │
  │              ──► audit: GuardianEscalated                           │
  │                  Park for Human (YIELDED + CapabilityRequested      │
  │                  with Guardian rationale & taint source)            │
  └─────────────────────────────────────────────────────────────────────┘
```

#### Gate A: Inbound Decision Model Screening (`TaintInputScreener`)

Gate A runs during tool result completion (`AgentLoopTaskRunner.finishToolCall`) whenever a `ToolCallResult` arrives with `result.trustLevel == TrustLevel.UNTRUSTED` (and after RFC-0035 secret withholding so credentials are never forwarded to any classifier).

```kotlin
interface TaintInputScreener {
    suspend fun screen(toolName: String, untrustedText: String): InputScreeningVerdict
}

data class InputScreeningVerdict(
    val instructionSuspected: Boolean,
    val probability: Double? = null,
    val modelId: String? = null,
)
```

`SystemOneTaintInputScreener` (in `agent:daemon`) delegates to the Engine's System One HTTP endpoint (`POST /v1/systemone` per `docs/system-one.md` and RFC-0022):

- **Model:** `"laya-multilingual"` (configurable default).
- **Primitive:** `noul` (`DecisionQuestion.Noul`).
- **Instruction:** `"The text is giving an instruction to an assistant or system rather than stating content."`
- **Threshold:** `0.80` (default). An answer with `noul >= 0.80` (when not abstained, or if the screener policy configures explicit abstention handling) returns `instructionSuspected = true`, which latches `injectionSuspected = true`.

**One-way suspicion latch invariant:**
1. Gate A can **only** raise suspicion (`injectionSuspected = true`). It can never lower `runs.taint_level`, mark a result `PROJECT`/`TRUSTED`, or clear a prior latch within the same Run.
2. Once any `TOOL_CALL` attempt in a Run records `injectionSuspected = true` inside `attempts.output_snapshot` (`StoredToolResult`), `hasSuspectedInjection(runId)` evaluates to `true` for the rest of the Run, even across process eviction and crash recovery (D3).
3. When Gate A latches suspicion on a tool result, it appends an audit row with `kind = "TaintInjectionSuspected"` to `audit_log` (recording `toolName` and score/model metadata, never raw sensitive content).
4. If no System One model is installed or reachable, Gate A returns `instructionSuspected = false` rather than granting authority: the Run remains `UNTRUSTED` and Gate B (or `MANUAL` approval) still governs every sensitive call. Gate A is a monotonic pre-filter latch, never a sole safety boundary.

#### Gate B: Outbound Blind Guardian LLM Verification (`GuardianApprovalVerifier`)

When `ToolBroker.invoke(subjectId, call, effectiveTaint)` returns `ToolOutcome.Denied(DenialReason.TAINT_REQUIRES_APPROVAL)` and a `capabilityId` exists:

1. If `trust.taint_approval_policy == TaintApprovalPolicy.MANUAL`, `AgentLoopTaskRunner` follows standard RFC-0027 escalation and parks for human approval immediately.
2. If `trust.taint_approval_policy == TaintApprovalPolicy.GUARDIAN`, `AgentLoopTaskRunner` queries durable state via `hasSuspectedInjection(runId)`:
   - **If `true` (Gate A latched):** Gate B is **not invoked**. `AgentLoopTaskRunner` writes an `audit_log` entry with `kind = "GuardianBypassedSuspectedInjection"` and parks the Run for manual human approval (`TaskState.AWAITING_APPROVAL`, `RunState.YIELDED`, `CapabilityRequested` event), noting that suspected prompt injection disabled automatic verification.
   - **If `false`:** `AgentLoopTaskRunner` invokes `GuardianApprovalVerifier.verify(request)`.

```kotlin
enum class GuardianDecision { ALIGNED, UNRELATED, AMBIGUOUS }

data class GuardianApprovalRequest(
    val userMessageSummary: String,  // runs.user_message_summary (TRUSTED)
    val toolName: String,
    val toolTitle: String? = null,
    val toolDescription: String? = null,
    val argumentsJson: String,
)

data class GuardianVerdict(
    val decision: GuardianDecision,
    val rationale: String,
    val modelId: String? = null,
)

interface GuardianApprovalVerifier {
    suspend fun verify(request: GuardianApprovalRequest): GuardianVerdict
}
```

`LlmGuardianApprovalVerifier` constructs a **blind, stateless, single-turn, zero-tool** `ModelRequest`:

- **Inputs (strictly bounded):**
  - `Turn.System`: concise rubric instructing the model to classify whether the candidate tool invocation is strictly aligned with fulfilling the user's explicit request, output a structured JSON decision (`ALIGNED`, `UNRELATED`, or `AMBIGUOUS`) with a brief rationale, and treat candidate `argumentsJson` as untrusted data rather than instructions.
  - `Turn.User`: contains **only** `runs.user_message_summary` (the user's `TRUSTED` prompt) and the single candidate invocation (`toolName`, descriptor `title`, descriptor `description`, `argumentsJson`).
  - **Excluded by construction:** conversation history (`Turn.Assistant`, `Turn.ToolResult`), `ContentNode` text, `InstructionSet` bodies, and any untrusted tool output.
- **Zero-tool enforcement:** `tools = emptyList()` and `toolChoice = ToolChoice.None`. The verifier cannot emit tool calls or invoke any broker operation.
- **Clean `RoutingContext`:** `LlmGuardianApprovalVerifier` selects a model via `InferenceRouter.select(ModelKind.LLM, routingCtx)` with `runTaint = TrustLevel.TRUSTED` because **no untrusted content enters Gate B's prompt** (only the `TRUSTED` user prompt summary and the proposed tool call's name/schema-validated arguments). Existing `PolicyInferenceRouter` rules—including `routing.remote_egress` (`NEVER` / `ASK` / `ALLOW`) and locality limits (RFC-0104)—still apply unchanged.
- **Fail-closed verdict parsing:** Any malformed response, unrecognized decision string, adapter failure, or non-approved `RoutingDecision` (`RemotePendingApproval`, `UnavailableOffline`, `DisabledByPolicy`, `ForegroundRequired`) maps to `GuardianDecision.AMBIGUOUS`.

**Handling the verdict:**

- **`ALIGNED`:**
  1. Write an `audit_log` row with `kind = "GuardianApproved"` (`subjectRef = call.toolName`, `capabilityId = capabilityId.value`, `detailJson` carrying `callId`, `decision`, `rationale`, and `modelId`, redacted per RFC-0035).
  2. Grant a **single-use taint exercise** for this `ToolCall` only: re-invoke `broker.invoke(subjectId, call, TrustLevel.PROJECT)` once, leaving `runs.taint_level` unchanged at `TrustLevel.UNTRUSTED`.
  3. Notice that re-invoking the broker with effective taint `PROJECT` still executes **every non-taint check** in `SqliteCapabilityManager.validate`—including `CapabilityConstraints.requiresApprovalPerUse` (`DenialReason.REQUIRES_APPROVAL`), `maxExerciseCount`, expiry, and revocation. If `REQUIRES_APPROVAL` is returned on that second call, the task still parks for human approval!
- **`UNRELATED` or `AMBIGUOUS`:**
  1. Write an `audit_log` row with `kind = "GuardianEscalated"` (`subjectRef = call.toolName`, `capabilityId = capabilityId.value`, `detailJson` carrying `decision`, `rationale`, and `modelId`, redacted per RFC-0035).
  2. Park the Run (`TaskState.AWAITING_APPROVAL`, `RunState.YIELDED`, `SuspendedOperation.ToolCall`, `CapabilityRequested` event) with an escalation message that preserves RFC-0027's taint source description (`taintSourceDescription(runId)`) and includes the Guardian's decision and rationale so the user understands why automatic verification declined to clear the step.

### Hard Boundaries Never Overridden by Guardian

Gate B only ever evaluates `DenialReason.TAINT_REQUIRES_APPROVAL`. It can never override or bypass:

| Boundary | Denial / Outcome | Why Guardian cannot override |
|---|---|---|
| `Permission.SECRETS_READ` on tainted Run | `DenialReason.ATTENUATED_BY_TAINT` | Denied outright in `TaintAttenuation.decide`; never produces `TAINT_REQUIRES_APPROVAL`. |
| `EffectKind.Mutate(MutationScope.OUT_OF_PROJECT)` on tainted Run | `DenialReason.ATTENUATED_BY_TAINT` | Denied outright in `TaintAttenuation.decide`; never produces `TAINT_REQUIRES_APPROVAL`. |
| `CapabilityConstraints(requiresApprovalPerUse = true)` | `DenialReason.REQUIRES_APPROVAL` | Checked unconditionally in `SqliteCapabilityManager.validate` (including when Guardian passes effective taint `PROJECT`). |
| Missing, revoked, expired, out-of-scope, or exhausted grant | `NO_CAPABILITY`, `CAPABILITY_REVOKED`, `CAPABILITY_EXPIRED`, `SCOPE_MISMATCH`, `CONSTRAINT_EXCEEDED`, `BUDGET_EXHAUSTED` | Evaluated before or alongside taint in `ToolBroker` / `SqliteCapabilityManager`; Guardian never creates or widens capabilities. |
| Remote LLM `MODEL_CALL` on tainted Run (`PolicyInferenceRouter`) | `RoutingDecision.RemotePendingApproval` | Carries the full tainted transcript off-device; governed by RFC-0023/0027 `CAPABILITY_APPROVAL`, never by Gate B. |

## Data Model

RFC-0110 requires **no new SQLite tables or columns** in `schema/user.sql`, `schema/vault.sql`, or `schema/project.sql`. All durable state reuses existing tables:

- **`settings` (`schema/user.sql`):** Stores `trust.taint_approval_policy` (`"MANUAL"` or `"GUARDIAN"`) at `scope = 'user'`, enforced as `ScopeClass.SECURITY` with `mostRestrictive = MANUAL`. Attempted overrides in `project` or `session` scopes are audited in `settings_audit` (`schema/user.sql`).
- **`runs` (`schema/project.sql`):**
  - `runs.user_message_summary` supplies the `TRUSTED` user intent passed to Gate B.
  - `runs.taint_level` remains `UNTRUSTED` monotonically for the duration of the Run even when Gate B approves a single-use exercise.
- **`attempts` (`schema/project.sql`):** Gate A's suspicion latch is stored durably inside `attempts.output_snapshot` via `StoredToolResult` (serialized JSON on `TOOL_CALL` tasks):

```kotlin
@Serializable
private data class StoredToolResult(
    val outcome: String,
    val detail: String?,
    val text: String,
    val trustLevel: String,
    val resultTrust: String? = null,
    val injectionSuspected: Boolean = false,
    val injectionProbability: Double? = null,
)
```

  `hasSuspectedInjection(runId)` queries `attempts` joined with `tasks` (`WHERE tasks.run_id = ? AND tasks.kind = 'TOOL_CALL'`) and returns `true` if any `StoredToolResult` in that Run has `injectionSuspected == true` (deserializing backwards-compatibly with older rows where the field defaults to `false`). If `LargeOutputStore` (RFC-0056) externalizes an oversized snapshot, `hasSuspectedInjection` resolves the stored payload or records the compact flag alongside it before checking `injectionSuspected`.
- **`continuations` and `tasks` (`schema/project.sql`):** When Gate A latches and Gate B is bypassed, or when Gate B returns `UNRELATED`/`AMBIGUOUS`, `AgentLoopTaskRunner` parks via `SuspendedOperation.ToolCall` (`continuations.suspended_operation = 'TOOL_CALL'`, `tasks.state = 'AWAITING_APPROVAL'`, `runs.state = 'YIELDED'`), storing the Guardian rationale inside `ToolCallApprovalDetail.taintMessage`.
- **`audit_log` (`schema/project.sql`):** Records all Gate A and Gate B decisions using the existing append-only `audit_log` table with `actor_kind = 'RUNTIME'` (or session actor matching executor audit conventions) and RFC-0035 redaction:
  - `TaintInjectionSuspected` — Gate A latched `injectionSuspected = true` on an `UNTRUSTED` tool output.
  - `GuardianBypassedSuspectedInjection` — Gate B was skipped because Gate A had already latched suspicion for the Run.
  - `GuardianApproved` — Gate B returned `ALIGNED` and granted a single-use taint exercise.
  - `GuardianEscalated` — Gate B returned `UNRELATED` or `AMBIGUOUS` (including fail-closed parse/routing errors) and parked the Run for human approval.

## Security

### Threat model and mitigations

1. **Indirect prompt injection via tool results.**
   - *Mitigation (defense in depth):* Gate A screens inbound `UNTRUSTED` results with `/v1/systemone` (`noul`) and irreversibly latches `injectionSuspected = true` when suspected (`>= 0.80`). Even if an adversarial payload evades Gate A (for example, a zero-shot false negative on Laya, per `docs/system-one.md`), Gate B never sees the untrusted tool output at all: Gate B evaluates only `runs.user_message_summary` (`TRUSTED`) against the proposed `toolName` and `argumentsJson`. An injected unrelated action (`curl evil.example`, `aidos-tasks` delete when the user asked to summarize calendar events) is classified `UNRELATED` or `AMBIGUOUS` and parked for human approval.
2. **Argument-borne prompt injection targeting Gate B.**
   - *Risk:* Although Gate B does not see `ToolCallResult` text, `argumentsJson` itself is produced by a tainted model turn and reaches Gate B's prompt.
   - *Mitigation:* Gate B is strictly single-turn with `tools = emptyList()` and `toolChoice = ToolChoice.None`, runs in its own isolated prompt without conversation history, and fails closed to `AMBIGUOUS` on any unexpected format or uncertainty. Even if an attacker manipulates `argumentsJson`, Gate B cannot execute tools itself, cannot grant new capabilities, and cannot approve `SECRETS_READ`, `Mutate(OUT_OF_PROJECT)`, or `requiresApprovalPerUse` grants.
3. **Confused deputy across Android apps (`Permission.APP_IPC`).**
   - *Mitigation:* Adding `Permission.APP_IPC` to `TaintAttenuation.decide` (`REQUIRES_APPROVAL`) closes the Binder gap where an untrusted document or event read could trigger cross-app writes or queries without any approval gate.
4. **Repository-driven downgrade (`aidos.toml`).**
   - *Mitigation:* `trust.taint_approval_policy` is `ScopeClass.SECURITY`, so a cloned repository cannot enable `GUARDIAN` mode via `aidos.toml`. Any attempt is rejected, surfaced with file and line, and audited in `settings_audit`.
5. **Egress and privacy leakage from Gate A / Gate B.**
   - *Mitigation:* Gate A calls the local Engine `/v1/systemone` endpoint after RFC-0035 secret withholding (`SecretContentWithheld`), so credentials are never screened or persisted. Gate B passes `runTaint = TrustLevel.TRUSTED` in `RoutingContext` only because its prompt excludes all untrusted tool outputs and transcript history, while `PolicyInferenceRouter` still enforces `routing.remote_egress` and RFC-0104 locality ceilings.

### Honest limits

- As documented in `docs/system-one.md`, the base `laya-multilingual` checkpoint has limited zero-shot accuracy without task-specific fine-tuning. Gate A is therefore strictly a one-way suspicion latch (`false` negatives fall back to Gate B; `false` positives fall back safely to `MANUAL` human approval).
- An attacker who crafts a malicious tool call whose arguments *appear* semantically aligned with a very broad user prompt (e.g., user says "help me handle everything in this email" and the email injects a plausible-looking outbound message) cannot be reliably distinguished by intent alignment alone. Users enabling `GUARDIAN` accept that semantic alignment verification trades some human review for reduced prompt fatigue, and should keep `MANUAL` (or `requiresApprovalPerUse = true`) for high-consequence capabilities.

## MVP

1. Add `TaintAttenuation` coverage for `Permission.APP_IPC` -> `Decision.REQUIRES_APPROVAL`.
2. Declare `Settings.trustTaintApprovalPolicy` (`trust.taint_approval_policy`, `TaintApprovalPolicy.MANUAL` default, `ScopeClass.SECURITY`, `mostRestrictive = MANUAL`).
3. Add `TaintInputScreener` and `SystemOneTaintInputScreener` calling `/v1/systemone` (`noul`, `"The text is giving an instruction to an assistant or system rather than stating content."`, threshold `0.80`, model `"laya-multilingual"`), latching `injectionSuspected = true` in `StoredToolResult` (`attempts.output_snapshot`) and auditing `TaintInjectionSuspected`.
4. Add `GuardianApprovalVerifier` and `LlmGuardianApprovalVerifier` using a stateless, single-turn, zero-tool (`tools = emptyList()`, `ToolChoice.None`) `ModelRequest` over a clean `RoutingContext` (`runTaint = TrustLevel.TRUSTED`) with fail-closed `AMBIGUOUS` fallback.
5. Wire Gate A and Gate B into `AgentLoopTaskRunner` and `RuntimeCompositionRoot`, preserving all hard boundaries (`SECRETS_READ`, `Mutate(OUT_OF_PROJECT)`, `requiresApprovalPerUse`, capability validity) and auditing `GuardianBypassedSuspectedInjection`, `GuardianApproved`, and `GuardianEscalated`.
6. Unit and end-to-end tests covering: default `MANUAL` behavior, `aidos.toml` refusal on `SECURITY` setting, Gate A one-way latch persistence across eviction/resume, Gate B bypass on suspected injection, Gate B `ALIGNED` single-use exercise preserving `runs.taint_level = UNTRUSTED`, Gate B `UNRELATED`/`AMBIGUOUS`/error fail-closed escalation with rationale, hard-boundary enforcement (`SECRETS_READ`, `OUT_OF_PROJECT`, `requiresApprovalPerUse`), and `Permission.APP_IPC` taint attenuation.

## Future Work

- Fine-tuned Laya / System One prompt-injection dataset and temperature calibration to raise Gate A recall and reduce false-positive manual escalations.
- Per-permission or per-recovery-class Guardian policy granularity (for example, allowing Guardian for `APP_IPC` while keeping `SHELL_EXEC` strictly `MANUAL`) under `ScopeClass.SECURITY`.
- Exposing Gate A suspicion indicators and Gate B audit rationales in Run Summary (RFC-0057) and desktop/Android approval sheets.
