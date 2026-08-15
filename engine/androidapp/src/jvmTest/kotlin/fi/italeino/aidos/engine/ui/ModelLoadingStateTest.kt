package fi.italeino.aidos.engine.loading

import fi.italeino.aidos.engine.ui.ModelLoadingState
import fi.italeino.aidos.engine.ui.ModelLoadingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for ModelLoadingState and ModelLoadingStatus state machines (RFC-0103, Phase E).
 *
 * Tests cover state transitions for model loading/unloading:
 * - NOT_LOADED → LOADING → LOADED (success path)
 * - NOT_LOADED → LOADING → ERROR (failure path)
 * - LOADED → UNLOADING → NOT_LOADED (unload path)
 * - Progress tracking (0-100%)
 * - Error message handling
 */
class ModelLoadingStateTest {

    @Test
    fun testInitialState() {
        val state = ModelLoadingState()
        
        assertEquals("", state.modelId)
        assertEquals(ModelLoadingStatus.NOT_LOADED, state.status)
        assertEquals(0, state.loadProgress)
        assertEquals(0, state.estimatedMemoryMB)
        assertNull(state.error)
        assertNull(state.loadTimeMs)
    }

    @Test
    fun testLoadingStateTransition() {
        val initialState = ModelLoadingState(
            modelId = "test-model",
            status = ModelLoadingStatus.NOT_LOADED
        )
        
        val loadingState = initialState.copy(
            status = ModelLoadingStatus.LOADING,
            loadProgress = 0
        )
        
        assertEquals(ModelLoadingStatus.LOADING, loadingState.status)
        assertEquals(0, loadingState.loadProgress)
    }

    @Test
    fun testLoadProgressUpdates() {
        var state = ModelLoadingState(
            modelId = "test-model",
            status = ModelLoadingStatus.LOADING,
            loadProgress = 0
        )
        
        for (progress in listOf(25, 50, 75, 100)) {
            state = state.copy(loadProgress = progress)
            assertEquals(progress, state.loadProgress)
        }
    }

    @Test
    fun testSuccessfulLoadCompletion() {
        val state = ModelLoadingState(
            modelId = "test-model",
            status = ModelLoadingStatus.LOADING,
            loadProgress = 100
        )
        
        val loadedState = state.copy(
            status = ModelLoadingStatus.LOADED,
            loadTimeMs = 5000L
        )
        
        assertEquals(ModelLoadingStatus.LOADED, loadedState.status)
        assertEquals(100, loadedState.loadProgress)
        assertEquals(5000L, loadedState.loadTimeMs)
    }

    @Test
    fun testLoadingErrorState() {
        val state = ModelLoadingState(
            modelId = "test-model",
            status = ModelLoadingStatus.LOADING
        )
        
        val errorState = state.copy(
            status = ModelLoadingStatus.ERROR,
            loadProgress = 50,
            error = "Model not found on disk"
        )
        
        assertEquals(ModelLoadingStatus.ERROR, errorState.status)
        assertEquals("Model not found on disk", errorState.error)
        assertEquals(50, errorState.loadProgress)  // Partial progress preserved
    }

    @Test
    fun testErrorRecoveryRetry() {
        val errorState = ModelLoadingState(
            modelId = "test-model",
            status = ModelLoadingStatus.ERROR,
            error = "Timeout during load"
        )
        
        val retryState = errorState.copy(
            status = ModelLoadingStatus.LOADING,
            loadProgress = 0,
            error = null  // Clear error on retry
        )
        
        assertEquals(ModelLoadingStatus.LOADING, retryState.status)
        assertNull(retryState.error)
        assertEquals(0, retryState.loadProgress)
    }

    @Test
    fun testUnloadingStateTransition() {
        val loadedState = ModelLoadingState(
            modelId = "test-model",
            status = ModelLoadingStatus.LOADED
        )
        
        val unloadingState = loadedState.copy(
            status = ModelLoadingStatus.UNLOADING
        )
        
        assertEquals(ModelLoadingStatus.UNLOADING, unloadingState.status)
    }

    @Test
    fun testUnloadCompletion() {
        val unloadingState = ModelLoadingState(
            modelId = "test-model",
            status = ModelLoadingStatus.UNLOADING
        )
        
        val unloadedState = unloadingState.copy(
            status = ModelLoadingStatus.NOT_LOADED,
            loadTimeMs = null  // Clear load time on unload
        )
        
        assertEquals(ModelLoadingStatus.NOT_LOADED, unloadedState.status)
        assertNull(unloadedState.loadTimeMs)
    }

    @Test
    fun testLoadingTimeout() {
        val state = ModelLoadingState(
            modelId = "test-model",
            status = ModelLoadingStatus.LOADING,
            loadProgress = 80
        )
        
        val timeoutState = state.copy(
            status = ModelLoadingStatus.ERROR,
            error = "Loading exceeded 30000ms timeout"
        )
        
        assertEquals(ModelLoadingStatus.ERROR, timeoutState.status)
        assertTrue(timeoutState.error?.contains("timeout") == true)
    }

    @Test
    fun testEstimatedMemoryTracking() {
        val state1 = ModelLoadingState(
            modelId = "model1",
            estimatedMemoryMB = 2_400
        )
        
        val state2 = ModelLoadingState(
            modelId = "model2",
            estimatedMemoryMB = 7_200  // Larger model
        )
        
        assertEquals(2_400, state1.estimatedMemoryMB)
        assertEquals(7_200, state2.estimatedMemoryMB)
    }

    @Test
    fun testLoadTimeMetrics() {
        val loadedState = ModelLoadingState(
            modelId = "test-model",
            status = ModelLoadingStatus.LOADED,
            loadTimeMs = 12_500L
        )
        
        assertEquals(12_500L, loadedState.loadTimeMs)
        assertEquals(12, loadedState.loadTimeMs!! / 1000)  // ~12 seconds
    }

    @Test
    fun testMultipleModelsIndependentStates() {
        val modelA = ModelLoadingState(modelId = "model-a", status = ModelLoadingStatus.NOT_LOADED)
        val modelB = ModelLoadingState(modelId = "model-b", status = ModelLoadingStatus.LOADED)
        
        assertEquals("model-a", modelA.modelId)
        assertEquals("model-b", modelB.modelId)
        assertEquals(ModelLoadingStatus.NOT_LOADED, modelA.status)
        assertEquals(ModelLoadingStatus.LOADED, modelB.status)
    }

    @Test
    fun testProgressBoundary() {
        val state = ModelLoadingState(
            modelId = "test-model",
            status = ModelLoadingStatus.LOADING
        )
        
        // Progress at boundaries
        val minProgress = state.copy(loadProgress = 0)
        val maxProgress = state.copy(loadProgress = 100)
        
        assertEquals(0, minProgress.loadProgress)
        assertEquals(100, maxProgress.loadProgress)
    }

    @Test
    fun testErrorMessagePreservation() {
        val errors = listOf(
            "Model not found",
            "Insufficient memory",
            "Corrupted model file",
            "Network timeout",
            "Permission denied"
        )
        
        for (errorMsg in errors) {
            val errorState = ModelLoadingState(
                modelId = "test-model",
                status = ModelLoadingStatus.ERROR,
                error = errorMsg
            )
            assertEquals(errorMsg, errorState.error)
        }
    }
}
