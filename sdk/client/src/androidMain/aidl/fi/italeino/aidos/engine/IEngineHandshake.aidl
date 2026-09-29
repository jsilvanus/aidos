// IEngineHandshake.aidl
package fi.italeino.aidos.engine;

/**
 * AIDL interface for the Aidos Engine handshake (RFC-0103). Frozen for API v1 — see
 * sdk/CONTRACT.md.
 *
 * This is the single copy of the interface: Engine (engine/androidapp) implements the generated
 * Stub from the SDK client library rather than keeping its own AIDL, so client and host cannot
 * drift. AIDL clients bind by fully-qualified interface name, which is why it lives under
 * `fi.italeino.aidos.engine` rather than the SDK's own package.
 *
 * Clients call performHandshake() to:
 * 1. Be identified by Binder's verified caller UID and checked against the user's approval
 *    (first contact records the app as pending and notifies the user)
 * 2. Receive ephemeral HTTP server port and bearer token
 * 3. Receive API version and capability information
 *
 * The handshake permission is `normal`-level and carries no trust (RFC-0103, "Trust model"):
 * any app may call, and the user's per-app approval is the gate.
 *
 * All subsequent traffic is via plain HTTP to 127.0.0.1:<port>, using the bearer token in the
 * Authorization header.
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
