package dev.aidos.androidapp.service

import dev.aidos.ui.system.NativeProcessHealth
import org.junit.Test
import kotlin.test.assertEquals

class NativeInferenceConnectionMonitorTest {

    @Test
    fun testDeadObjectTriggersWarmRestartAndRecovery() {
        var restartCalls = 0
        val monitor = NativeInferenceConnectionMonitor(
            onRequestWarmRestart = { restartCalls++ }
        )

        assertEquals(NativeProcessHealth.HEALTHY, monitor.health.value)
        assertEquals(0, monitor.restartCount)

        monitor.onConnected()
        assertEquals(NativeProcessHealth.HEALTHY, monitor.health.value)

        // Simulate DeadObjectException / Binder death in :native_inference
        monitor.onDeadObjectException()
        assertEquals(NativeProcessHealth.CRASHED_WARM_RESTARTING, monitor.health.value)
        assertEquals(1, restartCalls)
        assertEquals(1, monitor.restartCount)

        // Simulate service rebinding
        monitor.onConnected()
        assertEquals(NativeProcessHealth.RECOVERED, monitor.health.value)

        // User/timer acknowledges recovery
        monitor.acknowledgeRecovered()
        assertEquals(NativeProcessHealth.HEALTHY, monitor.health.value)
    }
}
