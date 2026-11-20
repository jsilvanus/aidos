package dev.aidos.ui.assistant

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeType
import dev.aidos.tcr.overlay.IntentCreationChatPanel as TcrIntentCreationChatPanel
import dev.aidos.tcr.overlay.TcrChatModelOption
import dev.aidos.tcr.overlay.TcrIntentChatMessage
import dev.aidos.tcr.overlay.TcrIntentChatRole
import dev.aidos.tcr.overlay.TcrIntentChatUiState
import dev.aidos.tcr.overlay.TcrIntentProposal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ChatModelOption(
    val id: String,
    val label: String,
    val subtitle: String = "",
    val isInstalled: Boolean = true,
)

enum class ChatMessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

data class IntentProposal(
    val id: String,
    val title: String,
    val type: IntentNodeType = IntentNodeType.ATOMIC_TASK,
    val actorKind: IntentActorKind = IntentActorKind.AGENT,
    val description: String? = null,
    val isCreated: Boolean = false,
)

data class IntentChatMessage(
    val id: String,
    val role: ChatMessageRole,
    val text: String,
    val modelId: String? = null,
    val proposals: List<IntentProposal> = emptyList(),
)

data class IntentCreationChatUiState(
    val isOpen: Boolean = false,
    val availableModels: List<ChatModelOption> = listOf(AUTO_MODEL_OPTION),
    val selectedModelId: String = AUTO_MODEL_OPTION.id,
    val promptText: String = "",
    val selectedType: IntentNodeType = IntentNodeType.ATOMIC_TASK,
    val selectedActor: IntentActorKind = IntentActorKind.AGENT,
    val messages: List<IntentChatMessage> = emptyList(),
    val isSending: Boolean = false,
    val errorMessage: String? = null,
) {
    val selectedModel: ChatModelOption
        get() = availableModels.firstOrNull { it.id == selectedModelId }
            ?: availableModels.firstOrNull()
            ?: AUTO_MODEL_OPTION

    companion object {
        val AUTO_MODEL_OPTION = ChatModelOption(
            id = "auto",
            label = "Auto (Default)",
            subtitle = "Active orchestrator routing",
            isInstalled = true,
        )
    }
}

/**
 * State holder for the Red Pill Orchestrator Intent Creation Chat.
 * Supports model selection, prompt composition, direct intent creation, and 1-click proposal creation.
 */
