package dev.aidos.cli

import dev.aidos.api.MockRuntimeClient
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.RuntimeProfile
import dev.aidos.kernel.RuntimeVersion
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for DiagnoseCommand (RFC-0037).
 */
class DiagnoseCommandTest {
    
    @TempDir
    lateinit var tempDir: File
    
    private lateinit var diagnoseCommand: DiagnoseCommand
    private lateinit var mockRuntimeClient: MockRuntimeClient

    @BeforeEach
    fun setUp() {
        // Create a mock runtime client that returns desktop profile
        mockRuntimeClient = object : MockRuntimeClient() {
            override suspend fun profile(): PlatformProfile {
                return PlatformProfile.DESKTOP
            }
        }

        diagnoseCommand = DiagnoseCommand(mockRuntimeClient)
    }

    @Test
    fun testDiagnoseCreatesBundleFile() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath
        
        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        assertTrue(File(bundlePath).exists(), "Bundle file should be created")
    }

    @Test
    fun testBundleContainsManifest() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath
        
        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        ZipFile(bundlePath).use { zip ->
            val entry = zip.getEntry("manifest.json")
            assertNotNull(entry, "Bundle should contain manifest.json")
        }
    }

    @Test
    fun testBundleContainsRedactionReport() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath
        
        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        ZipFile(bundlePath).use { zip ->
            val entry = zip.getEntry("redaction-report.txt")
            assertNotNull(entry, "Bundle should contain redaction-report.txt")
            
            val content = zip.getInputStream(entry).bufferedReader().readText()
            assertTrue(content.contains("Redaction Report"))
            assertTrue(content.contains("excluded by default"))
        }
    }

    @Test
    fun testBundleContainsMetrics() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath
        
        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        ZipFile(bundlePath).use { zip ->
            val entry = zip.getEntry("metrics.json")
            assertNotNull(entry, "Bundle should contain metrics.json")
        }
    }

    @Test
    fun testBundleContainsSchema() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath
        
        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        ZipFile(bundlePath).use { zip ->
            val entry = zip.getEntry("schema.json")
            assertNotNull(entry, "Bundle should contain schema.json")
        }
    }

    @Test
    fun testBundleStructure() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath
        
        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        ZipFile(bundlePath).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            
            // Required files
            assertTrue(entries.contains("manifest.json"))
            assertTrue(entries.contains("metrics.json"))
            assertTrue(entries.contains("schema.json"))
            assertTrue(entries.contains("redaction-report.txt"))
        }
    }

    @Test
    fun testIncludeTraceFlag() {
        val bundlePath = File(tempDir, "diagnostic-trace.zip").absolutePath
        
        runBlocking {
            diagnoseCommand.diagnose(bundlePath, includeTrace = true)
        }

        assertTrue(File(bundlePath).exists())
        // Verification of trace inclusion would require actual trace logs to exist
    }

    @Test
    fun testSelectedRuns() {
        val bundlePath = File(tempDir, "diagnostic-runs.zip").absolutePath
        val selectedRuns = listOf("run-123", "run-456")
        
        runBlocking {
            diagnoseCommand.diagnose(bundlePath, selectedRuns = selectedRuns)
        }

        assertTrue(File(bundlePath).exists())
    }

    @Test
    fun testBundleIsValidZip() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath
        
        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        var isValidZip = false
        try {
            ZipFile(bundlePath).use { zip ->
                isValidZip = zip.size() > 0
            }
        } catch (e: Exception) {
            isValidZip = false
        }

        assertTrue(isValidZip, "Bundle should be a valid ZIP file")
    }

    @Test
    fun testManifestContentIsJson() {
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath
        
        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        ZipFile(bundlePath).use { zip ->
            val entry = zip.getEntry("manifest.json")
            assertNotNull(entry)
            val content = zip.getInputStream(entry).bufferedReader().readText()
            assertTrue(content.contains("timestamp"))
            assertTrue(content.contains("runtime_version") || content.contains("platform"))
        }
    }
}

/**
 * Mock implementation of RuntimeClient for testing.
 * Subclasses can override methods to customize behavior.
 */
open class MockRuntimeClient : dev.aidos.api.RuntimeClient {
    override suspend fun profile(): PlatformProfile = PlatformProfile.DESKTOP
    
    // Add other required methods if needed from the RuntimeClient interface
    override suspend fun version(): RuntimeVersion {
        return RuntimeVersion("0.1.0-alpha", "0.1.0-alpha", PlatformProfile.DESKTOP)
    }
}
