package dev.aidos.models

import dev.aidos.downloads.DownloadEvent
import dev.aidos.downloads.DownloadManager
import dev.aidos.kernel.ModelKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageInstallTest {
    private val root: File = Files.createTempDirectory("pkg-install").toFile().also { it.deleteOnExit() }

    /** Writes a small file per URL; a URL containing "FAIL" fails after earlier files are on disk. */
    private inner class FakeDownloads : DownloadManager {
        override fun download(url: String, destination: String, expectedDigest: String?): Flow<DownloadEvent> = flow {
            if ("FAIL" in url) { emit(DownloadEvent.Failed("HTTP 404", false)); return@flow }
            val f = File(destination); f.parentFile.mkdirs(); f.writeText(url)
            emit(DownloadEvent.Completed(f.absolutePath, "d-${f.name}", f.length()))
        }
        override suspend fun canResume(destination: String, url: String) = false
        override suspend fun delete(destination: String) { File(destination).delete() }
        override suspend fun deleteTree(destination: String) { File(destination).deleteRecursively() }
    }

    private class FakeCatalog : ModelCatalogManager {
        val installed = mutableListOf<InstalledModel>()
        override suspend fun addToCatalog(model: CatalogEntry) = Result.success(Unit)
        override suspend fun listCatalog() = Result.success(emptyList<CatalogEntry>())
        override suspend fun getCatalog(modelId: String) = Result.success<CatalogEntry?>(null)
        override suspend fun listInstalled() = Result.success(installed.toList())
        override suspend fun markInstalled(modelId: String, digest: String, path: String, sizeBytes: Long, quantization: String?): Result<Unit> {
            installed += InstalledModel(modelId, digest, path, sizeBytes, quantization, ""); return Result.success(Unit)
        }
        override suspend fun uninstall(modelId: String): Result<Unit> { installed.removeAll { it.modelId == modelId }; return Result.success(Unit) }
        override suspend fun updateInstalledMetadata(modelId: String, userLabel: String?, propertiesJson: String?) = Result.success(Unit)
    }

    private fun request(dir: File, vararg urls: Pair<String, String>) = ModelPackageDownloadRequest(
        modelId = "laya-onnx",
        files = urls.map { (name, url) -> PackageFileRequest(name, url) },
        destinationDir = dir.absolutePath,
        kind = ModelKind.DECISION,
        format = "onnx",
        backend = "onnx-runtime",
    )

    @Test
    fun installsEveryFileIntoOneDirectoryAndUninstallRemovesTheDirectory() = runBlocking {
        val catalog = FakeCatalog()
        val workflow = DefaultModelInstallerWorkflow(FakeDownloads(), catalog)
        val dir = File(root, "laya-onnx")

        val result = workflow.installPackage(request(dir, "model.onnx" to "u/model.onnx", "tokenizer.json" to "u/tok", "rl_agent_config.json" to "u/cfg")).getOrThrow()

        assertEquals(setOf("model.onnx", "tokenizer.json", "rl_agent_config.json"), dir.list()!!.toSet())
        assertEquals(dir.absolutePath, result.path)
        assertTrue("model.onnx:d-model.onnx" in result.digest)
        assertEquals(1, catalog.installed.size)

        workflow.uninstall("laya-onnx").getOrThrow()
        assertFalse(dir.exists(), "uninstall must remove the whole package directory")
        assertTrue(catalog.installed.isEmpty())
    }

    @Test
    fun aFailedFileLeavesNoHalfInstalledPackage() = runBlocking {
        val catalog = FakeCatalog()
        val dir = File(root, "half")
        val result = DefaultModelInstallerWorkflow(FakeDownloads(), catalog)
            .installPackage(request(dir, "tokenizer.json" to "u/tok", "model.onnx_data" to "u/FAIL"))

        assertTrue(result.isFailure)
        assertFalse(dir.exists())
        assertTrue(catalog.installed.isEmpty(), "nothing is recorded as installed")
    }

    @Test
    fun refusesFileNamesThatCouldEscapeTheDirectory() = runBlocking {
        val workflow = DefaultModelInstallerWorkflow(FakeDownloads(), FakeCatalog())
        for (bad in listOf("../evil", "a/b", "..", "")) {
            assertTrue(workflow.installPackage(request(File(root, "x"), bad to "u/x")).isFailure, "'$bad'")
        }
        assertFalse(File(root, "evil").exists())
    }
}
