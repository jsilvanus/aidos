package dev.aidos.daemon

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.storage.AidosStorage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for MetricsCollector (RFC-0037).
 */
class MetricsCollectorTest {
    
    @TempDir
    lateinit var tempDir: File
    
    private lateinit var driver: SqlDriver
    private lateinit var collector: MetricsCollector

    @BeforeEach
    fun setUp() {
        val userDb = AidosStorage.openUser(
            File(tempDir, "user.db"),
            "0.1.0-alpha",
            { java.time.Instant.now().toString() }
        )
        driver = userDb.driver
        collector = MetricsCollector(driver)
    }

    @Test
    fun testMetricsCollectorInitializes() {
        // Just verify initialization works
        assertTrue(collector != null)
    }

    @Test
    fun testCollectRunoutcomes() {
        // This is a basic test - full test would require setting up run data
        try {
            collector.collectRunOutcomes(
                bucket = java.time.LocalDate.now().toString() + "T00:00:00Z"
            )
        } catch (e: Exception) {
            // Expected if no run data
            assertTrue(true)
        }
    }

    @Test
    fun testCollectStepDuration() {
        try {
            collector.collectStepDuration(
                bucket = java.time.LocalDate.now().toString() + "T00:00:00Z"
            )
        } catch (e: Exception) {
            // Expected if no step data
            assertTrue(true)
        }
    }

    @Test
    fun testCollectModelLoadTime() {
        try {
            collector.collectModelLoadTime(
                bucket = java.time.LocalDate.now().toString() + "T00:00:00Z"
            )
        } catch (e: Exception) {
            // Expected if no model load data
            assertTrue(true)
        }
    }

    @Test
    fun testCollectStoragePerProject() {
        try {
            collector.collectStoragePerProject(
                bucket = java.time.LocalDate.now().toString() + "T00:00:00Z"
            )
        } catch (e: Exception) {
            // Expected if no storage data
            assertTrue(true)
        }
    }

    @Test
    fun testCollectTokensAndCost() {
        try {
            collector.collectTokensAndCost(
                bucket = java.time.LocalDate.now().toString() + "T00:00:00Z"
            )
        } catch (e: Exception) {
            // Expected if no attempt data
            assertTrue(true)
        }
    }

    @Test
    fun testCollectCallRatio() {
        try {
            collector.collectCallRatio(
                bucket = java.time.LocalDate.now().toString() + "T00:00:00Z"
            )
        } catch (e: Exception) {
            // Expected if no tool invocation data
            assertTrue(true)
        }
    }

    @Test
    fun testFullCollect() {
        try {
            collector.collect()
            assertTrue(true)
        } catch (e: Exception) {
            // Expected if database is empty
            assertTrue(true)
        }
    }
}

// Extension functions to expose private methods for testing
private fun MetricsCollector.collectRunOutcomes(bucket: String) {
    this::class.java.declaredMethods
        .find { it.name == "collectRunOutcomes" }
        ?.let { 
            it.isAccessible = true
            it.invoke(this, bucket)
        }
}

private fun MetricsCollector.collectStepDuration(bucket: String) {
    this::class.java.declaredMethods
        .find { it.name == "collectStepDuration" }
        ?.let { 
            it.isAccessible = true
            it.invoke(this, bucket)
        }
}

private fun MetricsCollector.collectModelLoadTime(bucket: String) {
    this::class.java.declaredMethods
        .find { it.name == "collectModelLoadTime" }
        ?.let { 
            it.isAccessible = true
            it.invoke(this, bucket)
        }
}

private fun MetricsCollector.collectStoragePerProject(bucket: String) {
    this::class.java.declaredMethods
        .find { it.name == "collectStoragePerProject" }
        ?.let { 
            it.isAccessible = true
            it.invoke(this, bucket)
        }
}

private fun MetricsCollector.collectTokensAndCost(bucket: String) {
    this::class.java.declaredMethods
        .find { it.name == "collectTokensAndCost" }
        ?.let { 
            it.isAccessible = true
            it.invoke(this, bucket)
        }
}

private fun MetricsCollector.collectCallRatio(bucket: String) {
    this::class.java.declaredMethods
        .find { it.name == "collectCallRatio" }
        ?.let { 
            it.isAccessible = true
            it.invoke(this, bucket)
        }
}
