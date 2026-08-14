package dev.aidos.routing

import dev.aidos.kernel.BatteryState
import dev.aidos.kernel.DegradationRung
import dev.aidos.kernel.DeviceSignals
import dev.aidos.kernel.MemoryPressureLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RFC-0045 MVP done-when: rungs 1, 2, 4, and 5 activate on their documented triggers, more than
 * one rung can be active at once (independent axes), and rungs 3/6 (post-MVP) never activate.
 */
class DegradationLadderTest {

    private fun signals(
        memoryPressure: MemoryPressureLevel = MemoryPressureLevel.NONE,
        batteryPercent: Int = 100,
        isCharging: Boolean = true,
    ) = DeviceSignals(memoryPressure, BatteryState(batteryPercent, isCharging))

    @Test
    fun `no pressure and healthy battery activates nothing`() {
        val ladder = DegradationLadder()
        assertTrue(ladder.evaluate(signals()).isEmpty())
    }

    @Test
    fun `background pressure activates only rung 1`() {
        val ladder = DegradationLadder()
        val active = ladder.evaluate(signals(memoryPressure = MemoryPressureLevel.BACKGROUND_PRESSURE))
        assertEquals(setOf(DegradationRung.PAUSE_INDEXING), active.keys)
    }

    @Test
    fun `elevated pressure activates rungs 1 and 2`() {
        val ladder = DegradationLadder()
        val active = ladder.evaluate(signals(memoryPressure = MemoryPressureLevel.ELEVATED))
        assertEquals(setOf(DegradationRung.PAUSE_INDEXING, DegradationRung.UNLOAD_MODEL), active.keys)
    }

    @Test
    fun `critical memory activates rungs 1, 2, and 5 but never rung 3`() {
        val ladder = DegradationLadder()
        val active = ladder.evaluate(signals(memoryPressure = MemoryPressureLevel.CRITICAL))
        assertEquals(
            setOf(DegradationRung.PAUSE_INDEXING, DegradationRung.UNLOAD_MODEL, DegradationRung.PARK_RUNS),
            active.keys,
        )
    }

    @Test
    fun `continued pressure never activates the post-MVP rung 3`() {
        val ladder = DegradationLadder()
        val active = ladder.evaluate(signals(memoryPressure = MemoryPressureLevel.CONTINUED))
        assertTrue(DegradationRung.DROP_KNOWLEDGE_CACHE !in active.keys)
    }

    @Test
    fun `low battery not charging activates rung 4`() {
        val ladder = DegradationLadder(batteryFloorPercent = 20)
        val active = ladder.evaluate(signals(batteryPercent = 15, isCharging = false))
        assertEquals(setOf(DegradationRung.SUSPEND_DEFERRED_WORK), active.keys)
    }

    @Test
    fun `low battery while charging does not activate rung 4`() {
        val ladder = DegradationLadder(batteryFloorPercent = 20)
        val active = ladder.evaluate(signals(batteryPercent = 15, isCharging = true))
        assertTrue(DegradationRung.SUSPEND_DEFERRED_WORK !in active.keys)
    }

    @Test
    fun `battery exactly at the floor does not trigger, one below does`() {
        val ladder = DegradationLadder(batteryFloorPercent = 20)
        assertTrue(
            DegradationRung.SUSPEND_DEFERRED_WORK !in
                ladder.evaluate(signals(batteryPercent = 20, isCharging = false)).keys
        )
        assertTrue(
            DegradationRung.SUSPEND_DEFERRED_WORK in
                ladder.evaluate(signals(batteryPercent = 19, isCharging = false)).keys
        )
    }

    @Test
    fun `memory pressure and low battery are independent axes and can both be active`() {
        val ladder = DegradationLadder(batteryFloorPercent = 20)
        val active = ladder.evaluate(
            signals(memoryPressure = MemoryPressureLevel.CRITICAL, batteryPercent = 5, isCharging = false)
        )
        assertEquals(
            setOf(
                DegradationRung.PAUSE_INDEXING,
                DegradationRung.UNLOAD_MODEL,
                DegradationRung.PARK_RUNS,
                DegradationRung.SUSPEND_DEFERRED_WORK,
            ),
            active.keys,
        )
    }

    @Test
    fun `every active rung carries a non-blank user-visible reason`() {
        val ladder = DegradationLadder()
        val active = ladder.evaluate(
            signals(memoryPressure = MemoryPressureLevel.CRITICAL, batteryPercent = 5, isCharging = false)
        )
        for ((rung, reason) in active) {
            assertTrue(reason.isNotBlank(), "$rung has a blank reason")
        }
    }
}
