package dev.aidos.identity

import dev.aidos.kernel.DeviceId
import dev.aidos.kernel.PlatformProfile
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * One installation on one device has one identity, generated at first start (RFC-0046).
 *
 * Stored as a single row in `device_identity` in `user.db` (user scope, RFC-0054),
 * keyed `CHECK (id = 1)`.
 *
 * Local, self-assigned, never registered anywhere. It exists because a project directory can be
 * copied between devices (RFC-0041) and "which machine did this Run happen on?" is a question the
 * audit trail answers.
 *
 * [deviceId] is not a stable user identifier and is not transmitted anywhere. It appears in
 * local audit records and in exports the user chooses to make.
 */
@Serializable
data class DeviceIdentity(
    val deviceId: DeviceId,
    val displayName: String,
    val platformProfile: PlatformProfile,
    val createdAt: Instant,
) {
    val deviceIdString: String get() = deviceId.value
}
