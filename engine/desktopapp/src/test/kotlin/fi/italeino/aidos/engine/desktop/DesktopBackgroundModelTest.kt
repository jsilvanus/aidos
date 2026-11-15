package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.desktop.install.OsFamily
import fi.italeino.aidos.engine.desktop.install.ServiceInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopBackgroundModelTest {
    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(25)
        assertTrue(cond(), what)
    }

    private fun withHome(block: (CoroutineScope) -> Unit) {
        val home = Files.createTempDirectory("aidos-bg").toFile()
        val old = System.getProperty("user.home")
        System.setProperty("user.home", home.path)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { block(scope) } finally {
            System.setProperty("user.home", old); home.deleteRecursively(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `the switch registers and removes the per-user services`() = withHome { scope ->
        val ran = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        val model = DesktopBackgroundModel(scope, ServiceInstaller(run = { ran += it; 0 }, isElevated = { false }), OsFamily.LINUX)
        waitFor("initial status") { model.state.value.services.size == 2 }
        assertFalse(model.state.value.enabled)

        model.setEnabled(true)
        waitFor("registered") { model.state.value.enabled }
        assertNull(model.state.value.error)
        assertTrue(ran.any { it.contains("enable") })

        model.setEnabled(false)
        waitFor("removed") { !model.state.value.busy && model.state.value.services.none { it.registered } }
        assertNull(model.state.value.error)
    }

    @Test
    fun `a failing service manager shows as an error and leaves nothing registered`() = withHome { scope ->
        val model = DesktopBackgroundModel(scope, ServiceInstaller(run = { 1 }, isElevated = { false }), OsFamily.LINUX)
        waitFor("initial") { model.state.value.services.size == 2 }
        model.setEnabled(true)
        waitFor("error shown") { model.state.value.error != null }
        assertNotNull(model.state.value.error)
        assertTrue(model.state.value.services.none { it.registered })
    }
}
