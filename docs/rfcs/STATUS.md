# RFC implementation status

Audited 2026-11-09 against the code (not against status lines or milestone checkmarks). One read-only audit per Accepted RFC; test files were read, not run.

**Accepted** (the status in each RFC) means the design is approved. This table says how much of it exists in code. `IMPLEMENTED` = the RFC's MVP is built and tested; `PARTIAL` = some of it is built and tested, gaps listed; `NOT_IMPLEMENTED` = schema/DDL at most; `NOT_APPLICABLE` = the RFC's MVP is a decision, not code.

Not audited: RFC-0000 Vision, 0001 Principles, 0099 Roadmap (documents, not implementable), 0013/0014 (Superseded), 0100-0102 (reviews). Draft/Proposed RFCs are listed at the end.

| RFC | Verdict | Built / missing |
|---|---|---|
| 0002 | PARTIAL | executor/capability/audit/lock/identity/api real; BudgetLedger interface only; no project delete/archive |
| 0003 | PARTIAL | capability, audit, redactor, scrubbed MCP env, socket auth real; SqliteSecretsVault not wired in prod; audit gaps |
| 0004 | PARTIAL | events table+EventStore+causal depth; no category/visibility/subscribe-authorization, no redaction at publish |
| 0005 | PARTIAL | event wake + causal depth done; MOBILE deterministic gate and admission policy missing |
| 0006 | PARTIAL | RunState, continuations, crash recovery; no CancelRun/priority interrupt, no event queue |
| 0007 | PARTIAL | no RuntimeDispatchers, per-project mutex contexts, scope hierarchy; revocation epoch + treeless worker + ProjectLocker present |
| 0008 | PARTIAL | two loops; no JSON-schema arg validation, UNKNOWN_TOOL/INVALID_ARGUMENTS, budget termination |
| 0009 | PARTIAL | recovery classes, drive/park/recover real; no INTENT row before effects, idempotency_key unwritten, no deadline budget, tests fabricate states |
| 0010 | PARTIAL | projects create/registry/lock/settings; no archive/dormancy/export/import/templates |
| 0011 | PARTIAL | sessions, worker spawn, memory store; no failure budget, no orphan cancel, no FACT cap |
| 0012 | PARTIAL | flat intent store + status fn, tests only; no proposal gate, edges, acyclicity; not wired in prod |
| 0015 | PARTIAL | GitsemaKnowledgeIndex adapter+factory; no KnowledgeContextProvider, no prod caller, IndexingJob not executed |
| 0016 | PARTIAL | discovery+hash done; no adoption writer, no budget drop, sha256 not blob hash |
| 0017 | PARTIAL | MigrationRunner wired; pending_operations unused, no row_version CAS, no open-time recovery pass, no lifecycle states |
| 0018 | PARTIAL | SqliteCapabilityManager+RelPath, 23 tests; delegate() no scope containment; maxExerciseCount/budget not enforced |
| 0019 | PARTIAL | schema+dependency scheduling+recovery; no cycle check, no TARGETED/IMPLEMENTS/RETRY_OF writers, no retry loop |
| 0020 | PARTIAL | PolicyInferenceRouter real; kind/budget ignored, no idle unload, only Anthropic remote adapter wired |
| 0021 | PARTIAL | ModelAdapter/ToolCall.rawText real; key from env not vault, adapter bypasses EffectBroker, no egress_records writer |
| 0022 | PARTIAL | cookbook, GlobalModelRuntime LRU, llama.cpp, GBNF, resumable download; no mmap, KV const, count-based admission |
| 0023 | PARTIAL | AnthropicAdapter+PolicyInferenceRouter real; no approval dialog, egress_records no writer, no redaction |
| 0024 | PARTIAL | kernel types, schema, SqliteContentNodeStore in androidapp; not wired to runtime composition; no sensitivity enforcement |
| 0025 | PARTIAL | PromptAssembler sections/fit/TooBig/tool-descriptor fencing real; no PromptPackage provenance, no PrivacyFilter, no <context> sandboxing, provenance never written |
| 0026 | PARTIAL | SessionMemoryStore+tests; not wired to runtime; no delete/inspect, no redactor, no expiry |
| 0027 | IMPLEMENTED | MVP built, wired into the daemon and tested; requirement-by-requirement map in [RFC-0027 requirement map](#rfc-0027-requirement-map) below. Content-node provenance (`taint_source_node_id`, `prompt_provenance`) waits on RFC-0024/0025 plumbing |
| 0028 | PARTIAL | Budget type+split, causal depth, step ceiling; BudgetLedger interface only, no writers, budgetRemaining=null; causal depth 16 not 8 |
| 0029 | PARTIAL | AidosError/ErrorClass + RetryPolicy + INDETERMINATE real; isModelAudience test-only; most registry codes never emitted, ad hoc codes used |
| 0030 | PARTIAL | ToolBroker capability check+audit+preview; no JSON-schema validation, budget stub, approvalTier no callers |
| 0031 | PARTIAL | stdio/HTTP clients, lazy lifecycle, adoption, fencing, tests; no MCP_SERVER capability issuance at enable, no mcp_servers write path; DEFECT: validateHttpEndpoint loopback string-prefix match lets http://localhost.evil.example pass on DESKTOP |
| 0032 | PARTIAL | JGit GitTool 14 tests, Reconciliation, TreelessWorker CAS; no fetch/merge_ff/switch, no worker-ref pruning, per-op capabilities differ |
| 0034 | PARTIAL | RelPath + symlink-aware containment + read/write/list/search+diff preview; no delete/move/mkdir, no .git refusal, non-atomic writes |
| 0035 | PARTIAL | SqliteSecretsVault AES-GCM tests, Redactor; key in-memory, no keystore, redact() not on any real path, secret_accesses/redaction_events DDL only |
| 0036 | IMPLEMENTED | MVP built, wired into the daemon and tested; requirement-by-requirement map in [RFC-0036 requirement map](#rfc-0036-requirement-map) below |
| 0037 | NOT_IMPLEMENTED | metric_samples/crash_records DDL only; no logger, metrics, crash writer, diagnostic bundle, tests |
| 0038 | PARTIAL | CrashRecoveryTest 4 seeded boundaries, injection suite, RelPath tests; no shared fakes, no fixtures tree, no profile matrix, no concurrency tests |
| 0039 | PARTIAL | MigrationRunner read-only fallback; unknown fields dropped, no size limits, migration_history unwritten; SDK uses range not exact match |
| 0040 | PARTIAL | 3-DB paths, WAL pragmas, MigrationRunner v1; read-only result ignored in prod, no synchronous=FULL, no auto_vacuum, no forward migrations |
| 0042 | PARTIAL | EgressSecurityPolicy+DefaultEgressClient tested; egress_records no writer, Anthropic/MCP/downloads bypass it, no response size cap |
| 0043 | NOT_APPLICABLE | no plugin host in v1, matches RFC MVP; only enum value + unread installed_plugins table |
| 0044 | PARTIAL | TriggerCalculator, ScheduledJobManager, NotificationManager tested; NotificationBudget unused, no timer/WorkManager calls scheduler |
| 0045 | PARTIAL | DegradationLadder + event store, tests only, not called in prod; no admission check, resource_budgets DDL only |
| 0046 | IMPLEMENTED | ActorRef/ActorKind, AuditLog actor+device, DeviceIdentity store+tests, reserved signature column; PLUGIN unused as expected |
| 0047 | PARTIAL | ProjectType+applyTypeDefaults tested but not wired; create hardcodes generic; no templates (out of MVP |
| 0048 | PARTIAL | manual constructor-injection root + test; no PlatformAdapter, no testRuntime, no module-dependency test, no lifecycle types |
| 0049 | PARTIAL | PlatformProfile+descriptorsFor filter tested; daemon hardcodes DESKTOP, no SIDECAR, no [requirements], availability() unimplemented |
| 0050 | PARTIAL | foreground service, RuntimeServiceHost, ~75 tests; UI screens placeholders, no editor/voice/WorkManager; manifest requests external storage, RFC forbids |
| 0052 | PARTIAL | RuntimeClient iface, socket transport, replay, mock; DiffQueries stub, no import/export, no version negotiation, 23 CLI notWired |
| 0053 | PARTIAL | Reconciliation + GitRunReconciler tests, treeless worker; no fingerprint check on open, no CONFLICTED, checkout preview says discard but doesn't |
| 0054 | PARTIAL | user/project db split, nearest-first settings, UUIDv7, registry; workspace scope no-op, no audit row for scope violation, no .aidos gitignore entry |
| 0055 | PARTIAL | ProjectLock+stale detection, 0600 socket+token, interactive gate, env scrub; heartbeat never called, lock_breaks no writer, no release on shutdown |
| 0056 | PARTIAL | RetentionEngine age/LRU/batch tests; not wired; no classes/compaction/blob dedup/SECRET deletion; conflicts: soft-deletes content_nodes RFC says PERMANENT |
| 0057 | PARTIAL | RunSummaryComputer, spoken summary, voice approval tested; no UI uses them, no STT/TTS wired, approval_channel null |
| 0103 | PARTIAL | handshake, tokens, loopback HTTP, LRU, SDK client 38 tests, desktop+Android host; no LicenseAcceptance, queue-never-reject violated at 8 queued |
| 0105 | PARTIAL | phase1 pairing/groups IMPLEMENTED; phase2 virtual models+delegation IMPLEMENTED; phase3 split: planner+supervisors exist but emulated children, supervisors only in tests, no GGML_RPC CI; header stale 'Not implemented yet' |

## Not Accepted

| RFC | Status | Note |
|---|---|---|
| 0022 Inference Backend Architecture | Draft | Decisions D36-D44 settled; llama.cpp backend and ONNX backends (decisions, STT, vision) exist. Candidate for Accepted. |
| 0033 Shell | Draft | No code; post-MVP. |
| 0041 Export/Import | Draft | No code. |
| 0051 Desktop | Draft | No code matching this RFC; desktop engine app exists under RFC-0103. |
| 0051 LAN Engine Sharing | Proposed | No LAN/HTTPS sharing code; only AIDIN peer links (RFC-0105). |
| 0060 Plugin SDK | Draft | Not scheduled for v1. |
| 0104 Inference Locality | Proposed | Placement derivation and enforcement built (D47 settled). Not done: per-app limit toggle, Engine-wide limit UI, refusal audit, non-local placements. |
| 0106 Desktop Generative UI | Draft | No code. |
| 0100, 0101, 0102 | Draft | Architecture reviews. |

## RFC-0036 requirement map

Implemented 2026-11-10. Every normative requirement, where it is built, and the test that proves it. Code is under `agent/settings/src/commonMain/kotlin/dev/aidos/settings/` (`Settings.kt`, `SettingDescriptor.kt`, `SettingsStore.kt`, `ProjectSettingsLoader.kt`, `SettingsAudit.kt`, `SecretRef.kt`, `TomlParser.kt`); tests are `SettingsTest` and `Rfc0036RequirementsTest` in `agent/settings/src/jvmTest/`, plus `RuntimeCompositionRootTest` in `agent/daemon/`.

| Requirement (RFC section) | Code | Test |
|---|---|---|
| Settings declared once with type, default, range, scope class; no untyped store (Design, MVP 1) | `Settings.all`, `SettingDescriptor`, codecs | `every declared setting has a key, default, scope class, and codec` |
| A SECURITY setting must name a most restrictive value (Fail closed) | `SettingBuilder.build` | `a SECURITY descriptor must name a most restrictive value` |
| Nearest-first resolution session, project, workspace, user, default (Resolution) | `SettingsStore.resolve` | `project scope overrides user scope...`, `user scope overrides the default` |
| Origin reported with file and line, `overriddenBy` set on a refusal (Resolution) | `Resolved`, `ProjectEntry.line`, `TomlParser.lines` | `a project value reports the exact file and line it came from`, `a project setting a SECURITY key is reported...` |
| SECURITY/SPEND user-scope only; project attempt is a visible error with file and line, not applied (Scope classes, Security 1, MVP 3) | `ProjectSettingsLoader.load`, `SettingsStore.resolve`, `SettingsWriter.writeProject` | `a project setting a SECURITY key is reported with file and line...`, `a SPEND key in a project file is refused too`, `SettingsWriter rejects...` |
| PROJECT_SAFE not settable from a session; PREFERENCE is (Scope classes table) | `SettingsStore.resolve` | `a session may override a PREFERENCE but not PROJECT_SAFE, SECURITY or SPEND` |
| Invalid value falls back to the default, not the next scope (Fail closed) | `SettingsStore.resolve` | `an invalid project value resolves to the default, not to the user's value`, `range violation falls through to default` |
| Invalid SECURITY value fails closed to the most restrictive value (Fail closed, Security 3) | `SettingsStore.resolve` | `invalid SECURITY value fails closed to mostRestrictive` |
| Rejection reported with file and line (Fail closed) | `ProjectSettingsLoader.load` | `an invalid project value resolves to the default...` |
| Unknown key warns, is preserved, never interpreted (Fail closed, Security 6) | `ProjectSettingsLoader.load` (cache keeps it, snapshot omits it) | `an unknown key warns, is kept in the cache and never resolves` |
| SECURITY/SPEND changes audited with old value, new value, actor (Security 5, MVP 4) | `SettingsWriter.writeUser`, `UserDbSettingsAudit`, table `settings_audit` in `schema/user.sql` | `a SECURITY change is audited...`, `a SPEND change is audited and a PREFERENCE change is not`, `a refused project write is audited` |
| Refused project attempt is audited once, not on every reload (Security 1) | `UserDbSettingsAudit.record` | `reloading an unchanged offending file does not append the same violation again` |
| `secret_ref` indirection; a bare credential is rejected; the secret is resolved at use and never stored, logged or audited (Settings are not secrets, MVP 5) | `SecretRef`, `SecretRefCodec`, `Resolved.resolveSecret` | the two `secret_ref` tests |
| `aidos.toml` parsed with per-line errors, trailing comments handled (MVP 6) | `TomlParser` | `toml parses...`, `toml strips trailing comments but not a hash inside a string` |
| Project scope resolves from Git-tracked `aidos.toml`, SQLite row is a cache; session overrides in memory only (Data Model) | `ProjectSettingsLoader.cache`, `SettingsStore.resolve` | `the state db cache resolves when no snapshot is supplied`, `PROJECT_SAFE accepts a project value and the file replaces the cache on reload` |
| Wired into the running engine: the daemon loads `aidos.toml` when it builds an executor, reports problems, and resolves `routing.remote_egress` against it (Motivation: a cloned repo must not change egress) | `RuntimeCompositionRoot.loadProjectSettings` | `drive() reads aidos toml, reports and audits a project's attempt...`, `resolveEgressPolicy ignores a project snapshot that carries a SECURITY value` |
| Migration: renamed key aliased with a warning, removed key kept but ignored with a warning (Migration) | `Settings.renamedKeys/removedKeys`, `ProjectSettingsLoader` | the three migration tests |
| Values written to the store are validated; SQL is parameterised | `SettingsWriter`, `SettingsStore.readRow` | `writeUser rejects a value the codec rejects...`, `writing the same user key twice leaves one row` |

Not built, and out of the RFC's own MVP ("Not in MVP"): a settings UI beyond the CLI, workspace scope (single implicit workspace), dynamic reconfiguration without restart, and the one-time user prompt when a default for a SECURITY setting changes (no SECURITY default has changed yet, so there is nothing to prompt about). The RFC's Future Work items (import diff view, policy profiles) are likewise not built.

Known limits that do not block the MVP: only `routing.remote_egress` and `trust.untrusted_paths` are consumed through the project-aware path in production today; the other declared settings have no production reader yet (that is those RFCs' work, e.g. 0056 `retention.aged_days`; `trust.untrusted_paths` is now read by RFC-0027). The setting audit is in `user.db` (`settings_audit`) rather than a project's `audit_log`, because a user-scope change belongs to no project.

## RFC-0027 requirement map

Implemented 2026-11-10. Code: `agent/capability` (`TaintAttenuation.kt`, `SqliteCapabilityManager.validate`), `agent/filesystem` (`PathTrustPolicy.kt`, `FilesystemTool`), `agent/git` (`GitTool`), `agent/executor` (`AgentLoopTaskRunner`, `SqliteExecutor`), `agent/prompt` (`PromptAssembler`), `agent/routing` (`PolicyInferenceRouter`), `agent/settings` (`Settings.trustUntrustedPaths`), `agent/daemon` (`RuntimeCompositionRoot.resolveUntrustedPaths`), kernel `DenialReason.TAINT_REQUIRES_APPROVAL`. Tests: `TaintEscalationEndToEndTest` (executor), `TrustLabelTest` (filesystem), `CapabilityTest`, `ContextSandboxTest` (prompt), `PolicyInferenceRouterTest`, `RuntimeCompositionRootTest`.

| Requirement (RFC section) | Code | Test |
|---|---|---|
| Trust assigned by origin, automatically: project file reads and git output are PROJECT, files under `untrusted_paths` are UNTRUSTED, network/MCP output UNTRUSTED, runtime-authored text TRUSTED (Trust levels) | `PathTrustPolicy.classify`, `FilesystemTool` read/list/search, `GitTool.ok`, `HttpTool`, `McpTool` | `ordinary project reads are PROJECT, which never attenuates`, `files under default untrusted paths are UNTRUSTED at any depth`, `search is UNTRUSTED when any match came from an untrusted path`, `write confirmation is runtime-authored and stays TRUSTED` |
| Default `untrusted_paths` for dependency directories (MVP 6) | `Settings.trustUntrustedPaths` default | `resolveUntrustedPaths defaults to the RFC-0027 dependency directories` |
| `untrusted_paths` read through the RFC-0036 layer; a project can only add, never shrink the floor (Settings, RFC-0036 PROJECT_SAFE) | `RuntimeCompositionRoot.resolveUntrustedPaths`, fed to `FilesystemTool` when the executor is built | `a project can add untrusted paths but never shrink the floor`, `the user may narrow the list in their own settings` |
| Run taint is the max over results, monotonic, recorded per Run, never carried into another Run (Taint propagation, MVP 2) | `AgentLoopTaskRunner.finishToolCall`, `runs.taint_level` | `reading a vendored file then attempting an unsafe effect parks and names the file` (taint UNTRUSTED), `approval is single-use, grants nothing, and leaves the run tainted` |
| Only UNTRUSTED attenuates; PROJECT and TRUSTED never do (Authority attenuation) | `SqliteCapabilityManager.validate`, `PolicyInferenceRouter` | `validate attenuates only an UNTRUSTED run - PROJECT never attenuates`, `PROJECT-tainted run (read ordinary project files) is not attenuated`, `reading ordinary project files never attenuates` |
| Attenuation by effect class: read, in-project mutate and notify allowed; out-of-project mutate and secrets denied; egress, shell and UNSAFE effects need approval per call (Authority attenuation table, MVP 3) | `TaintAttenuation.decide` | `taint attenuation follows the RFC-0027 effect table` |
| Escalation names the specific tainting content and publishes a `CapabilityRequested` event with the taint reason (Escalation, MVP 4) | `AgentLoopTaskRunner.taintSourceDescription`, `ParkRequest.escalationMessage`, `SqliteExecutor` | `reading a vendored file then attempting an unsafe effect parks and names the file` |
| Approval is a single-use exercise, not a capability upgrade; the Run remains tainted (Escalation) | `consumeTaintApproval`, `SqliteExecutor.resolveToolCallApproval` | `approval is single-use, grants nothing, and leaves the run tainted`, `denying the escalation fails the run and nothing unsafe runs` |
| Delimiter escaping in `<context>` sandboxing, moved into MVP (Interaction with structural sandboxing, MVP 5) | `PromptAssembler.wrapContext`, `escapeContextContent`, system prompt statement | `untrusted and project tool results are wrapped in context with source and trust`, `a closing delimiter inside content cannot end the sandbox`, `runtime-authored and denied results are not wrapped`, `system prompt states that context content is never instructions` |
| No model-written summary carries taint across Runs (Taint propagation, D32) | none needed: a new Run starts with a fresh transcript | n/a |

Decisions where the code departs from the RFC text:

- **Shell and NETWORK_EGRESS need approval, not denial.** The table says egress and unsafe effects ask per call. The old blunt permission list denied them outright.
- **Git output is PROJECT.** Repository history is "present in the project's Git history", so it never attenuates.
- **Model output is not a taint source.** It is a function of its input; treating its arguments as untrusted is what schema validation is for (RFC-0008). Counting it as taint would taint every Run at its first response.
- **Patterns match at any depth** unless they start with a slash, so `node_modules/**` also covers `web/node_modules/x`.

Not built: (a) `runs.taint_source_node_id`, `prompt_provenance.trust_level` and `content_nodes` trust writes need the content-node and provenance plumbing of RFC-0024 and RFC-0025, which is not wired into the runtime; today the tainting content is named from the tool-call row (tool and path). (b) The runner does not yet feed knowledge-engine `ContextItem`s into a prompt, so there is no context-item taint to compute; the assembler already labels them PROJECT and sandboxes them. (c) Worker creation with a wider scope than its parent is not attenuated (no scope comparison exists at validation time). (d) A symlink inside the project that points into an untrusted directory is not followed by the path match. (e) The one `PreviewRecorded` row the RFC mentions for tainted in-project mutation is the broker's existing preview step, which runs for every `Mutate`. Out of the RFC's own MVP: configurable attenuation policy, per-token influence tracking, taint visualisation beyond the escalation prompt, injection-pattern detection.
