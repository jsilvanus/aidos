package dev.aidos.identity

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.DeviceId
import dev.aidos.kernel.IdGenerator
import dev.aidos.kernel.PlatformProfile
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Manages persistence for [DeviceIdentity] in `user.db` (RFC-0046, RFC-0054).
 *
 * Backed by the `device_identity` table (single-row table with `CHECK (id = 1)`).
 */
class DeviceIdentityStore(
    private val userDriver: SqlDriver,
    private val idGen: IdGenerator = UuidV7Generator(),
    private val nowIso: () -> String = { Clock.System.now().toString() },
) {

    /**
     * Retrieves the existing device identity from `user.db`, or generates and stores
     * a fresh [DeviceIdentity] on first start.
     */
    fun getOrRegister(
        platformProfile: PlatformProfile = PlatformProfile.DESKTOP,
        defaultDisplayName: String = "Desktop",
    ): DeviceIdentity {
        val existing = load()
        if (existing != null) {
            return existing
        }

        val generatedId = idGen.next()
        val now = nowIso()

        userDriver.execute(
            identifier = null,
            sql = "INSERT INTO device_identity (id, device_id, display_name, platform_profile, created_at) " +
                "VALUES (1, ?, ?, ?, ?)",
            parameters = 4,
        ) {
            bindString(0, generatedId)
            bindString(1, defaultDisplayName)
            bindString(2, platformProfile.name)
            bindString(3, now)
        }

        return DeviceIdentity(
            deviceId = DeviceId(generatedId),
            displayName = defaultDisplayName,
            platformProfile = platformProfile,
            createdAt = Instant.parse(now),
        )
    }

    /**
     * Reads the current [DeviceIdentity] row, or null if the table has not yet been initialized.
     */
    fun load(): DeviceIdentity? {
        return userDriver.executeQuery(
            identifier = null,
            sql = "SELECT device_id, display_name, platform_profile, created_at FROM device_identity WHERE id = 1",
            mapper = { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) {
                        DeviceIdentity(
                            deviceId = DeviceId(cursor.getString(0)!!),
                            displayName = cursor.getString(1)!!,
                            platformProfile = PlatformProfile.valueOf(cursor.getString(2)!!),
                            createdAt = Instant.parse(cursor.getString(3)!!),
                        )
                    } else null
                )
            },
            parameters = 0,
        ).value
    }

    /**
     * Updates the user-editable [DeviceIdentity.displayName].
     *
     * Returns true if a row was updated.
     */
    fun updateDisplayName(displayName: String): Boolean {
        val affected = userDriver.execute(
            identifier = null,
            sql = "UPDATE device_identity SET display_name = ? WHERE id = 1",
            parameters = 1,
        ) {
            bindString(0, displayName)
        }.value
        return affected > 0
    }
}
