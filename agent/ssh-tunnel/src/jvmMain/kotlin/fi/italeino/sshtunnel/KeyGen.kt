package fi.italeino.sshtunnel

import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import java.io.ByteArrayOutputStream
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyPairGenerator

/** A freshly generated key: the private half is returned once and never stored by this library. */
class GeneratedKey(
    /** OpenSSH-format private key, ready for a [KeyProvider]. Caller owns and should wipe it. */
    val privateKey: ByteArray,
    /** `ssh-ed25519 AAAA… comment`, the part safe to show and paste into `authorized_keys`. */
    val publicKey: String,
)

object KeyGen {
    fun ed25519(comment: String): GeneratedKey {
        require(comment.isNotBlank() && comment.none { it == '\n' || it == '\r' }) { "invalid comment" }
        // BC provider so MINA's encoder gets the key classes it expects (JDK Ed25519 keys are not accepted).
        val kp = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider()).generateKeyPair()
        val out = ByteArrayOutputStream()
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(kp, comment, null, out)
        val pub = PublicKeyEntry.toString(kp.public) + " " + comment
        return GeneratedKey(out.toByteArray(), pub)
    }
}
