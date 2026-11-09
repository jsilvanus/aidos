package dev.aidos.aidin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.File

/**
 * What an Engine host needs for RFC-0105 phase 3 layer split: both supervisors on its [AidinNode],
 * driving the pinned llama.cpp programs found in [binDir], plus the heartbeat fields that make this
 * node a candidate helper.
 *
 * Split is offered only when the native programs are installed. The JVM stand-ins exist for tests;
 * a host never advertises them, so a peer never plans layers onto a node that cannot compute them.
 */
class SplitHosting(
    node: AidinNode,
    scope: CoroutineScope,
    binDir: File?,
    stateDir: File,
    /** This device's limits, read on every plan and heartbeat so a settings change applies at once. */
    private val policy: () -> ContributionPolicy,
    private val llamaCppBuildId: String = HelperProcessSupervisor.DEFAULT_LLAMA_CPP_BUILD_ID,
) : Closeable {
    private val spawner = OsSplitProcessSpawner.fromDirectory(binDir, logDir = File(stateDir, "split-logs"))

    /** True when `llama-server` and `ggml-rpc-server` are installed, so this node can split. */
    val available: Boolean = spawner.usesNativeBinaries

    private val helper: HelperProcessSupervisor? = if (available) {
        HelperProcessSupervisor(node, File(stateDir, "rpc-cache"), llamaCppBuildId, spawner, policy)
    } else null

    private val anchor: AnchorProcessSupervisor? = if (available) {
        AnchorProcessSupervisor(node, scope, llamaCppBuildId, spawner)
    } else null

    private val _status = MutableStateFlow<String?>(null)

    /** The last start or stop result, in words for the Groups screen; null before any. */
    val status: StateFlow<String?> = _status.asStateFlow()

    /** The split model this node anchors and serves now, or null. */
    val activeModelId: String? get() = anchor?.takeIf { it.isAvailable }?.activePlan?.modelId

    /**
     * Adds what makes this node a split helper to [base]: the llama.cpp build it runs and the RAM it
     * contributes. Without the programs the heartbeat stays as it was, so no planner picks this node.
     */
    fun heartbeat(base: Heartbeat): Heartbeat =
        if (!available) base
        else base.copy(llamaCppBuild = llamaCppBuildId, contributionRamBytes = policy().ramBudgetBytes.coerceAtLeast(0L))

    /**
     * Plans [modelFile] across [group] with this node as anchor and starts serving it. It then appears
     * to the group as a `cluster` model and resolves through `aidos:chat` like any other.
     */
    fun start(group: GroupId, modelId: String, modelDigest: String, modelFile: File, blockCount: Int, contextLength: Int = 4096): SplitPlanOutcome {
        val anchor = anchor ?: return SplitPlanOutcome.Refused(NOT_INSTALLED).also { _status.value = NOT_INSTALLED }
        val outcome = anchor.startSplitModel(
            domain = TrustDomain.Group(group),
            modelId = modelId,
            modelDigest = modelDigest,
            modelFile = modelFile,
            totalModelBytes = modelFile.length(),
            blockCount = blockCount,
            contextLength = contextLength,
            anchorRamBudgetBytes = policy().ramBudgetBytes,
        )
        _status.value = when (outcome) {
            is SplitPlanOutcome.Planned -> "Serving $modelId with ${outcome.plan.helpers.size} helper(s)"
            is SplitPlanOutcome.Refused -> "Could not split $modelId: ${outcome.reason}"
        }
        return outcome
    }

    /** Stops the split model this node anchors; helpers drop their share when their tunnels close. */
    fun stop() {
        val modelId = activeModelId
        anchor?.close()
        _status.value = modelId?.let { "Stopped $it" }
    }

    override fun close() {
        anchor?.close()
        helper?.close()
    }

    companion object {
        const val NOT_INSTALLED = "llama-server and ggml-rpc-server are not installed"
    }
}
