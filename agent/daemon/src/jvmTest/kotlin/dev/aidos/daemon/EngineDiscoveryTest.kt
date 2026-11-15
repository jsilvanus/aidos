package dev.aidos.daemon

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EngineDiscoveryTest {
    private fun file(json: String) = Files.createTempFile("engine", ".json").toFile().also { it.writeText(json) }
    private val ready = """{"status":"READY","port":18080,"baseUrl":"http://127.0.0.1:18080","token":"tok","pid":42}"""

    @Test
    fun `the Engine's discovery file supplies the URL and token`() {
        val d = EngineDiscovery(file(ready), env = { null }, isAlive = { true })
        assertEquals("http://127.0.0.1:18080", d.baseUrl())
        assertEquals("tok", d.token())
    }

    @Test
    fun `an explicit environment setting wins over the file`() {
        val env = mapOf("AIDOS_ENGINE_URL" to "http://engine.example:1", "AIDOS_ENGINE_TOKEN" to "env-tok")
        val d = EngineDiscovery(file(ready), env = env::get, isAlive = { true })
        assertEquals("http://engine.example:1", d.baseUrl())
        assertEquals("env-tok", d.token())
    }

    @Test
    fun `a file left by a crashed Engine, a missing file or a stopped Engine yield nothing`() {
        assertNull(EngineDiscovery(file(ready), env = { null }, isAlive = { false }).baseUrl(), "pid no longer alive")
        assertNull(EngineDiscovery(file("""{"status":"STOPPED","baseUrl":"http://x","token":"t"}"""), env = { null }, isAlive = { true }).baseUrl())
        assertNull(EngineDiscovery(file("not json"), env = { null }, isAlive = { true }).token())
        val missing = Files.createTempDirectory("ed").resolve("engine.json").toFile()
        assertNull(EngineDiscovery(missing, env = { null }).baseUrl())
    }
}
