# RFC-0051: LAN Engine Sharing and External Device Pairing

**Status:** Proposed  
**Authors:** Aidos contributors  
**Date:** 2026-08-18

## Summary

Aidos Engine currently exposes its HTTP inference API on loopback only. This RFC proposes an explicit **local-network sharing** mode that allows other devices on the same trusted LAN/Wi-Fi network to use inference provided by the Android phone.

The feature is opt-in and paired per external device. A user creates an external-device grant by giving it a human-readable label and an expiration time; the Engine then displays a QR code containing the information needed by the connecting client to establish access.

The goal is to make the experience resemble:

> **Add external device → label + expiration → show QR code**

while keeping ordinary local Android consumers on the existing Binder/handshake path.

## Motivation

A phone may have useful local inference capacity that other devices on the same LAN can consume. Examples include:

- a laptop using the phone's larger/available model;
- a desktop using the phone for occasional inference;
- another tablet or device using the phone for STT or embeddings;
- development and testing against a physically separate client.

The HTTP API already provides an OpenAI-compatible inference surface and bearer authentication. The current HTTP server, however, binds explicitly to `127.0.0.1`, making it unreachable from other LAN devices.

## Goals

1. Allow an Engine instance to serve inference to explicitly authorized devices on the local network.
2. Keep LAN sharing disabled by default.
3. Preserve the existing local Binder/handshake mechanism for Android app consumers.
4. Provide a simple, human-controlled pairing UX.
5. Give each external device its own credential/grant and expiration time.
6. Allow individual external-device access to be revoked without invalidating other devices.
7. Support service discovery without requiring users to manually type an IP address where practical.
8. Keep the inference/runtime layer independent of the LAN transport mechanism.

## Non-goals

- Internet-facing Engine hosting.
- Automatic exposure through NAT/UPnP.
- Public discovery of Aidos Engines on the Internet.
- Replacing Android Binder IPC.
- Making the LAN API unauthenticated merely because it is on a private network.

## Proposed architecture

```text
                         Android Binder
              ┌───────────────────────────────┐
              │                               │
 Local apps ──┤                               ▼
              │                         Aidos Engine
              │                               │
              │                    ┌──────────┴──────────┐
              │                    │                     │
              │              Local HTTP            LAN HTTP
              │                    │                     │
              │              127.0.0.1              Wi-Fi/LAN
              │                                          │
              │                                          ▼
              │                                  External device
              │                                  (laptop/tablet/etc.)
              │
              └───────────────────────────────────────────
```

The Engine should support two HTTP bind modes:

- `LOCALHOST` — bind only to loopback; current/default behaviour.
- `LAN` — bind to an address/interface reachable on the local network.

The implementation should avoid exposing the Engine on arbitrary interfaces when LAN sharing is disabled.

## External-device grants

An external-device grant is an Engine-owned authorization record containing at minimum:

```text
id
label
credential
createdAt
expiresAt
revoked
```

The credential must be generated with cryptographically secure randomness. Credentials are independent per external device.

The Engine should not use one global LAN token for all clients. This permits revocation and auditing at device level.

Expiration is mandatory. The UI may offer convenient presets such as:

- 1 hour
- 1 day
- 7 days
- 30 days
- custom

A grant may also be revoked manually before expiration.

## Pairing UX

The intended primary flow is:

1. User opens **Engine → External devices**.
2. User selects **Add external device**.
3. User enters a label, e.g. `My laptop`.
4. User chooses an expiration time.
5. Engine creates a new external-device grant.
6. Engine displays a QR code.
7. The external device scans the QR code.
8. The external client imports the Engine endpoint and credential and connects.
9. The Engine shows the paired device in its external-device list.

The QR code should contain a versioned Aidos connection descriptor rather than an opaque token alone.

Conceptually:

```json
{
  "v": 1,
  "scheme": "http",
  "host": "192.168.1.42",
  "port": 12345,
  "service": "aidos-engine",
  "credential": "...",
  "expiresAt": "2026-08-25T12:00:00Z"
}
```

The exact wire format is intentionally left open for implementation. The descriptor should be designed so that future versions can add TLS/public-key information without breaking existing clients.

## Does the connecting device need a dedicated Aidos app?

**Not necessarily.** The pairing mechanism should distinguish between the **connection protocol** and the **client UX**.

The QR code can encode a standard Aidos connection descriptor. A dedicated Aidos client application is the best UX because it can scan the QR code and store/manage the connection, but it is not inherently required by the Engine.

Possible clients include:

1. **Aidos client app** — preferred UX; scans QR and imports the connection automatically.
2. **Aidos SDK client** — an application can receive/import the descriptor and construct an `AidosEngineClient` connection.
3. **Developer tooling / CLI** — can accept the descriptor or its fields manually.
4. **Generic HTTP/OpenAI client** — can connect if the user manually supplies the LAN endpoint and bearer credential.

Therefore the QR code should not encode an instruction such as “open this in the Aidos app”. It should encode portable connection information. A dedicated client can recognize the descriptor and provide a seamless experience, while other clients remain possible.

