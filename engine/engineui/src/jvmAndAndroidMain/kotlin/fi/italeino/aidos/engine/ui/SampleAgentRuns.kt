package fi.italeino.aidos.engine.ui

import dev.aidos.tcr.agent.*

/**
 * Shared demonstration and sample data for [AgentsPane].
 * Showcases RFC-0011 Driver/Worker Topology, DAG dependencies, budget split meters,
 * RFC-0104 placement (Local, Cluster, Remote), and RFC-0042 network egress records.
 */
object SampleAgentRuns {
    fun create(): List<AgentRunUiModel> {
        val now = System.currentTimeMillis()

        val driverRun = AgentRunUiModel(
            runId = "run-driver-001",
            ownerApp = "Dictator Workspace",
            modelId = "gemma-2-2b-it",
            placement = AgentPlacement.LOCAL,
            locality = AgentLocality.LOCAL_ONLY,
            status = AgentRunStatus.TOOL_CALLS,
            role = AgentRoleUi.DRIVER,
            seq = 4,
            steps = 4,
            maxSteps = 12,
            promptPreview = "Refactor audio pipeline and spawn worker to process audio frequency buffers",
            replyText = "",
            isParkedOnChild = true,
            awaitingChildRunId = "run-worker-002",
            tasks = listOf(
                TaskGraphNodeUi("t1", 1, "TOOL_CALL", "Inspect audio input device descriptors", "COMPLETED"),
                TaskGraphNodeUi("t2", 2, "COMPOSITE", "Spawn worker: analyze FFT frequency buffers", "AWAITING_INPUT", listOf("t1"), awaitingRunId = "run-worker-002"),
                TaskGraphNodeUi("t3", 3, "TOOL_CALL", "Merge audio transform buffers", "PENDING", listOf("t2")),
                TaskGraphNodeUi("t4", 4, "MODEL_CALL", "Draft voice recognition response", "PENDING", listOf("t3")),
            ),
            workerSpawns = listOf(
                WorkerSpawnUi(
                    taskId = "t2",
                    workerSessionId = "sess-worker-002",
                    childRunId = "run-worker-002",
                    brief = "Analyze FFT frequency buffers",
                    budgetSplitWays = 2,
                    delegatedCapabilities = listOf("STT_QUERY", "FS_READ")
                )
            ),
            budget = AgentBudgetUi(
                modelCalls = 8,
                inputTokens = 8000L,
                outputTokens = 4000L,
                steps = 12,
                maxSteps = 12,
                splitWays = 2
            ),
            startedAt = now - 120_000,
        )

        val workerRun = AgentRunUiModel(
            runId = "run-worker-002",
            ownerApp = "Dictator Workspace",
            modelId = "gemma-2-2b-it",
            placement = AgentPlacement.LOCAL,
            locality = AgentLocality.LOCAL_ONLY,
            status = AgentRunStatus.RUNNING,
            role = AgentRoleUi.WORKER,
            parentSessionId = "sess-driver-001",
            parentRunId = "run-driver-001",
            workerBrief = "Analyze FFT frequency buffers",
            seq = 2,
            steps = 2,
            maxSteps = 6,
            promptPreview = "Task Brief: Analyze FFT frequency buffers (delegated from driver)",
            replyText = "",
            tasks = listOf(
                TaskGraphNodeUi("wt1", 1, "TOOL_CALL", "Read raw audio stream samples", "COMPLETED"),
                TaskGraphNodeUi("wt2", 2, "MODEL_CALL", "Compute frequency bins & spectral centroids", "RUNNING", listOf("wt1")),
            ),
            budget = AgentBudgetUi(
                modelCalls = 4,
                inputTokens = 4000L,
                outputTokens = 2000L,
                steps = 6,
                maxSteps = 6,
                splitWays = 2
            ),
            startedAt = now - 60_000,
        )

        val clusterWorkerRun = AgentRunUiModel(
            runId = "run-worker-004",
            ownerApp = "Agentic Messenger",
            modelId = "llama-3.2-3b-q4",
            placement = AgentPlacement.CLUSTER,
            locality = AgentLocality.TRUSTED_CLUSTER,
            status = AgentRunStatus.RUNNING,
            role = AgentRoleUi.WORKER,
            parentSessionId = "sess-cluster-driver",
            parentRunId = "run-cluster-driver",
            workerBrief = "Verify consensus blocks with AIDIN cluster peer",
            seq = 1,
            steps = 1,
            maxSteps = 5,
            promptPreview = "Verify multi-party consensus proof with trusted AIDIN peer",
            replyText = "",
            tasks = listOf(
                TaskGraphNodeUi("ct1", 1, "TOOL_CALL", "Query local peer table", "COMPLETED"),
                TaskGraphNodeUi("ct2", 2, "MODEL_CALL", "Validate distributed ledger proof", "RUNNING", listOf("ct1")),
            ),
            budget = AgentBudgetUi(
                modelCalls = 5,
                inputTokens = 5000L,
                outputTokens = 2500L,
                steps = 5,
                maxSteps = 5,
                splitWays = 1
            ),
            startedAt = now - 45_000,
        )

        val remoteWorkerRun = AgentRunUiModel(
            runId = "run-remote-005",
            ownerApp = "External Client",
            modelId = "claude-3-5-sonnet",
            placement = AgentPlacement.REMOTE,
            locality = AgentLocality.ANY,
            status = AgentRunStatus.COMPLETED,
            role = AgentRoleUi.WORKER,
            parentSessionId = "sess-remote-parent",
            parentRunId = "run-remote-parent",
            workerBrief = "Translate document to French & German via Anthropic API",
            seq = 4,
            steps = 4,
            maxSteps = 4,
            promptPreview = "Translate document to French & German using external API provider",
            replyText = "Translation complete for French and German sections.",
            tasks = listOf(
                TaskGraphNodeUi("rt1", 1, "MODEL_CALL", "Translate to French", "COMPLETED"),
                TaskGraphNodeUi("rt2", 2, "MODEL_CALL", "Translate to German", "COMPLETED", listOf("rt1")),
            ),
            budget = AgentBudgetUi(
                modelCalls = 2,
                inputTokens = 2000L,
                outputTokens = 1500L,
                steps = 4,
                maxSteps = 4,
                splitWays = 1
            ),
            egressSummary = AgentEgressSummaryUi(
                totalCalls = 2,
                totalBytesOut = 3200L,
                totalBytesIn = 4800L,
                records = listOf(
                    AgentEgressRecordUi(
                        destinationHost = "api.anthropic.com",
                        method = "POST",
                        requestBytes = 1600L,
                        responseBytes = 2400L,
                        payloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                        durationMs = 450L,
                        outcome = "ALLOWED",
                        timestamp = "2026-11-09T15:30:00Z"
                    ),
                    AgentEgressRecordUi(
                        destinationHost = "api.anthropic.com",
                        method = "POST",
                        requestBytes = 1600L,
                        responseBytes = 2400L,
                        payloadHash = "a1b2c3d4e5f60718293a4b5c6d7e8f901234567890abcdef1234567890abcdef",
                        durationMs = 420L,
                        outcome = "ALLOWED",
                        timestamp = "2026-11-09T15:30:02Z"
                    )
                )
            ),
            startedAt = now - 180_000,
            finishedAt = now - 120_000,
        )

        val completedWorkerRun = AgentRunUiModel(
            runId = "run-worker-003",
            ownerApp = "Agentic Messenger",
            modelId = "qwen-2.5-1.5b",
            placement = AgentPlacement.LOCAL,
            locality = AgentLocality.LOCAL_ONLY,
            status = AgentRunStatus.COMPLETED,
            role = AgentRoleUi.WORKER,
            parentSessionId = "sess-amp-driver-1",
            parentRunId = "run-amp-driver-1",
            workerBrief = "Verify cryptographic Universal Envelope signatures",
            seq = 3,
            steps = 3,
            maxSteps = 4,
            promptPreview = "Verify envelope cryptographic signatures and provenance seals",
            replyText = "Verification complete: 3 envelope signatures verified valid against DeviceIdentity keys.",
            tasks = listOf(
                TaskGraphNodeUi("at1", 1, "TOOL_CALL", "Read envelope header signatures", "COMPLETED"),
                TaskGraphNodeUi("at2", 2, "MODEL_CALL", "Verify Ed25519 signatures", "COMPLETED", listOf("at1")),
                TaskGraphNodeUi("at3", 3, "TOOL_CALL", "Write verification receipt", "COMPLETED", listOf("at2")),
            ),
            workerOutcome = WorkerOutcomeUi(
                workerSessionId = "sess-amp-worker-3",
                commitRef = "refs/aidos/workers/sess-amp-worker-3",
                outcomeSummary = "Verified 3 envelope signatures; zero cryptographic errors found.",
                committedAt = "2026-11-09T15:40:00Z"
            ),
            startedAt = now - 300_000,
            finishedAt = now - 240_000,
        )

        return listOf(driverRun, workerRun, clusterWorkerRun, remoteWorkerRun, completedWorkerRun)
    }

    fun defaultStats(): AgentRuntimeOverviewStats = AgentRuntimeOverviewStats(
        activeRuns = 3,
        maxConcurrentRuns = 6,
        waitingToolCalls = 1,
        completedRuns = 2,
        failedRuns = 0,
        totalRetainedRuns = 5,
        appsCount = 3,
        runsByApp = mapOf("Dictator Workspace" to 2, "Agentic Messenger" to 2, "External Client" to 1),
        totalEgressCalls = 2,
    )
}
