package dev.aidos.api.socket

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet

/**
 * Makes a file readable and writable by its owner only (RFC-0115, D57).
 *
 * POSIX file systems get `rw-------`. Windows (NTFS) has no POSIX view, so the file's ACL is replaced
 * with a single entry that gives the owner full control and nobody else anything. A file system that
 * supports neither is an error, not a silent no-op: callers use this for tokens, and a token file that
 * could not be protected must not be left behind as if it were.
 */
object OwnerOnlyFiles {
    @Throws(IOException::class)
    fun restrict(path: Path) {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posix != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            return
        }
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            ?: throw IOException("cannot restrict $path to its owner: the file system supports neither POSIX permissions nor ACLs")
        val owner = Files.getOwner(path)
        val ownerOnly = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
            .build()
        acl.acl = listOf(ownerOnly)
        // Read back: an ACL that did not take is the failure this guards against.
        val applied = acl.acl
        if (applied.size != 1 || applied[0].principal() != owner) {
            throw IOException("could not restrict $path to its owner (ACL still has ${applied.size} entries)")
        }
    }

    /** True when only the owner can read [path]: used by tests and by startup self-checks. */
    fun isOwnerOnly(path: Path): Boolean {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posix != null) return Files.getPosixFilePermissions(path) == PosixFilePermissions.fromString("rw-------")
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return false
        val owner = Files.getOwner(path)
        return acl.acl.all { it.principal() == owner }
    }

    /**
     * Writes [text] to an owner-only file at [path], atomically: the secret goes to a temp file in the same directory
     * (restricted before any secret is written, so there is no readable window) and is then renamed over [path], so a
     * reader sees the old file or the new one, never a half-written one.
     */
    @Throws(IOException::class)
    fun writeSecret(path: Path, text: String) {
        val dir = path.toAbsolutePath().parent
        val tmp = Files.createTempFile(dir, "." + path.fileName.toString(), ".tmp")
        try {
            restrict(tmp)
            Files.writeString(tmp, text)
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }
}
