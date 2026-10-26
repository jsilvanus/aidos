package dev.aidos.aidin

import java.util.Base64

private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
private fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

fun MembershipCertificate.toWire() = WireCertificate(
    groupKey = b64(groupPublicKey),
    nodeKey = b64(nodePublicKey),
    roles = roles.map { it.wire }.sorted(),
    notBefore = notBeforeMillis,
    notAfter = notAfterMillis,
    signature = b64(signature),
)

/**
 * Null when malformed. An unknown role makes the certificate unusable rather than silently
 * narrower: its signature covers the role list, so dropping one would not verify anyway.
 */
fun WireCertificate.toCertificate(): MembershipCertificate? = try {
    val parsedRoles = roles.map { Scope.fromWire(it) ?: return null }.toSet()
    MembershipCertificate(unb64(groupKey), unb64(nodeKey), parsedRoles, notBefore, notAfter, unb64(signature))
} catch (e: IllegalArgumentException) {
    null
}

fun RevocationList.toWire() = WireRevocationList(
    groupKey = b64(groupPublicKey),
    serial = serial,
    revoked = revoked.map { it.value }.sorted(),
    issuedAt = issuedAtMillis,
    signature = b64(signature),
)

fun WireRevocationList.toRevocationList(): RevocationList? = try {
    RevocationList(unb64(groupKey), serial, revoked.map { NodeId(it) }.toSet(), issuedAt, unb64(signature))
} catch (e: IllegalArgumentException) {
    null
}
