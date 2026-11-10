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
| 0010 | PARTIAL | projects create/registry/lock/settings; export/import built (RFC-0041); no archive/dormancy/templates |
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
| 0036 | IMPLEMENTED | MVP built, wired into the daemon and tested; requirement-by-requirement map in [RFC-0036 requirement map](#rfc-0036-requirement-map) below |
| 0037 | NOT_IMPLEMENTED | metric_samples/crash_records DDL only; no logger, metrics, crash writer, diagnostic bundle, tests |
| 0038 | PARTIAL | CrashRecoveryTest 4 seeded boundaries, injection suite, RelPath tests; no shared fakes, no fixtures tree, no profile matrix, no concurrency tests |
| 0039 | PARTIAL | MigrationRunner read-only fallback; unknown fields dropped, no size limits, migration_history unwritten; SDK uses range not exact match |
| 0040 | PARTIAL | 3-DB paths, WAL pragmas, MigrationRunner v1; read-only result ignored in prod, no synchronous=FULL, no auto_vacuum, no forward migrations |
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

Known limits that do not block the MVP: only `routing.remote_egress` is consumed through the project-aware path in production today; the other declared settings have no production reader yet except the `retention.*` ones, which RFC-0056 now reads (that is those RFCs' work, e.g. 0027 `trust.untrusted_paths`). The setting audit is in `user.db` (`settings_audit`) rather than a project's `audit_log`, because a user-scope change belongs to no project.

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
