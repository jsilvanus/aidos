package dev.aidos.portability

import java.io.File
import java.security.KeyPair

/** Where the CLI reads passwords from and writes to; a seam so tests need no terminal. */
interface CliIo {
    fun out(line: String)
    fun err(line: String)
    fun password(prompt: String): CharArray?
    fun env(name: String): String?
}

object SystemCliIo : CliIo {
    override fun out(line: String) = println(line)
    override fun err(line: String) = System.err.println(line)
    override fun password(prompt: String): CharArray? = System.console()?.readPassword(prompt)
    override fun env(name: String): String? = System.getenv(name)
}

/**
 * Where export signing keys live when they are not in a key file: the secrets vault (RFC-0035).
 * Nothing here ever returns a key to anything but the signer.
 */
interface SigningKeyStore {
    /** Stores [text] (private line, newline, public line) under [name]; fails if [name] exists. */
    fun save(name: String, text: String)
    /** The stored text, or null if there is no such key. */
    fun load(name: String): String?
}

/**
 * `export`, `import` and `keygen` for the CLI (RFC-0041). These act on local files, so they need
 * no daemon. Returns a process exit code: 0 ok, 1 refused (a [PortabilityException]), 2 usage.
 *
 * ```
 * export <projectDir> [--output FILE] [--encrypt] [--sign KEYFILE] [--overwrite] [--compress 0-9]
 * import <archive> [--into DIR] [--rename | --replace] [--name NAME]
 *                  [--require-signature] [--trust FINGERPRINT]...
 * keygen <prefix>      writes <prefix>.key (private+public, keep secret) and <prefix>.pub
 * keygen --vault NAME [pubfile]   keeps the private key in the secrets vault (RFC-0035), never on
 *                      disk; export it with `--sign vault:NAME`. Optionally writes the public key.
 * ```
 * The password comes from `AIDOS_ARCHIVE_PASSWORD` or an interactive prompt, never from argv.
 */
object PortabilityCli {
    val COMMANDS = setOf("export", "import", "keygen")

    fun run(
        args: List<String>, registry: ProjectRegistryPort, io: CliIo = SystemCliIo,
        cwd: File = File("."), exportedBy: String = System.getProperty("user.name") ?: "unknown",
        deviceId: String = "unknown", newId: () -> String = { java.util.UUID.randomUUID().toString() },
        kdfIterations: Int = ArchiveCrypto.DEFAULT_ITERATIONS,
        keys: SigningKeyStore? = null,
        scanner: SecretScanner? = null,
    ): Int {
        if (args.isEmpty() || args[0] !in COMMANDS) return usage(io)
        return try {
            when (args[0]) {
                "export" -> doExport(args.drop(1), io, cwd, exportedBy, deviceId, newId, kdfIterations, keys, scanner)
                "import" -> doImport(args.drop(1), registry, io, cwd, exportedBy, deviceId, newId)
                else -> doKeygen(args.drop(1), io, cwd, keys)
            }
        } catch (e: PortabilityException) {
            io.err("aidos: ${e.code}: ${e.message}"); 1
        } catch (e: UsageError) {
            io.err("aidos: ${e.message}"); usage(io)
        }
    }

    private class UsageError(message: String) : Exception(message)

