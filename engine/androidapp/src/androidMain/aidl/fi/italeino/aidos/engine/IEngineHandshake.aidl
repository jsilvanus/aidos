// IEngineHandshake.aidl
package fi.italeino.aidos.engine;

// Parcelable types used in the handshake
parcelable HandshakeResult;

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
     * Perform handshake and return server details.
     *
     * @return HandshakeResult with:
     *   - port: ephemeral port the HTTP server is bound to (always 127.0.0.1)
     *   - token: bearer token for HTTP Authorization header (valid for this session)
     *   - apiVersion: the API version (1 for MVP)
     *   - capabilities: JSON string listing available endpoints and models
     *
     * Throws exception if the handshake cannot complete (e.g., Engine service not ready).
     */
    HandshakeResult performHandshake() = 1;
}
