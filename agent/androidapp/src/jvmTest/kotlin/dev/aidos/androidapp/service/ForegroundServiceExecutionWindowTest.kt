package dev.aidos.androidapp.service

import dev.aidos.kernel.ExecutionWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for ForegroundServiceExecutionWindow (D24, M27).
 *
 * Verifies that local model inference is permitted only when a Run is actively executing
 * in the foreground service (RunningRun state).
 */
class ForegroundServiceExecutionWindowTest {

    @Test
    fun `permitsLocalInference returns true when service is running`() {
        val state = MutableStateFlow<ServiceState>(ServiceState.RunningRun("run-1", "Running task"))
        val window = ForegroundServiceExecutionWindow(state)
        assertTrue(window.permitsLocalInference())
    }

    @Test
    fun `permitsLocalInference returns false when service is idle`() {
        val state = MutableStateFlow<ServiceState>(ServiceState.Idle)
        val window = ForegroundServiceExecutionWindow(state)
        assertFalse(window.permitsLocalInference())
    }

    @Test
    fun `permitsLocalInference returns false when service is evicted`() {
        val state = MutableStateFlow<ServiceState>(ServiceState.EvictedMidRun("run-1", 5))
        val window = ForegroundServiceExecutionWindow(state)
        assertFalse(window.permitsLocalInference())
    }

    @Test
    fun `permitsLocalInference transitions as state changes`() {
        val state = MutableStateFlow<ServiceState>(ServiceState.Idle)
        val window = ForegroundServiceExecutionWindow(state)

        // Initially idle — no inference
        assertFalse(window.permitsLocalInference())

        // Transition to running — inference allowed
        state.value = ServiceState.RunningRun("run-1", "Running task")
        assertTrue(window.permitsLocalInference())

        // Transition to evicted — inference not allowed
        state.value = ServiceState.EvictedMidRun("run-1", 5)
        assertFalse(window.permitsLocalInference())

        // Transition back to idle — still not allowed
        state.value = ServiceState.Idle
        assertFalse(window.permitsLocalInference())
    }

    @Test
    fun `remainingMillis returns null (unbounded)`() {
        val state = MutableStateFlow<ServiceState>(ServiceState.RunningRun("run-1", "Running task"))
        val window = ForegroundServiceExecutionWindow(state)
        assertTrue(window.remainingMillis() == null)
    }

    @Test
    fun `implements ExecutionWindow interface`() {
        val state = MutableStateFlow<ServiceState>(ServiceState.Idle)
        val window: ExecutionWindow = ForegroundServiceExecutionWindow(state)
        // If this compiles, the interface is correctly implemented.
        assertFalse(window.permitsLocalInference())
    }
}
