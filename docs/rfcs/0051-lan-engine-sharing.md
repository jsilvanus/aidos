# RFC-0051: LAN Engine Sharing and External Device Pairing

**Status:** Proposed  
**Authors:** Aidos contributors  
**Date:** 2026-08-18

## Summary

Aidos Engine currently exposes its HTTP inference API on loopback only. This RFC proposes an explicit **local-network sharing** mode that allows other devices on the same LAN/Wi-Fi network to use inference provided by the Android phone.

LAN sharing is opt-in, uses **HTTPS**, and is authorized per external device. Pairing supports two modes:

1. **Code pairing** — a short-lived numeric bootstrap code for SDK/client implementations.
2. **Manual pairing** — human-readable connection details that can be copied, entered manually, or optionally transmitted as a QR code.

The QR code is a **transport format, not the pairing protocol**. A dedicated Aidos application or SDK is useful for automated pairing, but is not required for basic LAN access.

The intended UX is:

> **Add external device → label → expiration → choose pairing method**

Ordinary local Android consumers continue to use the existing Binder/handshake path.

## Motivation

A phone may have useful local inference capacity that other devices on the same LAN can consume: laptops, desktops, tablets, development tools, or other devices using chat, STT, embeddings, or other Engine capabilities.

The HTTP API already provides an OpenAI-compatible inference surface and bearer authentication. The current HTTP server binds to `127.0.0.1`, making it unreachable from other LAN devices.

## Goals

1. Serve inference to explicitly authorized devices on the local network.
2. Keep LAN sharing disabled by default.
3. Preserve Binder/handshake for local Android consumers.
4. Provide pairing for both SDK-aware and generic clients.
5. Give each external device its own credential, label, and expiration.
6. Support individual revocation.
7. Make QR useful without requiring a dedicated Aidos client.
8. Keep inference/runtime independent of LAN transport and pairing.
9. Use encrypted transport for all LAN inference traffic.

## Non-goals

- Internet-facing Engine hosting.
- NAT/UPnP exposure.
- Public Internet discovery.
- Replacing Binder IPC.
- Treating LAN membership as authorization.

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
              │             Local HTTP(S)           LAN HTTPS
              │                    │                     │
              │              127.0.0.1              Wi-Fi/LAN
              │                                          │
              │                                          ▼
              │                                  External device
              └───────────────────────────────────────────
```

The Engine should support two bind modes:

- `LOCALHOST` — loopback only; current/default behaviour.
- `LAN` — local-network interface(s), **HTTPS only**.

LAN mode must not expose a cleartext HTTP listener. Ktor supports direct server-side SSL configuration with a keystore, including embedded servers, so TLS should be implemented using the platform/Ktor TLS stack rather than a custom TLS implementation. citeturn0search1

## External-device grants

An external-device grant contains at minimum:

```text
id
label
credential
createdAt
expiresAt
revoked
```

Credentials must be cryptographically random and independent per device. Expiration is mandatory. The UI may offer 1 hour, 1 day, 7 days, 30 days, and custom expiration.

The Engine must not use one global LAN token for all clients.

## Pairing model

### Mode A — Code pairing

For SDK-aware clients, the Engine displays a short-lived bootstrap code:

```text
PAIRING CODE

847 291

Expires in 60 seconds
```

The code is **not** an inference bearer credential. It is a temporary bootstrap secret used to establish a device grant.

```text
External client                 Aidos Engine
      │                               │
      │  pairing code                 │
      ├──────────────────────────────►│
      │                               │
      │       device credential       │
      │◄──────────────────────────────┤
      │                               │
      │──── HTTPS + bearer auth ─────►│
```

The exact cryptographic construction may use TOTP-style rotating codes or an equivalent short-lived challenge. The SDK should encapsulate the protocol.

### Mode B — Manual pairing

For generic HTTP clients, CLI tools, development, or users without an SDK, the Engine displays:

```text
Aidos Engine

