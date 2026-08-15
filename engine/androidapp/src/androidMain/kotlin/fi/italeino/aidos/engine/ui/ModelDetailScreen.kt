package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Model detail / acquire screen (RFC-0103, RFC-0022, Phase D).
 *
 * Reachable both from Home's Cookbook pane and as a deep link from client apps — RFC-0103
 * requires this route work standalone, since Aidos Agent (and any other client) deep-links here
 * rather than rendering its own download UI.
 *
 * Shows, in order: the per-context-length fit table RFC-0022 specifies (4k/16k/32k verdicts),
 * the model's license/terms-of-service at the point of deciding to download (not a blanket EULA
 * at first launch), and — if the model needs Hugging Face authentication — an inline token
 * prompt. Download is disabled until the license for this model+version is accepted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDetailScreen(modelId: String) {
    // Sample data (will be bound to ViewModel later)
    var state by remember {
        mutableStateOf(
            ModelDetailState(
                model = ModelDetail(
                    id = modelId,
                    name = "Qwen2.5 3B Instruct Q4_K_M",
                    description = "A high-performing 3B parameter model optimized for instruction-following tasks with excellent quality-to-size ratio.",
                    licenseName = "Apache 2.0",
                    licenseText = """
                        Apache License
                        Version 2.0, January 2004

                        TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

                        1. Definitions.

                        "License" shall mean the terms and conditions for use, reproduction, and distribution.
                        "Licensor" shall mean the copyright owner and entity excluding any individual or Legal Entity exercising permissions granted by this License.
                        
                        2. Grant of Copyright License. Subject to the terms and conditions of this License, each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable copyright license to reproduce, prepare Derivative Works of, publicly display, publicly perform, sublicense, and distribute the Work and such Derivative Works in Source or Object form.
                    """.trimIndent(),
                    sizeMB = 2_100,
                    contextFitTable = listOf(
                        ContextFitRow(4, ModelFitVerdict.RUNS_WELL, 600),
                        ContextFitRow(16, ModelFitVerdict.RUNS_WELL, 1_800),
                        ContextFitRow(32, ModelFitVerdict.RUNS_TIGHT, 2_400),
                    ),
                    requiresHfToken = false
                ),
                licenseAccepted = false,
                isDownloading = false,
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.model?.name?.take(30) ?: "Model") },
                navigationIcon = {
                    IconButton(onClick = { /* TODO: navigate back */ }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.model != null) {
                val model = state.model!!

                Text(
                    model.description,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline
                )

                Text(
                    "Size: ${model.sizeMB} MB",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                ContextFitTable(model.contextFitTable)

                LicenseAcceptanceCard(
                    licenseName = model.licenseName,
                    licenseText = model.licenseText,
                    accepted = state.licenseAccepted,
                    onAcceptedChange = { state = state.copy(licenseAccepted = it) }
                )

                if (model.requiresHfToken) {
                    Column {
                        Text(
                            "Hugging Face Token",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "This model requires a Hugging Face access token.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                        // Token field would go here (masked)
                    }
                }

                Button(
                    onClick = { /* TODO: Start download */ },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    enabled = state.licenseAccepted && !state.isDownloading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        if (state.isDownloading) "Downloading..." else "Download Model",
                        color = Color.White
                    )
                }

                if (state.isDownloading) {
                    DownloadProgressCard(
                        DownloadProgress(
                            model.id,
                            model.name,
                            state.downloadProgress,
                            12.5f,
                            300
                        )
                    )
                }
            }
        }
    }
}
