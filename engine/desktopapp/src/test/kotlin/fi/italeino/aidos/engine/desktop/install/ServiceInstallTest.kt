package fi.italeino.aidos.engine.desktop.install

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceInstallTest {
    private val user = ServiceSpec("engine", "Aidos Engine", listOf("/opt/java/bin/java", "-cp", "/a b/x.jar", "Main", "--daemon"), InstallScope.USER)
    private val system = ServiceSpec("engine", "Aidos Engine", listOf("/opt/java/bin/java", "Main", "--daemon"), InstallScope.SYSTEM, "juha")

    @Test
    fun `systemd user unit runs as the user and quotes spaces`() {
        val p = ServiceDefinitions.plan(user, OsFamily.LINUX, home = "/home/juha")
        assertEquals("/home/juha/.config/systemd/user/aidos-engine.service", p.files.single().path)
        val c = p.files.single().content
        assertTrue(c.contains("ExecStart=/opt/java/bin/java -cp \"/a b/x.jar\" Main --daemon"))
        assertTrue(c.contains("WantedBy=default.target") && !c.contains("User="))
        assertEquals(listOf("systemctl", "--user", "enable", "--now", "aidos-engine.service"), p.install.last())
        assertFalse(p.needsElevation)
    }

    @Test
    fun `systemd system unit runs as the named user and never as root`() {
        val p = ServiceDefinitions.plan(system, OsFamily.LINUX, home = "/home/juha")
        assertEquals("/etc/systemd/system/aidos-engine.service", p.files.single().path)
        assertTrue(p.files.single().content.contains("User=juha"))
        assertTrue(p.files.single().content.contains("WantedBy=multi-user.target"))
        assertTrue(p.needsElevation)
        assertFailsWith<IllegalArgumentException> { system.copy(runAsUser = "root") }
        assertFailsWith<IllegalArgumentException> { system.copy(runAsUser = null) }
    }

    @Test
    fun `launchd agent and daemon`() {
        val a = ServiceDefinitions.plan(user, OsFamily.MACOS, home = "/Users/juha", uid = "501")
        assertEquals("/Users/juha/Library/LaunchAgents/fi.italeino.aidos.engine.plist", a.files.single().path)
        assertEquals(listOf("launchctl", "bootstrap", "gui/501", a.files.single().path), a.install.single())
        assertTrue(a.files.single().content.contains("<string>--daemon</string>") && !a.files.single().content.contains("UserName"))
        val d = ServiceDefinitions.plan(system, OsFamily.MACOS, home = "/Users/juha", uid = "501")
        assertTrue(d.files.single().path.startsWith("/Library/LaunchDaemons/"))
        assertTrue(d.files.single().content.contains("<key>UserName</key>\n  <string>juha</string>"))
        assertEquals("system", d.install.single()[2])
    }

    @Test
    fun `windows scheduled task at logon or at startup as the named user`() {
        val u = ServiceDefinitions.plan(user, OsFamily.WINDOWS)
        assertTrue(u.files.isEmpty())
        val create = u.install.first()
        assertEquals("ONLOGON", create[create.indexOf("/SC") + 1])
        assertTrue(create.contains("Aidos engine"))
        val s = ServiceDefinitions.plan(system, OsFamily.WINDOWS).install.first()
        assertEquals("ONSTART", s[s.indexOf("/SC") + 1])
        assertEquals("juha", s[s.indexOf("/RU") + 1])
        assertEquals("*", s[s.indexOf("/RP") + 1])
    }

    @Test
    fun `a failed install removes the files it wrote`() {
        val dir = Files.createTempDirectory("aidos-install").toFile()
        try {
            val plan = ServicePlan(
                files = listOf(ServiceFile(dir.resolve("sub/unit").path, "x")),
                install = listOf(listOf("ok"), listOf("boom")), uninstall = emptyList(),
                removeFiles = listOf(dir.resolve("sub/unit").path), needsElevation = false,
            )
            val ran = mutableListOf<List<String>>()
            val r = ServiceInstaller(run = { ran += it; if (it == listOf("boom")) 1 else 0 }).install(plan)
            assertEquals(ServiceInstaller.Result.Failed(listOf("boom"), 1), r)
            assertFalse(dir.resolve("sub/unit").exists())
            val ok = ServiceInstaller(run = { 0 }).install(plan.copy(install = listOf(listOf("ok"))))
            assertEquals(ServiceInstaller.Result.Installed, ok)
            assertTrue(dir.resolve("sub/unit").isFile)
            ServiceInstaller(run = { 0 }).uninstall(plan)
            assertFalse(dir.resolve("sub/unit").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `a machine-wide install asks for an elevated shell and writes nothing otherwise`() {
        val plan = ServiceDefinitions.plan(system, OsFamily.LINUX, home = "/h")
        val ran = mutableListOf<List<String>>()
        val r = ServiceInstaller(run = { ran += it; 0 }, isElevated = { false }).install(plan)
        assertTrue(r is ServiceInstaller.Result.NeedsElevation)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `the cli rejects a system tray and unknown services`() {
        val out = mutableListOf<String>()
        assertEquals(2, InstallCli.run(listOf("--install-service", "tray", "--system"), out::add))
        assertEquals(2, InstallCli.run(listOf("--install-service", "bogus"), out::add))
        assertEquals(2, InstallCli.run(listOf("--install-service", "engine", "--system"), out::add), "system needs --run-as")
        assertEquals(3, InstallCli.run(listOf("--install-service", "engine", "--system", "--run-as", "juha"), out::add, ServiceInstaller(run = { 0 }, isElevated = { false }), OsFamily.LINUX))
    }

    @Test
    fun `the agent daemon service takes its own command line`() {
        val spec = InstallCli.specFor("agent", listOf("--install-service", "agent", "--exec", "java", "-cp", "d.jar", "dev.aidos.daemon.MainKt", "--system"))
        assertEquals(listOf("java", "-cp", "d.jar", "dev.aidos.daemon.MainKt", "--system"), spec.command, "flags after --exec belong to the daemon")
        assertEquals(InstallScope.USER, spec.scope)
        assertFailsWith<IllegalArgumentException> { InstallCli.specFor("agent", listOf("--install-service", "agent")) }
    }

    @Test
    fun `a machine-wide Engine is told to use a passphrase vault and a per-user one is not`() {
        val sys = InstallCli.specFor("engine", listOf("--install-service", "engine", "--system", "--run-as", "juha"))
        assertEquals(mapOf("AIDOS_VAULT_MODE" to "passphrase"), sys.env)
        assertTrue(ServiceDefinitions.plan(sys, OsFamily.LINUX, home = "/h").files.single().content.contains("Environment=\"AIDOS_VAULT_MODE=passphrase\""))
        val plist = ServiceDefinitions.plan(sys, OsFamily.MACOS, home = "/h", uid = "501").files.single().content
        assertTrue(plist.contains("<key>EnvironmentVariables</key>") && plist.contains("<string>passphrase</string>"))
        assertTrue(InstallCli.specFor("engine", listOf("--install-service", "engine")).env.isEmpty())
        assertFailsWith<IllegalArgumentException> { user.copy(env = mapOf("bad name" to "x")) }
    }

    @Test
    fun `a machine-wide Agent daemon also starts locked with a passphrase vault`() {
        val sys = InstallCli.specFor("agent", listOf("--install-service", "agent", "--system", "--run-as", "juha", "--exec", "java", "-cp", "d.jar", "dev.aidos.daemon.MainKt"))
        assertEquals(mapOf("AIDOS_VAULT_MODE" to "passphrase"), sys.env)
        assertEquals(listOf("java", "-cp", "d.jar", "dev.aidos.daemon.MainKt"), sys.command)
        assertTrue(InstallCli.specFor("agent", listOf("--install-service", "agent", "--exec", "java")).env.isEmpty())
    }
}
