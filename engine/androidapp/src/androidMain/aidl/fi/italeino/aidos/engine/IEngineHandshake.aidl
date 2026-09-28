// IEngineHandshake.aidl
package fi.italeino.aidos.engine;

/**
 * AIDL interface for Aidos Engine handshake (RFC-0103).
 *
 * Clients call performHandshake() to:
 * 1. Establish trust via signature-level permission (OS-enforced at permission check time)
 * 2. Receive ephemeral HTTP server port and bearer token
 * 3. Receive API version and capability information
 *
 * The handshake is gated by Android's signature-level permission protection.
 * Only apps signed with the same certificate as Aidos Engine can call this interface.
 *
 * All subsequent traffic is via plain HTTP to the returned port, using the bearer token
 * in the Authorization header.
 */
interface IEngineHandshake {
    /**
     * Perform handshake and return server details as a Bundle.
     *
     * A plain Bundle is used instead of a hand-written AIDL parcelable so that Engine, the
     * Aidos SDK, and every consuming app — each versioned and released independently per
     * RFC-0103's "Aidos SDK" section — stay wire-compatible without their Parcelable field
     * layouts having to match exactly. A reader on either side of a version skew just gets the
     * default for a key it doesn't recognize, instead of misreading the field after it.
     *
     * Bundle keys:
     *   - "status" (String): "APPROVED", "PENDING_APPROVAL", or "DENIED"
     *   - "port" (Int): ephemeral port the HTTP server is bound to (always 127.0.0.1);
     *     0 unless status is "APPROVED"
     *   - "token" (String): bearer token for HTTP Authorization header, valid for this session;
     *     empty unless status is "APPROVED"
     *   - "apiVersion" (Int): the API version (1 for MVP)
     *   - "capabilitiesJson" (String): JSON string listing available endpoints and models;
     *     "{}" unless status is "APPROVED"
     *   - "deepLinkPendingIntent" (PendingIntent, optional): present only when status is
     *     "PENDING_APPROVAL"; opens Engine's ConnectedAppsScreen
     *
     * Throws exception if the handshake cannot complete (e.g., Engine service not ready).
     */
    Bundle performHandshake() = 1;
}