Address:    192.168.1.42
Port:       12345
Protocol:   Aidos Engine HTTPS v1
Credential: eyJ...
Expires:    25 Aug 2026 12:00
Fingerprint: SHA-256: A4:72:91:...
```

The UI provides:

```text
[ Copy connection details ]
[ Show QR ]
```

The QR contains the same connection descriptor. It is merely a convenient transmission mechanism.

The descriptor should be versioned and contain the TLS identity information needed by the client to verify the Engine, for example:

```json
{
  "v": 1,
  "scheme": "https",
  "host": "192.168.1.42",
  "port": 12345,
  "service": "aidos-engine",
  "engineId": "7f3c...",
  "certificateFingerprint": "sha256:A4:72:91:...",
  "credential": "...",
  "expiresAt": "2026-08-25T12:00:00Z"
}
```

## TLS identity

The Engine should generate and persist a local server identity when LAN sharing is first enabled:

```text
Aidos Engine
    │
    ├── private key
    └── self-signed certificate
```

The implementation must use standard Android/JVM cryptographic and TLS primitives; **Aidos must not implement TLS itself**.

The certificate/public-key identity should be represented by a stable fingerprint or equivalent identifier. Pairing establishes trust in that Engine identity. Clients should not simply disable certificate verification because the certificate is self-signed.

The private key must remain local to the Engine and must not be included in pairing descriptors.

The identity should remain stable across normal Engine restarts. If the identity is intentionally regenerated, existing clients should treat it as a new Engine and require re-pairing or explicit re-trust.

## Dedicated client / SDK requirement

A dedicated Aidos application is **not required** for LAN inference.

```text
Pairing protocol
       │
       ├── SDK / Aidos client
       │      └── automated code pairing
       │
       └── Generic client
              └── manual HTTPS descriptor
```

Possible clients include an Aidos client, SDK-based applications, CLI tooling, or generic HTTP/OpenAI-compatible clients.

The SDK is an implementation convenience, not a protocol dependency.

## Service discovery

LAN sharing may support mDNS/DNS-SD:

```text
_aidos-engine._tcp
```

Discovery may expose non-secret metadata such as Engine name, protocol version, HTTPS port, capabilities, and pairing state.

**Credentials and private key material must never be advertised through mDNS.** Discovery is not authorization.

## Authentication and authorization

LAN inference uses HTTPS plus a per-device bearer credential.

The Engine validates:

1. credential validity;
2. expiration;
3. revocation;
4. optionally, future per-device permissions.

The short-lived pairing code must never be accepted as an inference bearer credential.

Local Binder-issued tokens and external-device credentials remain separate credential classes/lifecycles.

## Transport security

**LAN inference MUST use HTTPS. Cleartext HTTP is not supported in LAN mode.**

The target architecture is:

```text
LAN
 ↓
mDNS discovery (optional)
 ↓
code pairing OR manual descriptor
 ↓
TLS certificate/public-key verification
 ↓
per-device bearer authorization
 ↓
