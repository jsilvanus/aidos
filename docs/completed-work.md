# Completed work moved from PIPELINE

PIPELINE.md is the forward-looking roadmap. Items below were finished and were moved here on 2026-11-18 so PIPELINE shows only open work. The original text is preserved verbatim.

---

### 3. MCP (RFC-0031) — ✅ Complete

The client is real and speaks the protocol through the official Kotlin MCP SDK.
- **Wired into ToolBroker & RuntimeCompositionRoot — ✅ Complete.** Implemented `LazyMcpTool` and registered adopted operations in `RuntimeCompositionRoot` (supporting both user and project scopes) without eager process or network connection spawning on project load. Activation is deferred until tool execution, and tool outputs are strictly marked `TrustLevel.UNTRUSTED`. Verified with live agent execution in `McpToolBrokerIntegrationTest`.
- **User Adoption Flow — ✅ Complete.** Implemented `McpAdoptionService` to inspect server catalogs (tool names, descriptions, input schemas), adopt specific operations, and unadopt them, persisting records via `McpOperationAdoptionStore`.
- **Idle Lifecycle Manager — ✅ Complete.** Implemented `McpLifecycleManager` and idle release support in `LazyMcpTool` (`releaseIfIdle`, `inFlightCount`, `lastActivityMillis`, `idleTimeoutMillis`), wired into `RuntimeCompositionRoot`. Idle server connections are released without losing offline adopted descriptor catalogs or interrupting in-flight calls, and transparently re-activate on subsequent calls (`McpLifecycleManagerTest`).
- **Adoption-Hash Migration — ✅ Complete.** Implemented `McpDescriptorHash.normalizeSdkInputSchema` and `McpOperationAdoptionStore.migrateAdoptionHashes` (plus in-place row migration during `resolve`) to recompute canonical descriptor hashes from persisted `description` and `input_schema_json` across canonical/SDK `ToolSchema` upgrades without mass un-adoption (`McpOperationAdoptionStoreTest`).
- **Security Regression Suite & `<tool_descriptor>` Structural Sandbox Fencing — ✅ Complete.** Added `<tool_descriptor source="mcp:<serverId>" operation="<op>">` fencing and delimiter escaping in `PromptAssembler`, `SubjectKind.MCP_SERVER` capability validation/revocation enforcement in `SqliteCapabilityManager` and `CapabilityResolver.resolveForTool`, and the 7-test security regression suite in `McpSecurityRegressionTest`.
- **External / Non-Kotlin Interoperability Suite — ✅ Complete.** Added `McpExternalInteropTest` exercising both `stdio` and `Streamable HTTP` end-to-end against a non-Kotlin Python MCP server process (`external_mcp_server.py`) through `McpServerStore`, `McpAdoptionService`, `McpOperationAdoptionStore`, `McpServerActivator`, `LazyMcpTool`, and `McpLifecycleManager`.
- **`JsonRpc.kt` Cleanup — ✅ Complete.** Moved `JsonRpc.kt` out of `agent/mcp-core/src/jvmMain` into `agent/mcp-core/src/jvmTest`.
- **Activation write/capability gap closure — ✅ Complete.** Added user-scope `mcp_servers` write path (`McpServerRegistry` upsert/delete) and MCP-server capability issuance on adopted-server activation (`SubjectKind.MCP_SERVER`) with dedicated tests (`McpServerRegistryTest`, `McpCapabilityIssuanceTest`).

- **Upstream:** the SDK's `ToolSchema` models five keys with no catch-all, so unmodeled top-level
  JSON Schema keywords are dropped. RFC-0031's "Protocol layer" amendment records why that is
  tolerable. A passthrough fix should still be filed upstream.

### 4. RFC-0011 driver/worker orchestration — ✅ Complete

The design and implementation are in place across `schema`, `kernel`, `capability`, `executor`, and `daemon`.

