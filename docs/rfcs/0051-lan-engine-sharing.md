# RFC-0051: LAN Engine Sharing and External Device Pairing

**Status:** Proposed  
**Authors:** Aidos contributors  
**Date:** 2026-08-18

## Summary

Aidos Engine currently exposes its HTTP inference API on loopback only. This RFC proposes an explicit **local-network sharing** mode that allows other devices on the same LAN/Wi-Fi network to use inference provided by the Android phone.

The feature is opt-in and authorized per external device. The pairing UX deliberately supports two connection modes:

1. **Code pairing** — a short-lived, TOTP-style numeric bootstrap code intended for SDK/client implementations.
2. **Manual pairing** — human-readable connection details that can be copied, entered manually, or optionally transmitted as a QR code.

The QR code is therefore a **transport format, not the pairing protocol**. A dedicated Aidos application or SDK is useful for automated pairing, but is not required for basic LAN access.

The intended user experience is:

> **Add external device → label → expiration → choose pairing method**

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
4. Provide simple pairing for both SDK-aware and generic clients.
5. Give each external device its own credential/grant and expiration time.
6. Allow individual external-device access to be revoked without invalidating other devices.
7. Make QR useful without making a dedicated Aidos client a protocol requirement.
8. Keep the inference/runtime layer independent of the LAN transport and pairing mechanism.
9. Leave room for service discovery and stronger transport security later.

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

## Pairing model

Pairing has two distinct modes.

### Mode A — Code pairing

This is intended for clients that implement the Aidos pairing protocol, typically through the SDK or a dedicated Aidos client.

The Engine UI creates a short-lived numeric bootstrap code, for example:

```text
PAIRING CODE

847 291

Expires in 60 seconds
```

The code is **not** the long-term bearer credential. It is a one-time/short-lived bootstrap secret used to establish trust between the Engine and the external client.

Conceptually:

```text
External client                 Aidos Engine
      │                               │
      │  pairing code                 │
      ├──────────────────────────────►│
      │                               │
      │       device credential       │
      │◄──────────────────────────────┤
      │                               │
      │──── authenticated HTTP ──────►│
```

The exact cryptographic construction may use TOTP-style rotating codes or an equivalent short-lived challenge. The important property is that the displayed code is temporary and is not itself the persistent bearer credential.

The SDK should encapsulate this protocol so application developers do not need to implement pairing cryptography themselves.

### Mode B — Manual pairing

This is intended for generic HTTP clients, CLI tools, development, or users who do not have an Aidos SDK/client available.

The Engine displays a human-readable connection descriptor such as:

```text
Aidos Engine

Address:   192.168.1.42
Port:      12345
Protocol:  Aidos Engine HTTP v1
Credential: eyJ...
Expires:   25 Aug 2026 12:00
```

The UI provides:

```text
[ Copy connection details ]
[ Show QR ]
```

The QR code contains the same connection descriptor in a machine-readable form. It is merely a convenient way to transmit the information.

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

The exact wire format is intentionally left open for implementation. It should be versioned and designed so future versions can add TLS/public-key information without breaking existing clients.

## Dedicated client / SDK requirement

A dedicated Aidos application is **not required** for LAN inference.

The distinction is:

```text
Pairing protocol
       │
       ├── SDK / Aidos client
       │      └── automated code pairing
       │
       └── Generic client
              └── manual connection descriptor
```

Possible clients include:

1. **Aidos client app** — preferred UX; can perform code pairing automatically.
2. **Aidos SDK client** — application developers can use the pairing protocol without implementing it themselves.
3. **Developer tooling / CLI** — can implement code pairing or accept manual connection details.
4. **Generic HTTP/OpenAI client** — can use the manual endpoint and bearer credential directly.

The Aidos SDK is therefore an **implementation convenience**, not a protocol dependency.

A future OS-level deep link may improve the experience, but it must remain optional.

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

Discovery is a convenience for finding an Engine; it is not authorization.

