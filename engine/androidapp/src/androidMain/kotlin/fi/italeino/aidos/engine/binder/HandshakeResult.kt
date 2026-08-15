package fi.italeino.aidos.engine.binder

import android.os.Parcel
import android.os.Parcelable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import fi.italeino.aidos.engine.http.Capabilities

/**
 * Parcelable result of the Aidos Engine handshake Binder call (RFC-0103).
 *
 * Returned by [IEngineHandshake.performHandshake] to clients, containing:
 * - port: ephemeral HTTP server port (loopback only, 127.0.0.1)
 * - token: bearer token for HTTP Authorization header
 * - apiVersion: handshake API version (1 for MVP)
 * - capabilitiesJson: JSON-serialized [Capabilities]
 */
data class HandshakeResult(
    val port: Int,
    val token: String,
    val apiVersion: Int = 1,
    val capabilitiesJson: String  // JSON-serialized Capabilities
) : Parcelable {

    constructor(parcel: Parcel) : this(
        port = parcel.readInt(),
        token = parcel.readString() ?: "",
        apiVersion = parcel.readInt(),
        capabilitiesJson = parcel.readString() ?: "{}"
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(port)
        parcel.writeString(token)
        parcel.writeInt(apiVersion)
        parcel.writeString(capabilitiesJson)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<HandshakeResult> {
        override fun createFromParcel(parcel: Parcel): HandshakeResult = HandshakeResult(parcel)
        override fun newArray(size: Int): Array<HandshakeResult?> = arrayOfNulls(size)
    }
}
