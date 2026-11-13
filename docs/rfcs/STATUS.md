# RFC implementation status

Audited 2026-11-09 against the code (not against status lines or milestone checkmarks). One read-only audit per Accepted RFC; test files were read, not run.

**Accepted** (the status in each RFC) means the design is approved. This table says how much of it exists in code. `IMPLEMENTED` = the RFC's MVP is built and tested; `PARTIAL` = some of it is built and tested, gaps listed; `NOT_IMPLEMENTED` = schema/DDL at most; `NOT_APPLICABLE` = the RFC's MVP is a decision, not code.

Not audited: RFC-0000 Vision, 0001 Principles, 0099 Roadmap (documents, not implementable), 0013/0014 (Superseded), 0100-0102 (reviews). Draft/Proposed RFCs are listed at the end.

| RFC | Verdict | Built / missing |
|---|---|---|
| 0002 | IMPLEMENTED | executor/capability/audit/lock/identity/api real; BudgetLedger with reserve/settle/release/remaining; project archive and delete operations |
| 0003 | PARTIAL | capability, audit, redactor, scrubbed MCP env, socket auth real; vault wired (RFC-0035); audit gaps |
| 0004 | PARTIAL | events table+EventStore+causal depth, payload redacted at publish (RFC-0035); no category/visibility/subscribe-authorization |
| 0005 | PARTIAL | event wake + causal depth done; MOBILE deterministic gate and admission policy missing |
| 0006 | PARTIAL | RunState, continuations, crash recovery; no CancelRun/priority interrupt, no event queue |
| 0007 | PARTIAL | no RuntimeDispatchers, per-project mutex contexts, scope hierarchy; revocation epoch + treeless worker + ProjectLocker present |
| 0008 | IMPLEMENTED | two loops; JSON-schema arg validation, INVALID_ARGUMENTS errors, budget termination condition all built and tested |
| 0009 | PARTIAL | recovery classes, drive/park/recover real; no INTENT row before effects, idempotency_key unwritten, no deadline budget, tests fabricate states |
| 0010 | PARTIAL | projects create/registry/lock/settings; export/import built (RFC-0041); no archive/dormancy/templates |
| 0011 | PARTIAL | sessions, worker spawn, memory store; no failure budget, no orphan cancel, no FACT cap |
| 0012 | PARTIAL | flat intent store + status fn, tests only; no proposal gate, edges, acyclicity; not wired in prod |
| 0015 | PARTIAL | GitsemaKnowledgeIndex adapter+factory; no KnowledgeContextProvider, no prod caller, IndexingJob not executed |
| 0016 | PARTIAL | discovery+hash done; no adoption writer, no budget drop, sha256 not blob hash |
| 0017 | PARTIAL | MigrationRunner wired; pending_operations unused, no row_version CAS, no open-time recovery pass, no lifecycle states |
| 0018 | IMPLEMENTED | SqliteCapabilityManager+RelPath, 23+ tests; delegate() scope containment checks and attenuation; maxExerciseCount enforcement with budget checks |
| 0019 | PARTIAL | schema+dependency scheduling+recovery; no cycle check, no TARGETED/IMPLEMENTS/RETRY_OF writers, no retry loop |
| 0020 | IMPLEMENTED | PolicyInferenceRouter with budget-aware selection, idle model unload with timeout, RemoteAdapterPipeline skeleton; Anthropic remote adapter wired |
| 0021 | PARTIAL | ModelAdapter/ToolCall.rawText real; key from the vault (env fallback), adapter bypasses EffectBroker, no egress_records writer |
| 0022 | PARTIAL | cookbook, GlobalModelRuntime LRU, llama.cpp, GBNF, resumable download; no mmap, KV const, count-based admission |
| 0023 | PARTIAL | AnthropicAdapter+PolicyInferenceRouter real; no approval dialog, egress_records no writer, no redaction |
| 0024 | PARTIAL | kernel types, schema, SqliteContentNodeStore in androidapp; not wired to runtime composition; no sensitivity enforcement |
| 0025 | PARTIAL | PromptAssembler sections/fit/TooBig/tool-descriptor fencing real; no PromptPackage provenance, no PrivacyFilter, no <context> sandboxing, provenance never written |
| 0026 | PARTIAL | SessionMemoryStore+tests; not wired to runtime; no delete/inspect, no redactor, no expiry |
| 0027 | IMPLEMENTED | MVP built, wired into the daemon and tested; requirement-by-requirement map in [RFC-0027 requirement map](#rfc-0027-requirement-map) below. Content-node provenance (`taint_source_node_id`, `prompt_provenance`) waits on RFC-0024/0025 plumbing |
| 0028 | PARTIAL | Budget type+split, causal depth, step ceiling; BudgetLedger interface only, no writers, budgetRemaining=null; causal depth 16 not 8 |
| 0029 | PARTIAL | AidosError/ErrorClass + RetryPolicy + INDETERMINATE real; isModelAudience test-only; most registry codes never emitted, ad hoc codes used |
| 0030 | IMPLEMENTED | ToolBroker capability check+audit+preview; JSON-schema validation MVP (type, required, properties, constraints); budget tracking foundation |
| 0031 | PARTIAL | stdio/HTTP clients, lazy lifecycle, adoption, fencing, tests; no MCP_SERVER capability issuance at enable, no mcp_servers write path; Binder transport for Android is future work (RFC-0107 Phase 2) |
| 0032 | PARTIAL | JGit GitTool 14 tests, Reconciliation, TreelessWorker CAS; no fetch/merge_ff/switch, no worker-ref pruning, per-op capabilities differ |
| 0034 | IMPLEMENTED | RelPath + symlink-aware containment + read/write/list/search+diff preview; delete/move/mkdir operations with .git/ protection; atomic writes for mkdir |
| 0035 | IMPLEMENTED | MVP built, wired into the daemon and the CLI, tested; requirement-by-requirement map in [RFC-0035 requirement map](#rfc-0035-requirement-map) below. Android Keystore is not built (the vault is JVM-only and the Android host does not run it yet) |
| 0036 | IMPLEMENTED | MVP built, wired into the daemon and tested; requirement-by-requirement map in [RFC-0036 requirement map](#rfc-0036-requirement-map) below |
| 0037 | NOT_IMPLEMENTED | metric_samples/crash_records DDL only; no logger, metrics, crash writer, diagnostic bundle, tests |
| 0038 | PARTIAL | CrashRecoveryTest 4 seeded boundaries, injection suite, RelPath tests; no shared fakes, no fixtures tree, no profile matrix, no concurrency tests |
| 0039 | PARTIAL | MigrationRunner read-only fallback; unknown fields dropped, no size limits, migration_history unwritten; SDK uses range not exact match |
| 0040 | IMPLEMENTED | 3-DB paths, WAL pragmas, PRAGMA synchronous=NORMAL, auto_vacuum=INCREMENTAL, wal_autocheckpoint=1000; MigrationRunner v1; forward migrations foundation |
| 0041 | IMPLEMENTED | MVP built (full export, manifest, encryption, signing, verified import, migration, conflict policies, CLI) and tested; requirement map in [RFC-0041 requirement map](#rfc-0041-requirement-map) below |
| 0042 | PARTIAL | EgressSecurityPolicy+DefaultEgressClient tested; egress_records no writer, Anthropic/MCP/downloads bypass it, no response size cap |
| 0043 | NOT_APPLICABLE | no plugin host in v1, matches RFC MVP; only enum value + unread installed_plugins table |
| 0044 | PARTIAL | TriggerCalculator, ScheduledJobManager, NotificationManager tested; NotificationBudget unused, no timer/WorkManager calls scheduler |
| 0045 | PARTIAL | DegradationLadder + event store, tests only, not called in prod; no admission check, resource_budgets DDL only |
| 0046 | IMPLEMENTED | ActorRef/ActorKind, AuditLog actor+device, DeviceIdentity store+tests, reserved signature column; PLUGIN unused as expected |
| 0047 | PARTIAL | ProjectType+applyTypeDefaults tested but not wired; create hardcodes generic; no templates (out of MVP |
| 0048 | PARTIAL | manual constructor-injection root + test; no PlatformAdapter, no testRuntime, no module-dependency test, no lifecycle types |
| 0049 | PARTIAL | PlatformProfile+descriptorsFor filter tested; daemon hardcodes DESKTOP, no SIDECAR, no [requirements], availability() unimplemented |
| 0050 | PARTIAL | foreground service, RuntimeServiceHost, ~75 tests; UI screens placeholders, no editor/voice/WorkManager; manifest requests external storage, RFC forbids |
| 0052 | PARTIAL | RuntimeClient iface, socket transport, replay, mock; DiffQueries stub, no version negotiation; export/import/keygen are local CLI commands (RFC-0041), not RuntimeClient calls, 23 CLI notWired |
| 0053 | PARTIAL | Reconciliation + GitRunReconciler tests, treeless worker; no fingerprint check on open, no CONFLICTED, checkout preview says discard but doesn't |
| 0054 | PARTIAL | user/project db split, nearest-first settings, UUIDv7, registry; workspace scope no-op, no audit row for scope violation, no .aidos gitignore entry |
| 0055 | PARTIAL | ProjectLock+stale detection, 0600 socket+token, interactive gate, env scrub; heartbeat never called, lock_breaks no writer, no release on shutdown |
| 0056 | IMPLEMENTED | MVP built, wired into the daemon and tested; requirement-by-requirement map in [RFC-0056 requirement map](#rfc-0056-requirement-map) below |
| 0057 | PARTIAL | RunSummaryComputer, spoken summary, voice approval tested; no UI uses them, no STT/TTS wired, approval_channel null |
| 0103 | PARTIAL | handshake, tokens, loopback HTTP, LRU, SDK client 38 tests, desktop+Android host; no LicenseAcceptance, queue-never-reject violated at 8 queued |
| 0105 | PARTIAL | phase1 pairing/groups IMPLEMENTED; phase2 virtual models+delegation IMPLEMENTED; phase3 split: planner+supervisors exist but emulated children, supervisors only in tests, no GGML_RPC CI; header stale 'Not implemented yet' |

## Not Accepted

| RFC | Status | Note |
|---|---|---|
| 0022 Inference Backend Architecture | Draft | Decisions D36-D44 settled; llama.cpp backend and ONNX backends (decisions, STT, vision) exist. Candidate for Accepted. |
| 0033 Shell | Draft | No code; post-MVP. |
| 0051 Desktop | Draft | No code matching this RFC; desktop engine app exists under RFC-0103. |
| 0051 LAN Engine Sharing | Proposed | No LAN/HTTPS sharing code; only AIDIN peer links (RFC-0105). |
| 0060 Plugin SDK | Draft | Not scheduled for v1. |
| 0104 Inference Locality | Proposed | Placement derivation and enforcement built (D47 settled). Not done: per-app limit toggle, Engine-wide limit UI, refusal audit, non-local placements. |
| 0106 Desktop Generative UI | Draft | No code. |
| 0107 Remote Agent Hosting | Draft | Phase 1 (router adoption of the agent loop) built; peer agent endpoints, `agent` scope, tool relay not built. |
| 0108 AIDIN Remote Routes | Draft | Phase 3a built: standalone `agent/ssh-tunnel` library (JVM; sshj and MINA transports pass one shared suite against an in-process server). Phase 4a: sshj transport written for Android but never run on a device or emulator. Not built: route hints, remote-access switch, Engine tunnel manager and key page, Android keystore-backed `KeyProvider`. |
| 0109 Capture Device Protocol (ACP) | Draft | Planning only; no code. Open BLE protocol for voice buttons (the AI cube), user-configured button bindings and trust, modular Engine receiving side. |
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

Known limits that do not block the MVP: only `routing.remote_egress` and `trust.untrusted_paths` (RFC-0027) are consumed through the project-aware path in production today, plus the `retention.*` settings that RFC-0056 reads; the other declared settings have no production reader yet. The setting audit is in `user.db` (`settings_audit`) rather than a project's `audit_log`, because a user-scope change belongs to no project.

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

## RFC-0056 requirement map

Implemented 2026-11-10. Code is in `agent/retention/src/jvmMain/kotlin/dev/aidos/retention/` (`RetentionModel.kt`, `RetentionPolicyResolver.kt`, `Compactor.kt`, `BlobStore.kt`, `StoragePressure.kt`), the executor seam `LargeOutputStore` in `agent/executor/`, the four `retention.*` settings in `agent/settings/`, and the daemon wiring in `agent/daemon/` (`RetentionMaintenance.kt`, `RuntimeCompositionRoot`, `SqliteRunExecutor`). Tests: `Rfc0056RequirementsTest` (retention), `RetentionWiringTest` (daemon), `AgentLoopTaskRunnerTest` (executor).

The earlier `RetentionEngine` (90-day soft delete of `content_nodes`, 512 MB LRU cap, never called) is gone: it deleted PERMANENT records, which the RFC forbids.

| Requirement (RFC section) | Code | Test |
|---|---|---|
| MVP 1: retention classes and the default policy table | `RetentionClass`, `RetainedObject`, `RetentionPolicy` | `R1 the default policy table classifies...`, `R1 default windows are 30 days...` |
| Windows are user-adjustable per project, defaults stated (User control) | settings `retention.aged_days`, `tool_result_days`, `soft_limit_mb`, `never_compact`; `retention_policy` rows; `RetentionPolicyResolver` | `R8 retention reads its windows through the settings layer...`, `R8 retention_policy rows override per object...`, `R8 an invalid retention value falls back...` |
| "Never compact this project" (User control) | `RetentionPolicy.neverCompact`, null `perObjectDays` | `R3 never_compact for the project, or a null per-object window...` |
| MVP 2: compaction of Attempt snapshots, prompt packages and event payloads with head, tail, hash (Compaction) | `Compactor.compact`, `Compactor.compactedJson` | `R2 an old attempt snapshot, prompt package and event payload are compacted...`, `R2 event headers survive compaction...`, `R2 head and tail never split a surrogate pair` |
| Idempotent; never grows a value; young data untouched (Compaction) | `Compactor` selection, `retained >= original` guard | `R2 compaction is idempotent`, `R2 a payload too small to shrink...` |
| Tool results over 64KB age out after 7 days (table) | `Compactor` per-row cutoff | `R2 a tool result over 64KB is compacted after 7 days...` |
| Bounded batches, cancellation checks, background (Compaction) | `RetentionPolicy.batchSize/maxBatchesPerPass`, `yield()` per row, `RetentionMaintenance` on `Dispatchers.IO` | `R4 a pass is bounded by batches...`, `R4 cancelling mid-pass...` |
| MVP 5, Security 2: every compaction is recorded with the original hash | `compactions` insert in the same transaction as the update | `R5 every compaction writes a compactions row...` |
| Security 1, never-deleted list: PERMANENT records, audit log and capability history are never touched | `Compactor` writes only the four payload columns | `R3 compaction never touches PERMANENT records...` (also proves `content_nodes` are not soft-deleted) |
| Live Runs are not compacted from under themselves | `LIVE_RUN_STATES` filters | `R3 attempts of a Run that is still live...` |
| MVP 3: content-addressed blobs under `.aidos/blobs/<hash>` with reference counts (Deduplication) | `BlobStore`, `blob_refs` | `R6 identical content is stored once...`, `R6 the file is deleted when the last reference is released...`, `R6 a corrupted blob is never returned` |
| Dedup is used by the running agent loop | `LargeOutputStore` seam in `AgentLoopTaskRunner`, `BlobStore` injected by `RuntimeCompositionRoot` | `R6 large outputs are externalized...`, executor `a large tool result is stored by reference and still reaches the model...` |
| Deduplicated content is reclaimed when its Attempt is compacted | `Compactor.apply` releases the reference after commit | `R6 compacting an attempt whose output is a blob reference...` |
| EPHEMERAL: temp files and interrupted writes cleaned on startup | `BlobStore.sweep`, run by the first maintenance pass | `R6 startup sweep removes interrupted writes...` |
| MVP 4: Stage 1 (over soft limit, compact AGED older than 7 days) | `StoragePressureMonitor`, `RetentionService.maintain` | `R7 stage assessment orders...`, `R7 stage 1 compacts content older than 7 days...`, `R7 under the soft limit nothing inside the window is compacted` |
| MVP 4: Stage 3 (device free space under 250 MB stops new Runs, user told) | `StoragePressureAdmission` in `SqliteRunExecutor.send` | daemon `Stage 3 refuses to start a Run...`, `with enough free space a Run is admitted` |
| Wired into the running engine; settings come from `aidos.toml` (RFC-0036 consumer) | `RuntimeCompositionRoot.drive` calls `RetentionMaintenance.schedule` | daemon `drive schedules compaction, and aidos toml's retention aged_days is what decides what is old`, `a second drive inside the interval does not start another pass` |

Not built, and out of the RFC's own MVP ("Not in MVP"): the per-project retention UI, Stage 2 DERIVED eviction (its model-weight half conflicts with RFC-0103's manual-only policy, as the RFC's own amendment says), verification against Git before compaction, and cold export. Not built and not named in the MVP list: deleting SECRET-labelled content on detection (RFC-0035 write-time redaction is what exists), and offering an export at Stage 3 (RFC-0041 now exists as `aidos export`, but retention does not call it). Known limits: compaction only runs for projects the daemon drives a Run for; free space is measured where the project lives, not on the Android internal-storage API; the mobile hosts do not run it yet because they do not run the daemon composition root.

## RFC-0041 requirement map

Implemented 2026-11-10 (MVP scope). Code: `agent/portability/src/jvmMain/kotlin/dev/aidos/portability/` (`ProjectExporter`, `ProjectImporter`, `ArchiveFormat`, `ArchiveCrypto`, `PortabilityCli`, `ProjectFiles`); CLI wiring in `agent/cli` (`Main.kt`); `ProjectRegistry.isRegistered` in `agent/identity`. Tests: `PortabilityTest`, `PortabilityCliTest` (31). Deviations from the RFC's sketch are in the RFC's "Implementation Amendments".

| Requirement (RFC section) | Code | Test |
|---|---|---|
| MVP 1-2: full export as a ZIP with the whole project directory incl. Git history, state db and blobs | `ProjectExporter.export`, `collect` | `full export has metadata, manifest, git head and excludes lock and derived index` |
| Export while the runtime has the project open (Export Process: consistent read) | `Sqlite.snapshot` (`VACUUM INTO`) | `export works beside an open database and audits itself in the source project` |
| Export never includes secrets or anything outside the project (Security) | `collect` only walks the project root | `nothing outside the project directory is ever exported` |
| MVP 3: metadata and manifest with per-file SHA-256 and total checksum | `ExportMetadata`, `ExportManifest`, `ArchiveFormat.totalChecksum` | `full export has metadata...`, `a modified payload file is rejected...`, `an added or removed file is rejected` |
| MVP 4: password encryption, metadata hidden | `ArchiveCrypto.encrypt/decrypt` | `encrypted export hides everything and round trips with the password`, `crypto streams survive chunk boundaries and empty input` |
| Wrong password, tampering, truncation and reordering fail closed | AEAD per chunk, final flag in AAD | `wrong or missing password and tampering are refused`, `chunk reordering is detected` |
| MVP 5: Ed25519 signing and verification, signer identity | `ArchiveCrypto.sign/verify/fingerprint`, `ProjectImporter.verifySignature` | `signed export verifies and reports the signer`, `untrusted signer and a tampered manifest are refused`, `key encoding round trips` |
| Unsigned archives can be refused; a stripped signature is detected | `ImportOptions.requireSignature/trustedSigners`, metadata `signed` | `unsigned archive is refused when a signature is required...`, `untrusted signer...` |
| MVP 6: integrity verification before anything is placed | `ProjectImporter.verifyManifest`, staging directory | `a modified payload file is rejected and nothing is placed`, `import leaves no staging directory behind` |
| Validation of archive paths and size (Security 7) | `extract` (zip-slip, absolute paths, stray entries, byte cap) | `zip-slip and absolute entry names are rejected`, `an entry outside project and the metadata files is rejected`, `an archive that expands past the limit is rejected` |
| Format version compatibility (Import 2) | `ArchiveFormat.isSupported` | `a garbage file and an unsupported format version are rejected` |
| MVP 7: schema check and migration | `MigrationRunner` via `AidosStorage.openProject`, `Sqlite.schemaVersion` | `a database newer than this runtime is refused`, round trip (schema v1 to v1) |
| Git history verified (Import 9) | `resolveGitHead`, `gitObjectPresent` | `a git HEAD that does not match the export is rejected`, round trip |
| MVP 8: conflict detection, default refuse; rename, replace | `ConflictPolicy`, `ProjectImporter.import` | `default policy refuses...`, `rename imports a copy with a new id...`, `replace moves the existing directory aside...` |
| Import registers the project and restores it faithfully (Import 10-13) | `ProjectRegistryPort`, `rewriteForThisDevice` | `round trip restores content, git, blobs and registers the project` |
| Imported authority is not inherited (RFC-0003, 0016, 0031 applied to Import) | `rewriteForThisDevice` revokes grants, bumps epoch, clears adoptions | `import revokes grants and adoptions and records a ProjectImported audit entry` |
| Audit trail of every export and import (Security 5) | `Sqlite.appendAudit` (`ProjectExported`, `ProjectImported`) | `export works beside an open database and audits itself...`, `import revokes grants...` |
| CLI `aidos export` / `aidos import` (Export/Import Process), local, no daemon | `PortabilityCli`, `Main.runPortability` | `export then import via the command line`, `second import is refused...`, `encrypted and signed round trip...`, `keygen refuses to overwrite and bad usage exits 2`, `exporting something that is not a project fails cleanly` |

Not built, and out of the RFC's own MVP ("Not included"): snapshot and selective export, streaming export, cloud backup, incremental export, project merging, compression tuning beyond `--compress`. Not built and not in the RFC: a UI on the desktop or Android apps (the Android host can call `ProjectExporter`/`ProjectImporter` but nothing does yet), signing-key storage in the vault (key files from `keygen` for now), `git fsck` on import, and an export offered by retention at Stage 3. Known limits: the audit entry in the exporting project is best-effort (a failure is returned as a warning, not an error, so a finished archive is never discarded); passwords never come from argv (environment variable or prompt).

## RFC-0035 requirement map

Implemented 2026-11-10 (MVP scope). Code: `agent/vault/src/jvmMain/kotlin/dev/aidos/vault/` (`SqliteSecretsVault`, `SecretsVault`, `KeyProviders`, `DesktopVault`, `Redactor`); daemon wiring in `agent/daemon/` (`SecretsWiring.kt`, `RuntimeCompositionRoot`, `RuntimeClientFactory`); the hooks in `agent/prompt` (`PromptAssembler`), `agent/executor` (`EventStore`, `AgentLoopTaskRunner`), `agent/memory` (`SessionMemoryStore`), `agent/git` (`GitTool`), `agent/portability` (`ProjectExporter`, `SigningKeyStore`), `agent/mcp-broker` (`McpSecretResolver.resolveFor`), `agent/capability` (`grant`); CLI in `agent/cli` (`SecretsCli.kt`). Tests: `Rfc0035RequirementsTest` and `VaultTest` (vault), `SecretsWiringTest` (daemon), `SecretsCliTest` (cli), `PortabilityCliTest`, `AgentLoopTaskRunnerTest`, `EventStoreReplayTest`, `ContextSandboxTest`, `SessionMemoryTest`, `GitToolTest`, `CapabilityTest`.

| Requirement (RFC section) | Code | Test |
|---|---|---|
| Vault is user scope at `~/.aidos/secrets/vault.db`; ciphertext only; key never in the database (Design, MVP 1) | `DesktopVault.open`, `SqliteSecretsVault` | `a secret stored in one process resolves in the next with a persisted key`, `the database file never contains the plaintext or the key` |
| Key held by the platform keystore: Secret Service, Keychain, DPAPI (Design table, MVP 1) | `SecretServiceKeyProvider`, `KeychainKeyProvider`, `DpapiKeyProvider`, `VaultKeyProviders.platformDefault` | the three provider tests (driven through a fake `CommandRunner`; no real keystore runs in CI), `platformDefault picks the keystore per OS...` |
| Passphrase-derived key fallback; the user is told plainly that protection is weaker (Design, MVP 1) | `PassphraseKeyProvider`, `OwnerOnlyFileKeyProvider`, `KeyProtection.plainDescription`, `OpenedVault.weakProtectionNotice`, the daemon start-up notice, `secrets set` note | `passphrase provider derives the same key, rejects a wrong passphrase...`, `owner-only file provider ... says its protection is weak`, `status reports protection and a weak keystore is called out when storing` |
| Ciphertext bound to its secret (hardening; not in the RFC) | AES-GCM AAD = secret id | `a ciphertext copied onto another secret fails to decrypt` |
| Values in `CharArray`, zeroed after use (Design, Security 7) | `store`/`rotate` zero the input; `resolve` returns a fresh array | `VaultTest` round-trip tests; `push ... zeroes the secret afterwards` |
| Secrets referenced, never embedded; `secret_ref` resolves at the point of use (Design, MVP 2) | `VaultMcpSecretResolver` (MCP spawn), `vaultAnthropicKey` (provider), `VaultGitCredentialSource` + `GitTool` (Git remotes), `VaultSettingsSecretResolver` (`secret:<name>` settings) | `a vault entry wins over an environment variable...`, `drive resolves the provider key from the vault...`, `git credentials come from the vault by host...`, `push asks the credential source...`, `a secret setting reference resolves from the vault...` |
| Per-consumer allow-list (`allowedConsumers`) (Design, `SecretEntry`) | `resolveFor`, `setAllowedConsumers`; anonymous `resolve` refused when a list exists | `an allow-list limits consumers and blocks anonymous resolve`, `a secret's allow-list is enforced per MCP server` |
| `secrets:read` names specific IDs, never a wildcard (Design, Security 4, MVP 6) | `SqliteCapabilityManager.grant` | `grant refuses a SECRETS_READ with no ids, a wildcard, or a non-secrets scope` |
| A tainted Run cannot resolve secrets (Security 6, MVP 6) | `SqliteCapabilityManager.validate` (RFC-0027), `SecretRequest.tainted` in `resolveFor` | `validate denies SECRETS_READ when run taint is UNTRUSTED`, `a tainted run cannot resolve a secret` |
| Redactor runs unconditionally at the boundaries: events, attempt snapshots, prompts, logs, memory, exports (Redactor, Security 2, MVP 3) | events: `EventStore.redactPayload` + `Redactor.redactJson`; attempts: `AgentLoopTaskRunner.redact`; prompts: `PromptAssembler.redact`; logs: `DaemonLog`; memory: `SessionMemoryStore.redact`; exports: `SecretScanner` | `payloadIsRedactedBeforeItIsWritten`, `output snapshot is passed through the injected redact function...`, `a redactor sees every text the model would receive`, `redactJson ...`, `a secret in a memory entry is redacted...`, `a file containing a credential is left out of the export...` |
| Known-value redaction: every vault value registered at load, exact match, `«redacted:name»` (Redactor, Security 3, MVP 3) | `SqliteSecretsVault` init/`store`/`rotate`/`registerAllWith`; per-Run `Redactor` in `buildExecutor` | `opening a vault registers every stored value with the redactor`, `a stored value is registered with the redactor, delete unregisters it` |
| Pattern detection: provider keys, JWTs, PEM, connection strings, credential assignments (Redactor, MVP 4) | `Redactor.PATTERNS` (Anthropic, OpenAI, GitHub, Hugging Face, AWS, Slack, Google, JWT, PEM, connection string, assignment) | `pattern detection covers common provider keys, JWTs, PEM blocks and connection strings` |
| Detections audited by kind and boundary, never the value (Security 5, MVP 7) | `RedactionSink`, `RedactionEventsSink` writes `redaction_events` | `detections are reported by kind and boundary, never by value`, `the sink records boundary and kind, never the value` |
| Resolution audited without the value (Data Model, MVP 7) | `auditSecretUse` writes an `audit_log` row `SecretResolved` (actor kind, consumer, secret name); `SecretAccessSink` for callers that have a capability | `drive resolves the provider key from the vault and audits it without the value`, `resolveFor audits the access without the value` |
| Content found to contain secrets is withheld from prompts, with a notice (Secrets found in content, MVP 5) | `AgentLoopTaskRunner.detectSecrets`, audit row `SecretContentWithheld` | `a tool result containing credentials is withheld from the transcript and the model` |
| Content labelled SECRET is excluded from exports (Secrets found in content) | `ProjectExporter` + `SecretScanner`, warning names the file and kinds | `a file containing a credential is left out of the export and named without its value` |
| Rotation keeps the reference; the old value stays filtered for a grace period (Rotation) | `SqliteSecretsVault.rotate`, `Redactor.retire` (24 h) | `rotation replaces the value, and the old value stays filtered for the grace period only` |
| Deletion fails consumers with `secrets.not_found`; expiry fails resolution (Rotation) | `delete`, `setExpiry`, `load` | `missing secret reports secrets not_found`, `an expired secret fails to resolve...`, `VaultTest` delete test |
| A secret store the user can operate (MVP 1) | `aidos secrets status/set/rotate/delete/list/expire/allow`; value from env, prompt or stdin, never argv; no command prints a value | `SecretsCliTest` |
| Export signing keys live in the vault, not key files (RFC-0041 follow-up) | `keygen --vault NAME`, `--sign vault:NAME`, `VaultSigningKeys` | `a signing key kept in the vault signs an export and never touches disk`, `vault signing keys round trip and refuse duplicates` |

Not built, and out of the RFC's own MVP ("Not in MVP"): expiry prompts in a UI (resolution does fail), the breach-response report, SSH key management. Not built and inside it, stated plainly:

- **Android Keystore.** The vault is a JVM-only module and the Android host does not run the daemon composition root, so nothing on Android opens the vault yet. The desktop key providers and the passphrase fallback are built. Engine's own Hugging Face token store is separate by RFC-0103 and is unchanged.
- **Real OS keystore in CI.** The Secret Service, Keychain and DPAPI providers are tested through a fake command runner. They have not been run against a real keystore in this environment; first run on each OS is Juha's.
- **`ContentNode` label.** RFC-0024 content nodes are not produced by any tool today, so "labelled SECRET" is implemented as the result being withheld and audited (`SecretContentWithheld`), not as a node flag. Deleting runtime-produced SECRET content (RFC-0056) is likewise not built.
- **`secret_accesses` rows.** That table requires a capability id with a foreign key, and the runtime's own resolutions (MCP spawn, provider key, Git push) have no capability. Those are recorded in `audit_log` instead. `SecretAccessSink` and the table are ready for the capability-bearing path when a session-facing resolution exists.
- **Memory entries** are redacted by `SessionMemoryStore`, but the daemon does not construct a `SessionMemoryStore` yet, so the boundary has no production caller.
- **Diagnostic bundles** do not exist; log lines the daemon prints go through `DaemonLog`.
- macOS: the Keychain tool takes the new key on its command line once per install (`security add-generic-password -w`); a local process listing could see it for that instant.