- **Spawning (`WorkerSpawner`)**: a `COMPOSITE` task creates a worker session (`role = WORKER`,
  `parent_session_id` = driver's session ID), verifies driver holds `Permission.WORKER_CREATE`,
  delegates requested capabilities via `CapabilityManager.delegate()` with `Budget.split(ways)`, creates
  a child Run via `RunCreator.createForUserMessage` (brief as initial user message), and parks the composite
  task with `TaskResult.park(SuspendedOperation.ChildRun(childRunId, childSessionId), AWAITING_INPUT)`.
- **Driving the child and resuming the parent (`SqliteExecutor`)**: writing parent's continuation row
  precedes recursive synchronous child driving (`drive(childRunId)`). On child reaching any terminal state
  (normal completion, budget ceiling, or error), `resumeAwaitingParent(childRunId)` removes continuation,
  commits worker outcome summary via `WorkerCommitter`, sets composite parent task to `COMPLETED` (or cascades
  `FAILED`), transitions parent run back to `RUNNING` (or `FAILED`), and resumes parent execution.
- **`DEPENDS_ON` / `SKIPPED` traversal**: `pendingTasksFor` scans `tasks` in ordinal order and traverses
  `execution_edges`. A `FAILED` or `SKIPPED` prerequisite cascades `SKIPPED` to downstream tasks in the database;
  non-terminal prerequisites are skipped over to find later runnable independent siblings; tasks with zero or all
  completed prerequisites execute.
- **`worker_spawn_requests` schema**: Added table and index (`idx_worker_spawn_task`) to `schema/project.sql`
  recording spawn briefs, capability delegation lists, budget split ways, and linked worker session/child run IDs.
- **Treeless isolation (`WorkerCommitter`)**: Added `WorkerCommitter` seam in `commonMain` and JGit
  `GitWorkerCommitter` in `:agent:daemon` using `TreelessWorker` to commit outcome summaries under
  `refs/aidos/workers/<workerSessionId>` without checking out working trees.
- **Automated Verification**: End-to-end unit and integration test suite in `DriverWorkerOrchestrationTest`
  verifies permission gates, delegation with budget division, DAG dependency resolution, cascading skip states,
  and recursive child execution with parent resumption.

### 6. Kernel ↔ Schema Mapping Test (PIPELINE.md §6) — ✅ Complete

**2026-11-09 update:** Implemented automated JVM contract test `KernelSchemaMapTest` in `:agent:storage` asserting that every non-derived persisted kernel data class (`Run`, `Task` including `approvalChannel`/`approvalPhrase`, `Attempt`, `Capability`, `ContentNode`, `ProvenanceEdge`, `DegradationEvent`, `ScheduledJob`, `Budget`, `ContextItem`, `ProjectSummary`, `SessionSummary`, `ModelDescriptor`, `ProviderRetention`, `ToolCall`) maps to a canonical column in `schema/` (`project.sql`, `user.sql`, and `vault.sql`). Forms the third leg of CI alongside `schema/check.py` and module test suites. Verified with `$env:PYTHONUTF8=1; python schema/check.py` and `gradle :storage:jvmTest`.

### 7. Inference Groups / AIDIN Phase 2 & 3 (RFC-0105 & decisions.md D50) — ✅ Complete

**2026-11-09 update:** Implemented virtual model resolution (`aidos:chat`, `aidos:embed`, `aidos:stt`), locality-first filtering, group certificate renewal, and supervised child-process layer splitting across trusted peers:
- **Virtual Model Resolution & Routing (`VirtualModelRouter`)**: Filters configured and peer-advertised candidate models by request locality before any network contact. Queries `POST /aidin/v1/route` on candidate peers, verifies origin placement against origin's trust store (origin verifies anchor and every helper in a split route), maps placements (`cluster` for trusted peers/helpers, `remote` if untrusted helper or remote peer, 403 `locality_violation` if locality excludes candidates, 503 `model_unavailable` if permitted candidates are offline), and enforces warm local fallback pinning in `GlobalModelRuntime` against LRU eviction while peer candidates are active.
- **Peer Delegation RPC & Mid-Request Recovery**: Mounted `/aidin/v1/` endpoints (`/route`, `/chat/completions`, `/embeddings`, `/audio/transcriptions`, `/systemone`) over mTLS `PeerLink` in `AidinNode` and `EngineHttpServer`. Enforces hop limit 1 (delegated requests never re-delegate). Non-streaming requests retry once on local fallback upon peer loss or `409 plan_changed` / `503 peer_busy`; streaming requests emit SSE `{"type":"peer_lost","retryable":true}` and next request resolves locally without waiting for heartbeat timeout.
- **Group Certificate Renewal (`AidinNode`)**: Group admins renew member certificates over `PeerLink` when validity drops below threshold (default 7 days) and push `CertificateRenewal` wire frames without re-pairing.
- **Supervised Child-Process Layer Splitting (`SplitProcessSupervisors`)**:
  - `HelperProcessSupervisor` runs `ggml-rpc-server -H 127.0.0.1 -p <port> -c <cache>` as an isolated child OS process, exposes it only via authenticated tunnel to the active plan's anchor, and drains/stops on `ContributionPolicy` triggers (unplug, low battery, severe thermal).
  - `AnchorProcessSupervisor` generates versioned plans (`SplitModelPlanner`), opens loopback `127.0.0.1:<port>` tunnel listeners to each helper, supervises `llama-server` with `--rpc` and `--tensor-split`, and handles child aborts or helper drops by stopping `llama-server`, marking the split model unavailable for immediate virtual model fallback, and triggering `RESTART` re-planning with remaining healthy group members.
- **Dictator Integration**: Dictator's `AidosProvider` updated to resolve chat via `aidos:chat` while keeping dictation `local_only`.
- **Verification**: Verified with 51 unit/integration tests in `:engine:aidin:jvmTest` (`SplitInferenceTest`, `AidinNodeTest`), 153 unit/integration tests in `:engine:enginehost:jvmTest` (`VirtualModelAndDelegationTest`, `LocalityEnforcementTest`, etc.), and `:engine:modelruntime:jvmTest`.

