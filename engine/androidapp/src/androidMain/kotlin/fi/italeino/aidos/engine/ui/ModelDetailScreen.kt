package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.italeino.aidos.engine.ModelStateManager
import kotlinx.coroutines.launch

/**
 * Model detail / acquire screen (RFC-0103, RFC-0022).
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
@Composable
fun ModelDetailScreen(modelId: String) {
    val modelStateManager = remember { ModelStateManager.getInstance() }
    val loadedModels by modelStateManager.loadedModels.collectAsState()
    val isLoading by modelStateManager.loadingModel.collectAsState()
    val error by modelStateManager.loadError.collectAsState()
    val scope = rememberCoroutineScope()
    
    val isLoaded = loadedModels.contains(modelId)
    val isLoadingThisModel = isLoading == modelId
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Model: $modelId")
        Text("(Context-length fit table — 4k/16k/32k verdicts — will appear here, RFC-0022)")
        Text("(License/ToS text for this model+version will appear here)")
        Text("(Hugging Face token prompt appears here only if this model is gated)")
        
        // Load button
        Button(
            onClick = {
                scope.launch {
                    modelStateManager.loadModel(modelId)
                }
            },
            enabled = !isLoadingThisModel && !isLoaded,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            if (isLoadingThisModel) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            }
            Text(
                if (isLoaded) "Loaded into Memory" 
                else if (isLoadingThisModel) "Loading..." 
                else "Load into Memory"
            )
        }
        
        // Unload button (if model is loaded)
        if (isLoaded) {
            Button(
                onClick = {
                    scope.launch {
                        modelStateManager.unloadModel(modelId)
                    }
                },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Unload from Memory")
            }
        }
        
        // Error display
        if (error != null) {
            Text(
                "Error: $error",
                modifier = Modifier.padding(top = 16.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.error
            )
        }
        
        Button(
            onClick = { /* TODO(RFC-0103): disabled until license accepted; starts a
            resumable download via engine/downloads, digest-verified before use (RFC-0022) */ },
            enabled = false
        ) {
            Text("Download")
        }
        // TODO(RFC-0103): bind to LicenseAcceptance records and the vault (HF token) once
        // Engine Core's storage exists.
    }
}
