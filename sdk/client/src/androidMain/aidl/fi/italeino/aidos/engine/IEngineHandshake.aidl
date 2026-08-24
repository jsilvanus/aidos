// IEngineHandshake.aidl
package fi.italeino.aidos.engine;

/**
 * Client-side copy of Aidos Engine's handshake interface (RFC-0103).
 *
 * This must match engine/androidapp/src/androidMain/aidl/fi/italeino/aidos/engine/IEngineHandshake.aidl
 * exactly: engine/ and sdk/ are separate Gradle projects with no shared module, and AIDL clients
 * bind by fully-qualified interface name, not by sharing generated code. See that file for the
 * full method contract (Bundle keys, status values).
 */
interface IEngineHandshake {
    Bundle performHandshake() = 1;
}
