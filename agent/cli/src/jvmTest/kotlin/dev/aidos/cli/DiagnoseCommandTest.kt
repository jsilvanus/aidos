package dev.aidos.cli

import dev.aidos.api.MockRuntimeClient
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.RuntimeVersion
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Tests for DiagnoseCommand (RFC-0037).
 */
class DiagnoseCommandTest {
    
    private lateinit var tempDir: File
    private lateinit var diagnoseCommand: DiagnoseCommand
    private lateinit var mockRuntimeClient: MockRuntimeClient

    private fun setUp() {
        // Create a mock runtime client that returns desktop profile
        mockRuntimeClient = MockRuntimeClient()

        diagnoseCommand = DiagnoseCommand(mockRuntimeClient)
    }

    @Test
    fun testDiagnoseCreatesBundleFile() {
        setUp()
        tempDir = File.createTempFile("diagnostic", "").parentFile
        val bundlePath = File(tempDir, "diagnostic.zip").absolutePath
        
        runBlocking {
            diagnoseCommand.diagnose(bundlePath)
        }

        assertTrue(File(bundlePath).exists(), "Bundle file should be created")
    }

    @Test
    fun testBundleContainsManifest() {
        setUp()
        tempDir = File.createTempFile("diagnostic", "").parentFile
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
        setUp()
        tempDir = File.createTempFile("diagnostic", "").parentFile
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
        setUp()
        tempDir = File.createTempFile("diagnostic", "").parentFile
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
        setUp()
        tempDir = File.createTempFile("diagnostic", "").parentFile
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
        setUp()
        tempDir = File.createTempFile("diagnostic", "").parentFile
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
        setUp()
        tempDir = File.createTempFile("diagnostic", "").parentFile
        val bundlePath = File(tempDir, "diagnostic-trace.zip").absolutePath
        
        runBlocking {
            diagnoseCommand.diagnose(bundlePath, includeTrace = true)
        }

        assertTrue(File(bundlePath).exists())
        // Verification of trace inclusion would require actual trace logs to exist
    }

    @Test
    fun testSelectedRuns() {
        setUp()
        tempDir = File.createTempFile("diagnostic", "").parentFile
        val bundlePath = File(tempDir, "diagnostic-runs.zip").absolutePath
        val selectedRuns = listOf("run-123", "run-456")
        
        runBlocking {
            diagnoseCommand.diagnose(bundlePath, selectedRuns = selectedRuns)
        }

        assertTrue(File(bundlePath).exists())
    }

    @Test
    fun testBundleIsValidZip() {
        setUp()
        tempDir = File.createTempFile("diagnostic", "").parentFile
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
        setUp()
        tempDir = File.createTempFile("diagnostic", "").parentFile
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
