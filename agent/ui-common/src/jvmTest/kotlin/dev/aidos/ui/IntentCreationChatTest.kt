package dev.aidos.ui

import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.assistant.ChatMessageRole
import dev.aidos.ui.assistant.ChatModelOption
import dev.aidos.ui.assistant.IntentCreationChatState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntentCreationChatTest {

    @Test
    fun toggleOpenAndModelListAlwaysIncludesAuto() {
        val chat = IntentCreationChatState()
        assertFalse(chat.state.value.isOpen)
        chat.toggleOpen()
        assertTrue(chat.state.value.isOpen)

        chat.setModels(
            listOf(
                ChatModelOption(id = "llama-3.2-3b", label = "Llama 3.2 3B", subtitle = "LLM · Q4_K_M"),
                ChatModelOption(id = "qwen-2.5-7b", label = "Qwen 2.5 7B", subtitle = "LLM · Q4_K_M"),
            )
        )
        val models = chat.state.value.availableModels
        assertEquals(3, models.size)
        assertEquals("auto", models.first().id)

        chat.selectModel("qwen-2.5-7b")
        assertEquals("qwen-2.5-7b", chat.state.value.selectedModelId)
        assertEquals("Qwen 2.5 7B", chat.state.value.selectedModel.label)
    }

    @Test
    fun submitPromptProposesAndOneClickCreatesIntent() = runTest {
        val chat = IntentCreationChatState()
        val created = mutableListOf<Triple<String, IntentNodeType, IntentActorKind>>()

        chat.selectType(IntentNodeType.ATOMIC_TASK)
        chat.selectActor(IntentActorKind.HUMAN)
        chat.updatePrompt("Review security gate config")

        chat.submitPrompt(
            onCreateNode = { title, type, actor, _ -> created += Triple(title, type, actor) },
            onInference = { prompt, modelId -> "[$modelId] Proposed from: $prompt" },
            createDirectly = false,
        )

        assertTrue(created.isEmpty())
        val messages = chat.state.value.messages
        assertEquals(2, messages.size)
        assertEquals(ChatMessageRole.USER, messages[0].role)
        assertEquals(ChatMessageRole.ASSISTANT, messages[1].role)
        assertEquals(1, messages[1].proposals.size)
        assertFalse(messages[1].proposals.first().isCreated)

        val proposalId = messages[1].proposals.first().id
        chat.createProposal(
            messageId = messages[1].id,
            proposalId = proposalId,
            onCreateNode = { title, type, actor, _ -> created += Triple(title, type, actor) },
        )

        assertEquals(listOf(Triple("Review security gate config", IntentNodeType.ATOMIC_TASK, IntentActorKind.HUMAN)), created)
        assertTrue(chat.state.value.messages[1].proposals.first().isCreated)
    }

    @Test
    fun directCreateMultiLineGoalInfersGoalAndSubGoals() = runTest {
        val chat = IntentCreationChatState()
        val created = mutableListOf<Triple<String, IntentNodeType, IntentActorKind>>()

        chat.selectType(IntentNodeType.GOAL)
        chat.selectActor(IntentActorKind.AGENT)
        chat.updatePrompt("Launch v1\n- Build UI\n- Run tests")

        chat.submitPrompt(
            onCreateNode = { title, type, actor, _ -> created += Triple(title, type, actor) },
            createDirectly = true,
        )

        assertEquals(
            listOf(
                Triple("Launch v1", IntentNodeType.GOAL, IntentActorKind.AGENT),
                Triple("Build UI", IntentNodeType.SUB_GOAL, IntentActorKind.AGENT),
                Triple("Run tests", IntentNodeType.SUB_GOAL, IntentActorKind.AGENT),
            ),
            created,
        )
        val assistantMsg = chat.state.value.messages.last()
        assertTrue(assistantMsg.proposals.all { it.isCreated })
    }
}