Inference API
```

TLS should use the Android/JVM/Ktor implementation rather than custom cryptography. Ktor documents server-side SSL connectors and keystore-based certificate configuration for embedded servers. citeturn0search1

Localhost may retain its existing HTTP behaviour for backwards compatibility.

## Android considerations

The Android UI should clearly expose when LAN sharing is active and provide:

- sharing enabled/disabled state;
- local HTTPS address/port;
- Engine certificate/public-key fingerprint;
- external-device grants;
- expiration times;
- revoke action;
- pairing method selection;
- active/inactive pairing state.

LAN sharing must remain an explicit user-controlled capability.

## API / implementation direction

`EngineHttpServer` should gain explicit bind and TLS configuration rather than hard-coding `127.0.0.1`.

Conceptually:

```kotlin
enum class BindMode {
    LOCALHOST,
    LAN
}
```

LAN configuration should include the TLS identity/keystore and HTTPS connector. Ktor's server configuration supports SSL connectors and keystore-backed certificates. citeturn0search1

A separate component should own grants and pairing:

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

A separate `EngineIdentity`/TLS component should own key generation, certificate persistence, fingerprint calculation, and TLS configuration.

The QR generator consumes a connection descriptor rather than directly exposing internal credential structures.

## Backwards compatibility

- Existing localhost HTTP clients continue to work unchanged.
- Existing Binder consumers continue to use the Binder handshake.
- LAN sharing is disabled unless explicitly enabled.
- LAN clients must use HTTPS.
- Clients without Aidos pairing support can use the manual HTTPS descriptor.

## Security considerations

LAN does not equal trusted. IP address, subnet, or mDNS discovery must never be treated as authorization.

Safeguards:

- no LAN listener by default;
- explicit user action to enable sharing;
- HTTPS only for LAN;
- standard platform/Ktor TLS implementation;
- persistent Engine identity;
- certificate/public-key fingerprint verification;
- unique credential per external device;
- mandatory expiration;
- manual revocation;
- short-lived pairing codes;
- pairing codes never usable as inference credentials;
- no credentials or private keys in discovery advertisements;
- cryptographically secure credential generation;
- clear UI indication while sharing is active;
- rate limiting and request-size limits as appropriate.

Manual QR codes containing bearer credentials are sensitive and should not be unnecessarily persisted or logged.

## Open questions

1. Should the Engine use a self-signed certificate with public-key/certificate pinning, or a small Aidos local CA?
2. Should code pairing use TOTP specifically, or a purpose-built short-lived challenge/response protocol?
3. Should the Engine automatically stop LAN sharing when the last external grant expires?
4. Should external devices have permissions/capabilities beyond inference access?
5. Should a desktop Aidos client be the first official implementation of code pairing?
6. Should manual descriptors eventually use signed descriptors or public-key-based credential bootstrap?

## Recommended implementation order

### Phase 1 — LAN HTTPS transport

- Add explicit `LOCALHOST` / `LAN` bind mode.
- Generate/persist an Engine TLS identity.
- Configure Ktor HTTPS using the standard Android/JVM TLS stack.
- Bind LAN server only when sharing is enabled.
- Add non-loopback HTTPS integration tests.

### Phase 2 — External-device grants

- Implement per-device credentials.
- Add labels, expiration, and revocation.
- Separate LAN credentials from Binder-issued local tokens.

### Phase 3 — Manual pairing

- Define versioned HTTPS connection descriptor.
- Include Engine identity/fingerprint.
- Add copyable human-readable connection details.
- Add optional QR generation.

### Phase 4 — SDK code pairing

- Define the short-lived pairing-code protocol.
- Implement it in the SDK.
- Add Android UI for displaying the pairing code.
- Exchange the bootstrap code for a per-device credential.

### Phase 5 — Discovery and hardening

- Add mDNS/DNS-SD.
- Add stronger public-key pinning/trust management if required.
- Add richer device permissions and management.

## Decision

LAN inference is an **explicitly enabled, per-device paired capability**, not simply an HTTP server bound to `0.0.0.0`.

The preferred UX is:

> **Add external device → label → expiration → pairing method**

with:

- **Code pairing:** short-lived numeric bootstrap code for SDK-aware clients.
- **Manual pairing:** readable HTTPS connection details with optional QR transmission.

The QR code is deliberately **not** the pairing protocol. It is a convenient transport representation of the manual connection descriptor.

**LAN mode uses HTTPS from the first implementation.** Aidos uses the standard Android/JVM/Ktor TLS stack and does not implement TLS/cryptography itself. The Engine has a persistent local TLS identity whose fingerprint is established during pairing.

The Aidos SDK is recommended for automated pairing, but **the LAN protocol does not require the SDK or a dedicated Aidos application**.
