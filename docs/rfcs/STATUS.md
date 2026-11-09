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
| 0027 | PARTIAL | Trust.kt, monotonic taint, capability denial real; fs/git tools hardcode TRUSTED so file reads never taint; trust.untrusted_paths unused; no escalation/approval flow |
| 0028 | PARTIAL | Budget type+split, causal depth, step ceiling; BudgetLedger interface only, no writers, budgetRemaining=null; causal depth 16 not 8 |
| 0029 | PARTIAL | AidosError/ErrorClass + RetryPolicy + INDETERMINATE real; isModelAudience test-only; most registry codes never emitted, ad hoc codes used |
| 0030 | PARTIAL | ToolBroker capability check+audit+preview; no JSON-schema validation, budget stub, approvalTier no callers |
| 0031 | PARTIAL | stdio/HTTP clients, lazy lifecycle, adoption, fencing, tests; no MCP_SERVER capability issuance at enable, no mcp_servers write path; DEFECT: validateHttpEndpoint loopback string-prefix match lets http://localhost.evil.example pass on DESKTOP |
| 0032 | PARTIAL | JGit GitTool 14 tests, Reconciliation, TreelessWorker CAS; no fetch/merge_ff/switch, no worker-ref pruning, per-op capabilities differ |
| 0034 | PARTIAL | RelPath + symlink-aware containment + read/write/list/search+diff preview; no delete/move/mkdir, no .git refusal, non-atomic writes |
| 0035 | PARTIAL | SqliteSecretsVault AES-GCM tests, Redactor; key in-memory, no keystore, redact() not on any real path, secret_accesses/redaction_events DDL only |
| 0036 | PARTIAL | typed catalogue, resolve, TOML parser, tests; no caller loads aidos.toml, UNKNOWN_KEY unused, no audit rows; SQL by interpolation |
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