    private class Flags(args: List<String>, valued: Set<String>, switches: Set<String>) {
        val positional = mutableListOf<String>()
        val values = mutableMapOf<String, MutableList<String>>()
        val on = mutableSetOf<String>()
        init {
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a in valued -> { values.getOrPut(a) { mutableListOf() } += args.getOrNull(i + 1) ?: throw UsageError("$a needs a value"); i += 2 }
                    a in switches -> { on += a; i++ }
                    a.startsWith("--") -> throw UsageError("unknown option $a")
                    else -> { positional += a; i++ }
                }
            }
        }
        fun one(n: String) = values[n]?.last()
    }

    private fun resolve(cwd: File, p: String) = File(p).let { if (it.isAbsolute) it else File(cwd, p) }

    private fun doExport(a: List<String>, io: CliIo, cwd: File, by: String, device: String, newId: () -> String, kdf: Int, keys: SigningKeyStore?, scanner: SecretScanner?): Int {
        val f = Flags(a, setOf("--output", "--sign", "--compress"), setOf("--encrypt", "--overwrite"))
        val dir = resolve(cwd, f.positional.singleOrNull() ?: throw UsageError("export needs one project directory"))
        val name = dir.absoluteFile.normalize().name
        val out = f.one("--output")?.let { resolve(cwd, it) }
            ?: File(cwd, "$name-${java.time.LocalDate.now()}${ArchiveFormat.EXTENSION}")
        val password = if ("--encrypt" in f.on) {
            val p = io.env("AIDOS_ARCHIVE_PASSWORD")?.toCharArray() ?: io.password("Archive password: ")
                ?: throw UsageError("--encrypt needs AIDOS_ARCHIVE_PASSWORD or a terminal")
            if (io.env("AIDOS_ARCHIVE_PASSWORD") == null && !p.contentEquals(io.password("Repeat password: ") ?: CharArray(0))) {
                throw UsageError("passwords do not match")
            }
            if (p.isEmpty()) throw UsageError("empty password")
            p
        } else null
        val key = f.one("--sign")?.let { spec -> if (spec.startsWith(VAULT_PREFIX)) readVaultKey(spec.removePrefix(VAULT_PREFIX), keys) else readKey(resolve(cwd, spec)) }
        val r = ProjectExporter.export(
            dir, ExportOptions(
                output = out, password = password, signingKey = key, exportedBy = by, actorId = by, deviceId = device,
                compression = f.one("--compress")?.toIntOrNull() ?: 6, overwrite = "--overwrite" in f.on,
                newId = newId, kdfIterations = kdf, secretScanner = scanner,
            ),
        )
        io.out("exported ${r.projectName} (${r.projectId}): ${r.files} files, ${r.archiveBytes} bytes -> ${r.file.path}")
        io.out("  encrypted: ${r.encrypted}  signed: ${r.signerFingerprint ?: "no"}")
        r.warnings.forEach { io.err("  warning: $it") }
        return 0
    }

    private fun doImport(a: List<String>, reg: ProjectRegistryPort, io: CliIo, cwd: File, by: String, device: String, newId: () -> String): Int {
        val f = Flags(a, setOf("--into", "--name", "--trust"), setOf("--rename", "--replace", "--require-signature"))
        if ("--rename" in f.on && "--replace" in f.on) throw UsageError("--rename and --replace are exclusive")
        val archive = resolve(cwd, f.positional.singleOrNull() ?: throw UsageError("import needs one archive"))
        val head = if (archive.isFile) archive.inputStream().use { it.readNBytes(16) } else ByteArray(0)
        val password = if (ArchiveCrypto.isEncrypted(head)) {
            io.env("AIDOS_ARCHIVE_PASSWORD")?.toCharArray() ?: io.password("Archive password: ")
                ?: throw UsageError("the archive is encrypted; set AIDOS_ARCHIVE_PASSWORD or use a terminal")
        } else null
        val target = f.one("--into")?.let { resolve(cwd, it) } ?: File(cwd, archive.name.removeSuffix(ArchiveFormat.EXTENSION))
        val r = ProjectImporter.import(
            ImportOptions(
                archive = archive, targetDir = target, registry = reg, password = password,
                onConflict = when { "--rename" in f.on -> ConflictPolicy.RENAME; "--replace" in f.on -> ConflictPolicy.REPLACE; else -> ConflictPolicy.REFUSE },
                newName = f.one("--name"), requireSignature = "--require-signature" in f.on,
                trustedSigners = f.values["--trust"]?.toSet() ?: emptySet(), actorId = by, deviceId = device, newId = newId,
            ),
        )
        io.out("imported ${r.projectName} (${r.projectId}): ${r.files} files -> ${r.projectPath.path}")
        io.out("  schema v${r.schemaFrom} -> v${r.schemaTo}  git: ${r.gitHead ?: "none"}  signer: ${r.signerFingerprint ?: "unsigned"}")
        r.replacedPath?.let { io.out("  previous directory kept at ${it.path}") }
        r.warnings.forEach { io.err("  note: $it") }
        return 0
    }

    private const val VAULT_PREFIX = "vault:"

    private fun readVaultKey(name: String, keys: SigningKeyStore?): KeyPair {
        val text = (keys ?: throw PortabilityException("export.bad_key", "no secrets vault is available"))
            .load(name) ?: throw PortabilityException("export.bad_key", "no signing key named '$name' in the vault")
        return parseKey(text.lines().filter { it.isNotBlank() }, "vault key '$name'")
    }

    private fun doKeygen(a: List<String>, io: CliIo, cwd: File, keys: SigningKeyStore?): Int {
        if (a.firstOrNull() == "--vault") {
            val name = a.getOrNull(1) ?: throw UsageError("keygen --vault needs a name")
            val store = keys ?: throw PortabilityException("keygen.no_vault", "no secrets vault is available")
            val pubFile = a.getOrNull(2)?.let { resolve(cwd, it) }
            if (pubFile != null && pubFile.exists()) throw PortabilityException("keygen.exists", "${pubFile.path} already exists")
            val kp = ArchiveCrypto.generateSigningKeyPair()
            try {
                store.save(name, ArchiveCrypto.encodePrivate(kp.private) + "\n" + ArchiveCrypto.encodePublic(kp.public) + "\n")
            } catch (e: IllegalStateException) {
                throw PortabilityException("keygen.exists", e.message ?: "a signing key named '$name' already exists")
            }
            pubFile?.writeText(ArchiveCrypto.encodePublic(kp.public) + "\n")
            io.out("stored signing key '$name' in the secrets vault; use --sign $VAULT_PREFIX$name")
            io.out("fingerprint: ${ArchiveCrypto.fingerprint(kp.public)}")
            return 0
        }
        val prefix = resolve(cwd, a.singleOrNull() ?: throw UsageError("keygen needs a file prefix"))
        val priv = File(prefix.path + ".key"); val pub = File(prefix.path + ".pub")
        if (priv.exists() || pub.exists()) throw PortabilityException("keygen.exists", "${priv.path} or ${pub.path} already exists")
        val kp = ArchiveCrypto.generateSigningKeyPair()
        priv.writeText(ArchiveCrypto.encodePrivate(kp.private) + "\n" + ArchiveCrypto.encodePublic(kp.public) + "\n")
        priv.setReadable(false, false); priv.setReadable(true, true); priv.setWritable(false, false); priv.setWritable(true, true)
        pub.writeText(ArchiveCrypto.encodePublic(kp.public) + "\n")
        io.out("wrote ${priv.path} (secret) and ${pub.path}")
        io.out("fingerprint: ${ArchiveCrypto.fingerprint(kp.public)}")
        return 0
    }

    private fun readKey(file: File): KeyPair {
        val lines = file.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }
            ?: throw PortabilityException("export.bad_key", "${file.path} not found")
        return parseKey(lines, file.path)
    }

    private fun parseKey(lines: List<String>, what: String): KeyPair {
        if (lines.size < 2) throw PortabilityException("export.bad_key", "$what is not a key made by 'keygen'")
        return try {
            KeyPair(ArchiveCrypto.decodePublic(lines[1]), ArchiveCrypto.decodePrivate(lines[0]))
        } catch (e: Exception) {
            throw PortabilityException("export.bad_key", "$what is not a readable key")
        }
    }

    private fun usage(io: CliIo): Int {
        io.err("usage: export <projectDir> [--output FILE] [--encrypt] [--sign KEYFILE] [--overwrite] [--compress 0-9]")
        io.err("       import <archive> [--into DIR] [--rename|--replace] [--name NAME] [--require-signature] [--trust FINGERPRINT]")
        io.err("       keygen <prefix> | keygen --vault NAME [pubfile]      (sign with --sign vault:NAME)")
        return 2
    }
}