class IntentCreationChatState(
    initialOpen: Boolean = false,
    initialModels: List<ChatModelOption> = emptyList(),
) {
    private var nextIdCounter = 1
    private val _state = MutableStateFlow(
        IntentCreationChatUiState(
            isOpen = initialOpen,
            availableModels = normalizeModels(initialModels),
        )
    )
    val state: StateFlow<IntentCreationChatUiState> = _state.asStateFlow()

    fun setOpen(open: Boolean) {
        _state.update { it.copy(isOpen = open, errorMessage = null) }
    }

    fun toggleOpen() {
        _state.update { it.copy(isOpen = !it.isOpen, errorMessage = null) }
    }

    fun setModels(models: List<ChatModelOption>) {
        val normalized = normalizeModels(models)
        _state.update { current ->
            val keepSelected = normalized.any { it.id == current.selectedModelId }
            current.copy(
                availableModels = normalized,
                selectedModelId = if (keepSelected) current.selectedModelId else IntentCreationChatUiState.AUTO_MODEL_OPTION.id,
            )
        }
    }

    fun selectModel(modelId: String) {
        if (_state.value.availableModels.any { it.id == modelId }) {
            _state.update { it.copy(selectedModelId = modelId) }
        }
    }

    fun updatePrompt(text: String) {
        _state.update { it.copy(promptText = text, errorMessage = null) }
    }

    fun selectType(type: IntentNodeType) {
        _state.update { it.copy(selectedType = type) }
    }

    fun selectActor(actor: IntentActorKind) {
        _state.update { it.copy(selectedActor = actor) }
    }

    suspend fun submitPrompt(
        onCreateNode: suspend (title: String, type: IntentNodeType, actor: IntentActorKind, description: String?) -> Unit,
        onInference: (suspend (prompt: String, modelId: String) -> String)? = null,
        createDirectly: Boolean = false,
    ) {
        val current = _state.value
        val trimmed = current.promptText.trim()
        if (trimmed.isEmpty() || current.isSending) return

        val modelId = current.selectedModel.id
        val modelLabel = current.selectedModel.label
        val userMsg = IntentChatMessage(
            id = nextId("msg"),
            role = ChatMessageRole.USER,
            text = trimmed,
            modelId = modelId,
        )

        _state.update {
            it.copy(
                promptText = "",
                isSending = true,
                errorMessage = null,
                messages = it.messages + userMsg,
            )
        }

        try {
            if (createDirectly) {
                val proposals = buildProposalsFromPrompt(trimmed, current.selectedType, current.selectedActor)
                val createdProposals = proposals.map { proposal ->
                    onCreateNode(proposal.title, proposal.type, proposal.actorKind, proposal.description)
                    proposal.copy(isCreated = true)
                }
                val assistantMsg = IntentChatMessage(
                    id = nextId("msg"),
                    role = ChatMessageRole.ASSISTANT,
                    text = "Created ${createdProposals.size} intent(s) directly in the active project.",
                    modelId = modelId,
                    proposals = createdProposals,
                )
                _state.update { it.copy(isSending = false, messages = it.messages + assistantMsg) }
            } else {
                val replyText = if (onInference != null) {
                    onInference(trimmed, modelId)
                } else {
                    "Prepared ${current.selectedType.name.lowercase().replace('_', ' ')} proposal via $modelLabel. Review and create with 1 click:"
                }
                val proposals = buildProposalsFromPrompt(trimmed, current.selectedType, current.selectedActor)
                val assistantMsg = IntentChatMessage(
                    id = nextId("msg"),
                    role = ChatMessageRole.ASSISTANT,
                    text = replyText,
                    modelId = modelId,
                    proposals = proposals,
                )
                _state.update { it.copy(isSending = false, messages = it.messages + assistantMsg) }
            }
        } catch (t: Throwable) {
            _state.update {
                it.copy(
                    isSending = false,
                    errorMessage = t.message ?: "Failed to process prompt",
                )
            }
        }
    }

    suspend fun createProposal(
        messageId: String,
        proposalId: String,
        onCreateNode: suspend (title: String, type: IntentNodeType, actor: IntentActorKind, description: String?) -> Unit,
    ) {
        val target = _state.value.messages
            .firstOrNull { it.id == messageId }
            ?.proposals
            ?.firstOrNull { it.id == proposalId }
            ?: return

        if (target.isCreated) return

        try {
            onCreateNode(target.title, target.type, target.actorKind, target.description)
            _state.update { current ->
                current.copy(
                    errorMessage = null,
                    messages = current.messages.map { msg ->
                        if (msg.id != messageId) {
                            msg
                        } else {
                            msg.copy(
                                proposals = msg.proposals.map { prop ->
                                    if (prop.id == proposalId) prop.copy(isCreated = true) else prop
                                }
                            )
                        }
                    },
                )
            }
        } catch (t: Throwable) {
            _state.update { it.copy(errorMessage = t.message ?: "Failed to create intent") }
        }
    }

    fun buildProposalsFromPrompt(
        prompt: String,
        defaultType: IntentNodeType,
        defaultActor: IntentActorKind,
    ): List<IntentProposal> {
        val lines = prompt.lines()
            .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
            .filter { it.isNotEmpty() }

        if (lines.size <= 1) {
            return listOf(
                IntentProposal(
                    id = nextId("prop"),
                    title = prompt.trim(),
                    type = defaultType,
                    actorKind = defaultActor,
                )
            )
        }

        return lines.mapIndexed { index, line ->
            val inferredType = if (index == 0 && defaultType == IntentNodeType.GOAL) {
                IntentNodeType.GOAL
            } else if (lines.size > 1 && defaultType == IntentNodeType.GOAL) {
                IntentNodeType.SUB_GOAL
            } else {
                defaultType
            }
            IntentProposal(
                id = nextId("prop"),
                title = line,
                type = inferredType,
                actorKind = defaultActor,
            )
        }
    }

    private fun nextId(prefix: String): String = "$prefix-${nextIdCounter++}"

    private fun normalizeModels(models: List<ChatModelOption>): List<ChatModelOption> {
        val distinct = models.distinctBy { it.id }
        return if (distinct.any { it.id == IntentCreationChatUiState.AUTO_MODEL_OPTION.id }) {
            distinct
        } else {
            listOf(IntentCreationChatUiState.AUTO_MODEL_OPTION) + distinct
        }
    }
}

