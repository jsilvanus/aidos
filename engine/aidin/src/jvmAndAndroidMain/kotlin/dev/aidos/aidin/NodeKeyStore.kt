package dev.aidos.aidin

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Where an Engine keeps its node key. The key is created once per install and reused, so the
 * node id stays stable (RFC-0105 "Node identity").
 */
interface NodeKeyStore {
    fun loadOrCreate(): SigningKey
}

class InMemoryNodeKeyStore(private var key: SigningKey? = null) : NodeKeyStore {
    override fun loadOrCreate(): SigningKey = key ?: SigningKey.generate().also { key = it }
}

/**
 * Desktop store: two files in the Engine's data directory, the private key readable by the owner
 * only where the file system supports POSIX permissions. Android uses Android Keystore instead
 * (not part of this module yet).
 */
class FileNodeKeyStore(private val directory: File) : NodeKeyStore {
    private val privateFile get() = File(directory, "aidin-node.key")
    private val publicFile get() = File(directory, "aidin-node.pub")

    override fun loadOrCreate(): SigningKey {
        if (privateFile.isFile && publicFile.isFile) {
            return SigningKey.fromEncoded(privateFile.readBytes(), publicFile.readBytes())
        }
        directory.mkdirs()
        // Half a pair (an interrupted first start) is useless; start over rather than fail forever.
        privateFile.delete()
        publicFile.delete()
        val key = SigningKey.generate()
        writeOwnerOnly(privateFile, key.privateKeyEncoded())
        publicFile.writeBytes(key.publicKey)
        return key
    }

    private fun writeOwnerOnly(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(byteArrayOf())
        try {
            Files.setPosixFilePermissions(
                tmp.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (e: UnsupportedOperationException) {
            // Windows: no POSIX permissions; the file inherits the user profile's ACL.
        }
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("could not store node key in $directory")
        }
    }
}
