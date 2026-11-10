package fi.italeino.sshtunnel

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64

/** A freshly generated key: the private half is returned once and never stored by this library. */
class GeneratedKey(
    /** OpenSSH-format private key, ready for a [KeyProvider]. Caller owns and should wipe it. */
    val privateKey: ByteArray,
    /** `ssh-ed25519 AAAA… comment`, the part safe to show and paste into `authorized_keys`. */
    val publicKey: String,
)

/**
 * Ed25519 key generation with a hand-written OpenSSH `openssh-key-v1` encoder, so it needs only
 * Bouncy Castle's lightweight API: no MINA and no JCA provider registration, which keeps it usable
 * next to sshj on Android.
 */
object KeyGen {
    fun ed25519(comment: String): GeneratedKey {
        require(comment.isNotBlank() && comment.none { it == '\n' || it == '\r' }) { "invalid comment" }
        val priv = Ed25519PrivateKeyParameters(SecureRandom())
        val seed = priv.encoded
        val pub = priv.generatePublicKey().encoded

        val pubBlob = sshString("ssh-ed25519".toByteArray()) + sshString(pub)

        val check = SecureRandom().nextInt()
        val section = ByteArrayOutputStream()
        DataOutputStream(section).apply {
            writeInt(check); writeInt(check)
            write(sshString("ssh-ed25519".toByteArray()))
            write(sshString(pub))
            write(sshString(seed + pub)) // OpenSSH stores seed||public as the 64-byte secret
            write(sshString(comment.toByteArray()))
        }
        var pad = 1
        while (section.size() % 8 != 0) section.write(pad++) // deterministic 1,2,3… padding per spec

        val body = ByteArrayOutputStream()
        body.write("openssh-key-v1".toByteArray()); body.write(0)
        body.write(sshString("none".toByteArray()))  // cipher
        body.write(sshString("none".toByteArray()))  // kdf
        body.write(sshString(ByteArray(0)))          // kdf options
        DataOutputStream(body).writeInt(1)           // number of keys
        body.write(sshString(pubBlob))
        body.write(sshString(section.toByteArray()))

        val b64 = Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(body.toByteArray())
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
        seed.fill(0)
        val publicLine = "ssh-ed25519 " + Base64.getEncoder().encodeToString(pubBlob) + " " + comment
        return GeneratedKey(pem.toByteArray(), publicLine)
    }

    private fun sshString(b: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        DataOutputStream(o).apply { writeInt(b.size); write(b) }
        return o.toByteArray()
    }
}