/**
 * Toggleable Intent Creation Chat drawer/panel anchored to the Red Pill Orchestrator.
 * Delegates rendering to [dev.aidos.tcr.overlay.IntentCreationChatPanel].
 */
@Composable
fun IntentCreationChatPanel(
    uiState: IntentCreationChatUiState,
    onSelectModel: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onSelectType: (IntentNodeType) -> Unit,
    onSelectActor: (IntentActorKind) -> Unit,
    onSendPrompt: () -> Unit,
    onDirectCreate: () -> Unit,
    onCreateProposal: (messageId: String, proposalId: String) -> Unit,
    onDismiss: () -> Unit,
    onOpenVoiceAssistant: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tcrState = TcrIntentChatUiState(
        isOpen = uiState.isOpen,
        availableModels = uiState.availableModels.map {
            TcrChatModelOption(
                id = it.id,
                label = it.label,
                subtitle = it.subtitle,
                isInstalled = it.isInstalled,
            )
        },
        selectedModelId = uiState.selectedModelId,
        promptText = uiState.promptText,
        selectedType = uiState.selectedType.name,
        selectedActor = uiState.selectedActor.name,
        messages = uiState.messages.map { msg ->
            TcrIntentChatMessage(
                id = msg.id,
                role = when (msg.role) {
                    ChatMessageRole.USER -> TcrIntentChatRole.USER
                    ChatMessageRole.ASSISTANT -> TcrIntentChatRole.ASSISTANT
                    ChatMessageRole.SYSTEM -> TcrIntentChatRole.SYSTEM
                },
                text = msg.text,
                modelId = msg.modelId,
                proposals = msg.proposals.map { prop ->
                    TcrIntentProposal(
                        id = prop.id,
                        title = prop.title,
                        type = prop.type.name,
                        actor = prop.actorKind.name,
                        description = prop.description,
                        isCreated = prop.isCreated,
                    )
                },
            )
        },
        isSending = uiState.isSending,
        errorMessage = uiState.errorMessage,
    )

    TcrIntentCreationChatPanel(
        uiState = tcrState,
        onSelectModel = onSelectModel,
        onPromptChange = onPromptChange,
        onSelectType = { typeName ->
            IntentNodeType.entries.firstOrNull { it.name == typeName }?.let(onSelectType)
        },
        onSelectActor = { actorName ->
            IntentActorKind.entries.firstOrNull { it.name == actorName }?.let(onSelectActor)
        },
        onSendPrompt = onSendPrompt,
        onDirectCreate = onDirectCreate,
        onCreateProposal = onCreateProposal,
        onDismiss = onDismiss,
        onOpenVoiceAssistant = onOpenVoiceAssistant,
        typeOptions = listOf(
            IntentNodeType.GOAL.name to "Goal",
            IntentNodeType.SUB_GOAL.name to "Sub-Goal",
            IntentNodeType.ATOMIC_TASK.name to "Task",
        ),
        actorOptions = listOf(
            IntentActorKind.HUMAN.name to "Human",
            IntentActorKind.AGENT.name to "Agent",
            IntentActorKind.HYBRID.name to "Hybrid",
        ),
        modifier = modifier,
    )
}