A future OS-level deep link may improve the experience:

```text
aidos://engine/connect?... 
```

but this should be optional rather than a protocol requirement.

## Service discovery

LAN sharing should support mDNS/DNS-SD discovery where available.

Proposed service type:

```text
_aidos-engine._tcp
```

Discovery metadata may expose non-secret information such as:

- Engine name/label;
- Engine protocol version;
- HTTP port;
- API capabilities;
- whether pairing is required.

**Credentials must never be advertised through mDNS.**

The QR code remains the authorization mechanism.

Discovery is useful even without a dedicated client: a client can discover candidate Engines and then use a separately obtained grant.

## Authentication and authorization

The existing bearer-authenticated HTTP endpoints remain the inference API surface.

LAN requests must authenticate with a valid external-device credential. The Engine maps the credential to its external-device grant and checks:

1. credential validity;
2. expiration;
3. revocation;
4. optionally, future per-device permissions.

The existing single-current-token model should therefore be extended rather than reused unchanged for LAN sharing. Local Binder-issued tokens and external-device credentials should be separate credential classes/lifecycles.

## Transport security

The initial implementation may support HTTP on a trusted private LAN, but the architecture should reserve a path for authenticated TLS.

The preferred long-term model is:

```text
LAN
 ↓
mDNS discovery
 ↓
QR pairing
 ↓
authenticated TLS
 ↓
per-device authorization
```

A LAN credential is a bearer secret and must be treated as sensitive. The UI should warn users that sharing grants access to the Engine until expiration or revocation.

## Android considerations

LAN sharing requires the Android application to be allowed to communicate with the local network and to keep the Engine HTTP service alive while sharing is enabled.

The Engine should expose LAN sharing state to the Android UI so the user can see clearly when the phone is serving other devices.

The UI should provide:

- sharing enabled/disabled state;
- local network address/port;
- external-device grants;
- expiration times;
- revoke action;
- active/inactive pairing state.

## API / implementation direction

`EngineHttpServer` should gain an explicit bind configuration rather than hard-coding `127.0.0.1`.

Conceptually:

```kotlin
enum class BindMode {
    LOCALHOST,
    LAN
}
```

The server should derive the appropriate bind address from this configuration.

The existing HTTP authentication layer should be generalized from a single current token to a credential store capable of validating both local and external credentials.

A separate component should own external-device grants, for example:

```text
ExternalDeviceManager
    createGrant(label, expiresAt)
    validateCredential(credential)
    revoke(id)
    list()
```

QR generation should consume a connection descriptor produced by this manager rather than directly exposing internal credential structures.

## Backwards compatibility

- Existing localhost HTTP clients continue to work unchanged.
- Existing Binder consumers continue to use the Binder handshake.
- LAN sharing is disabled unless explicitly enabled.
- Existing local bearer-token semantics need not change for the localhost path.

## Security considerations

LAN does not equal trusted. The Engine must not treat an IP address, subnet, or mDNS discovery result as authorization.

Important safeguards:

- no LAN listener by default;
- explicit user action to enable sharing;
- unique credential per external device;
- mandatory expiration;
- manual revocation;
- no credentials in discovery advertisements;
- cryptographically random credentials;
- clear UI indication while LAN sharing is active;
- future TLS support;
- rate limiting and request-size limits should apply to LAN clients as appropriate.

## Open questions

1. Should LAN sharing use HTTP initially or require TLS from the first implementation?
2. Should QR descriptors contain the bearer credential directly, or should pairing use a short-lived one-time bootstrap secret that is exchanged for a persistent per-device credential?
3. Should the Engine automatically stop LAN sharing when the last external grant expires?
4. Should external devices have permissions/capabilities beyond simple inference access?
5. Should the desktop Aidos client be the first official QR-scanning client?
6. Should a generic web page or PWA be provided as a lightweight pairing client?

## Recommended implementation order

### Phase 1 — LAN transport

- Add explicit `LOCALHOST` / `LAN` bind mode.
- Bind LAN HTTP server only when sharing is enabled.
- Preserve bearer authentication.
- Add integration tests from a non-loopback client.

### Phase 2 — External-device grants

- Implement per-device credentials.
- Add labels, expiration, and revocation.
- Replace the single-token assumption for LAN credentials.

### Phase 3 — QR pairing

- Define versioned connection descriptor.
- Generate QR code in Android UI.
- Implement import in the Aidos client/SDK.

### Phase 4 — Discovery and hardened transport

- Add mDNS/DNS-SD.
- Add TLS/public-key pinning or an equivalent authenticated transport design.
- Add richer device management and permissions.

## Decision

The proposed design is to treat LAN inference as an **explicitly enabled, per-device paired capability**, not simply as an HTTP server bound to `0.0.0.0`.

The preferred UX is:

> **Add external device → label → expiration → QR code**

A dedicated Aidos client is recommended for the best experience, but **the protocol must not require one**. The QR code should carry a portable, versioned connection descriptor so that Aidos SDKs, CLIs, and other compatible clients can connect as well.
