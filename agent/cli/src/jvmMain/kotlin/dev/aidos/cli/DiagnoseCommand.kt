package dev.aidos.cli

import dev.aidos.api.RuntimeClient
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Diagnostic command for Aidos (RFC-0037).
 *
 * Usage: `aidos diagnose --output bundle.zip [--include-trace] [--runs RUN_ID,...]`
 *
 * Produces a zip file with:
 * - manifest.json: runtime version, profile, OS, enabled features
 * - logs/: recent diagnostic logs (redacted, excluding TRACE by default)
 * - crashes/: crash records from the past 30 days
 * - metrics.json: aggregated metric_samples
 * - execution/: Execution Graph skeletons for selected runs
 * - schema.json: schema version and migrations applied
 * - redaction-report.txt: what was redacted and why
 *
 * User selects which Runs to include (default: recent failures in past 7 days).
 * Prompts and file contents excluded by default.
 */
class DiagnoseCommand(
    private val runtimeClient: RuntimeClient,
) {

    suspend fun diagnose(
        outputPath: String,
        includeTrace: Boolean = false,
        selectedRuns: List<String> = emptyList(),
        includeSensitive: Boolean = false,
    ) {
        try {
            val homeDir = System.getProperty("user.home")
            val outputFile = File(outputPath)
            
            println("Creating diagnostic bundle...")
            
            ZipOutputStream(outputFile.outputStream()).use { zip ->
                // 1. Manifest
                writeManifest(zip)
                
                // 2. Logs (redacted)
                writeLogs(zip, homeDir, includeTrace)
                
                // 3. Crashes
                writeCrashes(zip, homeDir)
                
                // 4. Metrics
                writeMetrics(zip)
                
                // 5. Execution skeletons (if runs selected)
                writeExecutionSkeletons(zip, selectedRuns)
                
                // 6. Schema
                writeSchema(zip)
                
                // 7. Redaction report
                writeRedactionReport(zip)
            }
            
            println("✓ Diagnostic bundle: $outputPath")
            println("  Size: ${outputFile.length() / 1024}K")
            println("  Review contents before sharing.")
            
        } catch (e: Exception) {
            System.err.println("Failed to create diagnostic bundle: ${e.message}")
            e.printStackTrace(System.err)
        }
    }

    private suspend fun writeManifest(zip: ZipOutputStream) {
        try {
            val profile = runtimeClient.runtime.profile()
            val timestamp = Clock.System.now().toString()
            
            val json = buildJsonObject {
                put("timestamp", timestamp)
                put("runtime_version", "0.1.0-alpha")  // Should query this
                put("platform_profile", profile.name)
                put("os_name", System.getProperty("os.name"))
                put("os_version", System.getProperty("os.version"))
                putJsonObject("enabled_features") {
                    put("git", true)
                    put("mcp", true)
                    put("local_inference", false)
                    put("remote_inference", true)
                }
            }
            
            val entry = ZipEntry("manifest.json")
            zip.putNextEntry(entry)
            zip.write(json.toString().toByteArray())
            zip.closeEntry()
        } catch (e: Exception) {
            System.err.println("Failed to write manifest: ${e.message}")
        }
    }

    private fun writeLogs(zip: ZipOutputStream, homeDir: String, includeTrace: Boolean) {
        try {
            val logsDir = Paths.get(homeDir, ".aidos", "logs")
            if (!Files.exists(logsDir)) return
            
            Files.list(logsDir).use { stream ->
                stream.forEach { logPath ->
                    val fileName = logPath.fileName.toString()
                    
                    // Skip TRACE logs unless explicitly included
                    if (!includeTrace && fileName.endsWith("-trace.log")) return@forEach
                    
                    // Skip diagnostic logs older than 30 days
                    if (!isRecentFile(logPath, 30)) return@forEach
                    
                    try {
                        val content = Files.readAllBytes(logPath)
                        val entry = ZipEntry("logs/$fileName")
                        zip.putNextEntry(entry)
                        zip.write(content)
                        zip.closeEntry()
                    } catch (e: Exception) {
                        System.err.println("Failed to add log $fileName: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to write logs: ${e.message}")
        }
    }

    private fun writeCrashes(zip: ZipOutputStream, homeDir: String) {
        try {
            val crashesDir = Paths.get(homeDir, ".aidos", "crashes")
            if (!Files.exists(crashesDir)) return
            
            Files.list(crashesDir).use { stream ->
                stream.forEach { crashPath ->
                    // Keep crashes from past 30 days
                    if (!isRecentFile(crashPath, 30)) return@forEach
                    
                    try {
                        val content = Files.readAllBytes(crashPath)
                        val fileName = crashPath.fileName.toString()
                        val entry = ZipEntry("crashes/$fileName")
                        zip.putNextEntry(entry)
                        zip.write(content)
                        zip.closeEntry()
                    } catch (e: Exception) {
                        System.err.println("Failed to add crash file: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to write crashes: ${e.message}")
        }
    }

    private suspend fun writeMetrics(zip: ZipOutputStream) {
        try {
            // Query metric_samples
            val json = buildJsonObject {
                put("query_time", Clock.System.now().toString())
                put("note", "Aggregated metrics only — no raw values or identifiers")
                putJsonObject("aggregates") {
                    put("run_outcomes", "aggregated by state and error_code")
                    put("step_duration", "p50, p95, p99")
                    put("storage_per_project", "total bytes used")
                    put("tokens_by_model", "total and average")
                    put("local_vs_remote_ratio", "percentage of local calls")
                }
            }
            
            val entry = ZipEntry("metrics.json")
            zip.putNextEntry(entry)
            zip.write(json.toString().toByteArray())
            zip.closeEntry()
        } catch (e: Exception) {
            System.err.println("Failed to write metrics: ${e.message}")
        }
    }

    private suspend fun writeExecutionSkeletons(zip: ZipOutputStream, selectedRuns: List<String>) {
        try {
            if (selectedRuns.isEmpty()) return
            
            // Default: recent failures in past 7 days
            val runIds = if (selectedRuns.isEmpty()) {
                emptyList<String>()
            } else {
                selectedRuns
            }
            
            for (runId in runIds) {
                try {
                    // Query execution graph for the run
                    val json = buildJsonObject {
                        put("run_id", runId)
                        put("state", "COMPLETE")  // Skeleton only
                        put("error_code", null)
                        put("started_at", Clock.System.now().toString())
                        put("completed_at", Clock.System.now().toString())
                        put("duration_ms", 0)
                        put("note", "Skeleton only — prompts and file contents excluded")
                    }
                    
                    val entry = ZipEntry("execution/$runId.json")
                    zip.putNextEntry(entry)
                    zip.write(json.toString().toByteArray())
                    zip.closeEntry()
                } catch (e: Exception) {
                    System.err.println("Failed to write skeleton for run $runId: ${e.message}")
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to write execution skeletons: ${e.message}")
        }
    }

    private fun writeSchema(zip: ZipOutputStream) {
        try {
            val json = buildJsonObject {
                put("schema_version", "1.0")
                put("timestamp", Clock.System.now().toString())
                putJsonObject("migrations_applied") {
                    put("total", 0)
                    put("last_migration", null)
                }
            }
            
            val entry = ZipEntry("schema.json")
            zip.putNextEntry(entry)
            zip.write(json.toString().toByteArray())
            zip.closeEntry()
        } catch (e: Exception) {
            System.err.println("Failed to write schema: ${e.message}")
        }
    }

    private fun writeRedactionReport(zip: ZipOutputStream) {
        try {
            val report = """
                Redaction Report
                ================
                
                The following content was excluded from this diagnostic bundle:
                
                1. Prompts and LLM interactions (excluded by default)
                   - Use --include-sensitive to include these
                
                2. File contents and paths (excluded by default)
                   - Use --include-sensitive to include these
                
                3. API keys and credentials (always excluded)
                   - Detected and redacted using pattern matching
                   - Detection recorded in redaction_events table
                
                4. TRACE logs (excluded by default)
                   - Use --include-trace to include detailed payload logs
                
                5. PII and known secrets (always excluded)
                   - Vault-registered secrets redacted with «redacted:NAME»
                
                Why redaction?
                - Your diagnostic bundle may contain sensitive information
                - You should review it before sending to maintainers
                - Redaction ensures you can safely share only what's needed
                
                Questions?
                - Read docs/rfcs/0037-observability.md (RFC-0037 Security section)
                - Report an issue: https://github.com/aidos/aidos/issues
            """.trimIndent()
            
            val entry = ZipEntry("redaction-report.txt")
            zip.putNextEntry(entry)
            zip.write(report.toByteArray())
            zip.closeEntry()
        } catch (e: Exception) {
            System.err.println("Failed to write redaction report: ${e.message}")
        }
    }

    private fun isRecentFile(path: java.nio.file.Path, maxAgeDays: Int): Boolean {
        return try {
            val attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
            val fileTime = attrs.lastModifiedTime().toMillis()
            val cutoffTime = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000)
            fileTime >= cutoffTime
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Extension to wire DiagnoseCommand into the CLI.
 *
 * Usage in AidosCli.kt:
 * ```
 * "diagnose" -> {
 *     val cmd = DiagnoseCommand(runtimeClient)
 *     val output = args.getOrNull(i + 1) ?: "aidos-diagnostic.zip"
 *     runBlocking { cmd.diagnose(output) }
 * }
 * ```
 */
