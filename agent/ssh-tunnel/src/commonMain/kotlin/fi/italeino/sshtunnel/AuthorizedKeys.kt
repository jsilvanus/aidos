package fi.italeino.sshtunnel

/**
 * Builds the server-side `authorized_keys` line that limits a key to exactly one tunnel
 * (RFC-0108): `restrict` drops pty, agent, X11 and rc files; `port-forwarding` re-enables only
 * forwarding; `permitlisten` / `permitopen` pin the one port; the forced command gives no shell.
 */
object AuthorizedKeys {
    /** Line for a [Forward.Reverse] tunnel: the key may only listen on `serverBindHost:serverPort`. */
    fun forReverse(publicKey: String, forward: Forward.Reverse, forcedCommand: String = "/bin/false"): String =
        line(publicKey, "permitlisten=\"${safe(forward.serverBindHost)}:${forward.serverPort}\"", forcedCommand)

    /** Line for a [Forward.Local] tunnel: the key may only open `remoteHost:remotePort`. */
    fun forLocal(publicKey: String, forward: Forward.Local, forcedCommand: String = "/bin/false"): String =
        line(publicKey, "permitopen=\"${safe(forward.remoteHost)}:${forward.remotePort}\"", forcedCommand)

    private fun line(publicKey: String, permit: String, forcedCommand: String): String {
        val pk = publicKey.trim()
        require(pk.startsWith("ssh-ed25519 ") && '\n' !in pk && '\r' !in pk) { "expected one ssh-ed25519 public key line" }
        return "restrict,port-forwarding,$permit,command=\"${safe(forcedCommand)}\" $pk"
    }

    // Option values sit inside double quotes in a line-oriented file: refuse anything that could
    // end the quote or the line instead of trying to escape it.
    private fun safe(v: String): String {
        require(v.isNotEmpty() && v.none { it == '"' || it == '\n' || it == '\r' || it == '\\' }) { "unsafe option value" }
        return v
    }
}
