# Wire-AIDIN: RFC-0108 Phase 1 Wire Format Extension

Kotlin Multiplatform library for encoding/decoding route hints in AIDIN wire protocol.

**Status**: Phase 1 wire format implementation.

## Overview

RFC-0108 Phase 1 extends AIDIN's pairing wire protocol to carry route hints, enabling discovery and fallback dialing across multiple routes.

## Wire Format

PairingInvite already carries `addresses: List<String>` with LAN addresses. Phase 1 extends this with structured route hints:

Format:
1. LAN addresses (literal hostnames/IPs, e.g., "192.168.1.100", "fe80::1")
2. Explicit "lan" marker (separates LAN addresses from route hints)
3. Route hints:
   - "direct://host:port" (Tailscale, forwarded ports, tunnel endpoints)
   - "ssh://tunnelId:serverHost:serverPort" (SSH reverse tunnels)

Example addresses list:
```
["192.168.1.100", "lan", "direct://peer-tailscale:8766", "ssh://home:home.example.com:47100"]
```

## Usage

```kotlin
import dev.aidos.routes.Route
import dev.aidos.routes.RouteHints
import dev.aidos.wire.RouteHintCodec

// Create route hints
val hints = RouteHints(
    listOf(
        Route.Lan,
        Route.Direct("peer-tailscale", 8766),
        Route.SshReverse("home", "home.example.com", 47100)
    )
)

// Encode to wire format (addresses list for PairingInvite)
val addresses = RouteHintCodec.encode(listOf("192.168.1.100"), hints)
// Result: ["192.168.1.100", "lan", "direct://peer-tailscale:8766", "ssh://home:home.example.com:47100"]

// Put in PairingInvite
val invite = PairingInvite(publicKey, secret, addresses, port = 8765)
val uri = invite.toUri()  // Encodes as: aidin:pair?v=1&k=...&a=192.168.1.100,lan,direct://...

// Decode on receiving side
val (lanAddresses, decodedHints) = RouteHintCodec.decode(invite.addresses)!!
// lanAddresses: ["192.168.1.100"]
// decodedHints: RouteHints with [Lan, Direct("peer-tailscale", 8766), SshReverse("home", ...)]

// Create dialer with decoded hints
val dialer = RouteDialer(decodedHints, dialers, scope)
dialer.startDial()
```

## Design

**Forward compatibility**: Old clients that don't understand "direct://" and "ssh://" strings will treat them as literal hostnames and fail to dial, then fall back to LAN (which always works on the local network). New clients parse structured hints and try them in order.

**No PairingInvite API change**: The existing `addresses` list is reused; route hints are encoded as strings within it. Old code that just uses the list as-is still works (backward compatible).

**Separate encoding/decoding**: RouteHintCodec is independent of PairingInvite, so it can be used in any system that needs to encode routes in a string list.

## Testing

21 comprehensive unit tests cover:
- Encoding with various route combinations
- Decoding with and without route hints
- LAN address preservation
- Forward compatibility (unknown route types skipped)
- Error handling (malformed routes, invalid ports)
- Round-trip encode/decode consistency
- Individual route parsing

## Future Integration

1. **Engine AIDIN module**: Modify PairingInvite serialization to automatically encode/decode route hints
2. **Pairing UI**: When creating a QR code, include route hints (direct addresses, SSH tunnel public endpoint)
3. **Peer records**: Store decoded route hints with peer, pass to RouteDialer
4. **Desktop UI**: "Add address" button encodes new routes and updates peer record

## License

EUPL-1.2
