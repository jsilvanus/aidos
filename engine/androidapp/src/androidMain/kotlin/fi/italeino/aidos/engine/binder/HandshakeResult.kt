package fi.italeino.aidos.engine

import android.app.PendingIntent
import android.os.Parcel
import android.os.Parcelable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import fi.italeino.aidos.engine.http.Capabilities

/**
 * Parcelable result of the Aidos Engine handshake Binder call (RFC-0103).
 *
 * Handles both approval scenarios:
 *
 * 1. APPROVED (app is pre-approved or was just approved by user):
 *    - status = "APPROVED"
 *    - port: ephemeral HTTP server port (loopback only, 127.0.0.1)
 *    - token: bearer token for HTTP Authorization header
 *    - apiVersion: handshake API version (1 for MVP)
 *    - capabilitiesJson: JSON-serialized [Capabilities]
 *    - deepLinkPendingIntent: null
 *
 * 2. PENDING_APPROVAL (first handshake, needs user approval):
 *    - status = "PENDING_APPROVAL"
 *    - port: 0
 *    - token: empty string
 *    - apiVersion: 1
 *    - capabilitiesJson: "{}"
 *    - deepLinkPendingIntent: PendingIntent that opens ConnectedAppsScreen
 *
 * 3. DENIED (user explicitly denied this app):
 *    - status = "DENIED"
 *    - All other fields zero/empty
 */
data class HandshakeResult(
    val status: String = "APPROVED",  // "APPROVED", "PENDING_APPROVAL", or "DENIED"
    val port: Int = 0,
    val token: String = "",
    val apiVersion: Int = 1,
    val capabilitiesJson: String = "{}",  // JSON-serialized Capabilities
    val deepLinkPendingIntent: PendingIntent? = null
) : Parcelable {

    constructor(parcel: Parcel) : this(
        status = parcel.readString() ?: "APPROVED",
        port = parcel.readInt(),
        token = parcel.readString() ?: "",
        apiVersion = parcel.readInt(),
        capabilitiesJson = parcel.readString() ?: "{}",
        deepLinkPendingIntent = parcel.readParcelable(PendingIntent::class.java.classLoader)
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(status)
        parcel.writeInt(port)
        parcel.writeString(token)
        parcel.writeInt(apiVersion)
        parcel.writeString(capabilitiesJson)
        parcel.writeParcelable(deepLinkPendingIntent, flags)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<HandshakeResult> {
        override fun createFromParcel(parcel: Parcel): HandshakeResult = HandshakeResult(parcel)
        override fun newArray(size: Int): Array<HandshakeResult?> = arrayOfNulls(size)
    }
}
