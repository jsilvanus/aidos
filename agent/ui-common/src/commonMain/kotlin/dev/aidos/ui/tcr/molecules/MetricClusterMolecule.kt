package dev.aidos.ui.tcr.molecules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.ui.tcr.code.StepCounterAtom
import dev.aidos.ui.tcr.core.MetricBadgeAtom
import dev.aidos.ui.theme.SpacingTokens

@Composable
fun MetricClusterMolecule(
    currentStep: Int? = null,
    totalSteps: Int? = null,
    locality: IntentMinLocality? = null,
    targetProfile: IntentTargetProfile? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (currentStep != null && totalSteps != null) {
            StepCounterAtom(currentStep = currentStep, totalSteps = totalSteps)
        }

        if (locality != null) {
            val localityColor = when (locality) {
                IntentMinLocality.LOCAL_ONLY -> Color(0xFF10B981)
                IntentMinLocality.TRUSTED_CLUSTER -> Color(0xFF38BDF8)
                IntentMinLocality.ANY -> Color(0xFFA855F7)
            }
            MetricBadgeAtom(
                label = "Locality",
                value = locality.name,
                accentColor = localityColor,
            )
        }

        if (targetProfile != null) {
            MetricBadgeAtom(
                label = "Profile",
                value = targetProfile.name,
                accentColor = Color(0xFFF59E0B),
            )
        }
    }
}
