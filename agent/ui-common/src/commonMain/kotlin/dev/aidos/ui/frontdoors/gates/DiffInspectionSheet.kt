package dev.aidos.ui.frontdoors.gates

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.aidos.ui.tcr.code.DiffChunkAtom
import dev.aidos.ui.tcr.code.DiffLine
import dev.aidos.ui.tcr.core.ActionButtonAtom
import dev.aidos.ui.tcr.core.ActionButtonKind
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

data class FileDiffPreview(
    val filePath: String,
    val lines: List<DiffLine>,
)

@Composable
fun DiffInspectionSheet(
    fileDiffs: List<FileDiffPreview>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0F1D), shape = RoundedCornerShape(topStart = CornerTokens.lg, topEnd = CornerTokens.lg))
            .padding(SpacingTokens.base)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "File Diff Inspection",
                    style = AidosTypography.baseTypography.titleMedium,
                    color = Color.White,
                )

                ActionButtonAtom(
                    text = "Done",
                    onClick = onClose,
                    kind = ActionButtonKind.TACTILE_MICRO,
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(fileDiffs, key = { it.filePath }) { diff ->
                    DiffChunkAtom(
                        header = diff.filePath,
                        lines = diff.lines,
                    )
                }
            }
        }
    }
}