## Authentication and authorization

The existing bearer-authenticated HTTP endpoints remain the inference API surface.

LAN requests must authenticate with a valid external-device credential. The Engine maps the credential to its external-device grant and checks:

1. credential validity;
2. expiration;
3. revocation;
4. optionally, future per-device permissions.

The existing single-current-token model should therefore be extended rather than reused unchanged for LAN sharing. Local Binder-issued tokens and external-device credentials should be separate credential classes/lifecycles.

The short-lived code used by code pairing must not be accepted as a normal inference bearer credential.

## Transport security

The initial implementation may support HTTP on a trusted private LAN, but the architecture should reserve a path for authenticated TLS.

The preferred long-term model is:

```text
LAN
 ↓
mDNS discovery (optional)
 ↓
code pairing OR manual descriptor
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
- pairing method selection;
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

A separate component should own external-device grants and pairing, for example:

```text
ExternalDeviceManager
    createGrant(label, expiresAt)
    createPairingCode(grantId)
    completePairing(code, clientInfo)
    createManualDescriptor(grantId)
    validateCredential(credential)
    revoke(id)
    list()
```

The QR generator should consume a connection descriptor rather than directly exposing internal credential structures.

## Backwards compatibility

- Existing localhost HTTP clients continue to work unchanged.
- Existing Binder consumers continue to use the Binder handshake.
- LAN sharing is disabled unless explicitly enabled.
- Existing local bearer-token semantics need not change for the localhost path.
- Clients that do not implement Aidos pairing can still use manual connection details.

## Security considerations

LAN does not equal trusted. The Engine must not treat an IP address, subnet, or mDNS discovery result as authorization.

Important safeguards:

- no LAN listener by default;
- explicit user action to enable sharing;
- unique credential per external device;
- mandatory expiration;
- manual revocation;
- short-lived pairing codes;
- pairing codes never usable as inference bearer tokens;
- no credentials in discovery advertisements;
- cryptographically secure credential generation;
- clear UI indication while LAN sharing is active;
- future TLS support;
- rate limiting and request-size limits should apply to LAN clients as appropriate.

Manual QR codes containing bearer credentials must be treated as sensitive. The UI should make this clear and should not persist or log the descriptor unnecessarily.

## Open questions

1. Should LAN sharing use HTTP initially or require TLS from the first implementation?
2. Should code pairing use TOTP specifically, or a purpose-built short-lived challenge/response protocol?
3. Should the Engine automatically stop LAN sharing when the last external grant expires?
4. Should external devices have permissions/capabilities beyond simple inference access?
5. Should a desktop Aidos client be the first official implementation of code pairing?
6. Should manual QR descriptors eventually use a signed descriptor or public-key-based transport bootstrap?

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

### Phase 3 — Manual pairing

- Define versioned connection descriptor.
- Add copyable human-readable connection details.
- Add optional QR generation containing the same descriptor.

### Phase 4 — SDK code pairing

- Define the short-lived pairing-code protocol.
- Implement it in the SDK.
- Add Android UI for displaying the pairing code.
- Exchange the bootstrap code for a per-device credential.

### Phase 5 — Discovery and hardened transport

- Add mDNS/DNS-SD.
- Add TLS/public-key pinning or an equivalent authenticated transport design.
- Add richer device management and permissions.

## Decision

The proposed design is to treat LAN inference as an **explicitly enabled, per-device paired capability**, not simply as an HTTP server bound to `0.0.0.0`.

The preferred UX is:

> **Add external device → label → expiration → pairing method**

with two pairing methods:

- **Code pairing:** short-lived numeric bootstrap code for SDK-aware clients.
- **Manual pairing:** readable connection details with optional QR transmission for generic clients.

The QR code is deliberately **not** the pairing protocol. It is a convenient transport representation of the manual connection descriptor.

The Aidos SDK is recommended for the automated pairing experience, but **the LAN protocol must not require the SDK or a dedicated Aidos application**.
