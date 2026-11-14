package dev.aidos.ui.frontdoors.gates

import dev.aidos.ui.state.MviPresenter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ApprovalGateUiState(
    val pendingEgressRequests: List<BlindedEgressRequestUiModel> = emptyList(),
    val pendingVerifications: List<SplitVerificationUiModel> = emptyList(),
    val inspectedDiffs: List<FileDiffPreview>? = null,
    val isLoading: Boolean = false,
    val message: String? = null,
)

sealed interface ApprovalGateIntent {
    data class ApproveEgress(val requestId: String) : ApprovalGateIntent
    data class DenyEgress(val requestId: String, val reason: String) : ApprovalGateIntent
    data class RunVerification(val nodeId: String) : ApprovalGateIntent
    data class InspectDiff(val nodeId: String) : ApprovalGateIntent
    data object CloseDiffInspection : ApprovalGateIntent
}

class ApprovalGatePresenter(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onEgressApproved: (suspend (String) -> Unit)? = null,
    private val onEgressDenied: (suspend (String, String) -> Unit)? = null,
    private val onVerificationExecuted: (suspend (String) -> Unit)? = null,
) : MviPresenter<ApprovalGateUiState, ApprovalGateIntent> {

    private val _state = MutableStateFlow(ApprovalGateUiState())
    override val state: StateFlow<ApprovalGateUiState> = _state.asStateFlow()

    fun stageEgressRequest(request: BlindedEgressRequestUiModel) {
        _state.update {
            it.copy(pendingEgressRequests = it.pendingEgressRequests + request)
        }
    }

    fun stageVerification(verification: SplitVerificationUiModel) {
        _state.update {
            it.copy(pendingVerifications = it.pendingVerifications + verification)
        }
    }

    override fun send(intent: ApprovalGateIntent) {
        when (intent) {
            is ApprovalGateIntent.ApproveEgress -> {
                scope.launch(ioDispatcher) {
                    onEgressApproved?.invoke(intent.requestId)
                    _state.update {
                        it.copy(
                            pendingEgressRequests = it.pendingEgressRequests.filterNot { req -> req.requestId == intent.requestId },
                            message = "Egress request authorized for transmission."
                        )
                    }
                }
            }
            is ApprovalGateIntent.DenyEgress -> {
                scope.launch(ioDispatcher) {
                    onEgressDenied?.invoke(intent.requestId, intent.reason)
                    _state.update {
                        it.copy(
                            pendingEgressRequests = it.pendingEgressRequests.filterNot { req -> req.requestId == intent.requestId },
                            message = "Egress request denied."
                        )
                    }
                }
            }
            is ApprovalGateIntent.RunVerification -> {
                scope.launch(ioDispatcher) {
                    onVerificationExecuted?.invoke(intent.nodeId)
                    _state.update {
                        it.copy(
                            pendingVerifications = it.pendingVerifications.filterNot { ver -> ver.nodeId == intent.nodeId },
                            message = "Verification completed."
                        )
                    }
                }
            }
            is ApprovalGateIntent.InspectDiff -> {
                _state.update {
                    it.copy(
                        inspectedDiffs = listOf(
                            FileDiffPreview(
                                filePath = "sample/code/Diff.kt",
                                lines = emptyList()
                            )
                        )
                    )
                }
            }
            is ApprovalGateIntent.CloseDiffInspection -> {
                _state.update { it.copy(inspectedDiffs = null) }
            }
        }
    }
}
