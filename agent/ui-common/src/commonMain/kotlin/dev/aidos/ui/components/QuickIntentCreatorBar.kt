package dev.aidos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeType
import dev.aidos.tcr.intent.QuickIntentCreatorBar as TcrQuickIntentCreatorBar

/**
 * Kernel-enum adapter over [dev.aidos.tcr.intent.QuickIntentCreatorBar].
 */
@Composable
fun QuickIntentCreatorBar(
    onCreateIntent: (title: String, type: IntentNodeType, actorKind: IntentActorKind) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "New intent title...",
    defaultType: IntentNodeType = IntentNodeType.ATOMIC_TASK,
    defaultActor: IntentActorKind = IntentActorKind.AGENT,
    initiallyExpanded: Boolean = false,
) {
    TcrQuickIntentCreatorBar(
        onCreateIntent = { title, typeName, actorName ->
            onCreateIntent(
                title,
                IntentNodeType.entries.firstOrNull { it.name == typeName } ?: defaultType,
                IntentActorKind.entries.firstOrNull { it.name == actorName } ?: defaultActor,
            )
        },
        modifier = modifier,
        placeholder = placeholder,
        typeOptions = listOf(
            IntentNodeType.GOAL.name,
            IntentNodeType.SUB_GOAL.name,
            IntentNodeType.ATOMIC_TASK.name,
        ),
        actorOptions = listOf(
            IntentActorKind.HUMAN.name,
            IntentActorKind.AGENT.name,
            IntentActorKind.HYBRID.name,
        ),
        defaultType = defaultType.name,
        defaultActor = defaultActor.name,
        initiallyExpanded = initiallyExpanded,
    )
}
