# Policy: RFC-0108 Phase 2

Kotlin Multiplatform library for route planner policy and per-peer remote-access control.

**Status**: Phase 2 implementation of RFC-0108: AIDIN Remote Routes.

## Overview

Phase 2 integrates routes (Phase 1) with inference split policy and per-peer remote-access control.

Key decisions:
- Layer split (RFC-0105) is only safe over LAN routes due to ~10ms per-token-per-helper overhead
- WAN routes (Tailscale, SSH tunnel) cannot carry split due to high latency
- Using a peer over a remote route requires explicit user opt-in, logged in audit trail
- If a peer's only route is Lan, remote-access switch is hidden (not applicable)

## Usage

### Route Planner Policy

```kotlin
val policy = RoutePlannerPolicy(lanRttThreshold = 50.milliseconds)

val latencies = listOf(
    RouteLatency(Route.Lan, rttMillis = 10),
    RouteLatency(Route.Direct("peer-tailscale", 8766), rttMillis = 35),
    RouteLatency(Route.Direct("peer-wan", 8766), rttMillis = 150),
)

// Is each route safe for split?
policy.isSafeForSplit(latencies[0]) // true (Lan)
policy.isSafeForSplit(latencies[1]) // true (35ms < 50ms threshold)
policy.isSafeForSplit(latencies[2]) // false (150ms > 50ms threshold)

// Filter to only safe routes, sorted by speed
val localRoutes = policy.filterLocalRoutes(latencies)
// Result: [Lan @ 10ms, Direct @ 35ms]

// Decide if peer can be used for split at all
policy.hasLocalRoute(latencies) // true (Lan is available)

// Get best route for split (fastest local)
val best = policy.selectBestLocalRoute(latencies)
// Result: Lan @ 10ms
```

### Remote Access Policy

```kotlin
val remoteAccess = RemoteAccessPolicy()

// User enables remote access for a peer via UI
remoteAccess.allow("peer-1")

// Check if a peer can use remote routes
remoteAccess.isRemoteAccessAllowed("peer-1") // true

// Filter a peer's routes based on policy
val peerLatencies = listOf(
    RouteLatency(Route.Lan, 10),
    RouteLatency(Route.Direct("peer", 8766), 100)
)

// If not allowed and peer has remote routes, only Lan is returned
val allowed = remoteAccess.filterAllowedRoutes("peer-1", peerLatencies)
// With permission: [Lan, Direct]
// Without permission: [Lan]

// Audit event when user changes remote-access switch
val audit = RemoteAccessAuditEvent("peer-1", "Home Desktop", allowed = true)
// Log: "Remote access enabled for peer Home Desktop at 1234567890"
```

## Design Decisions

**LAN-only split**: Layer split helper overhead is ~10ms per token per helper. Over 100ms WAN latency, this becomes prohibitive. Conservative RTT threshold of 50ms ensures Tailscale and direct routes over fast overlays qualify as "local", while cellular and SSH are rejected.

**Per-peer opt-in**: Remote access is a security boundary—using a peer over the internet is different from local-only. Explicit user choice, shown in UI, logged in audit trail, ensures transparency.

**Observable state**: Both policies expose state flows so UI can enable/disable features as policy changes.

**Kernel-free**: No Aidos kernel dependencies. Can be used by any system that routes to multiple peers.

## Testing

All tests are in-process:

```bash
cd agent && gradle :policy:jvmTest
```

Tests cover:
- LAN routes always safe for split
- Direct/SSH routes only safe if RTT ≤ threshold
- Unmeasured routes conservatively refused
- Filtering and sorting by RTT
- Remote-access allow/disallow/toggle
- Route filtering with and without permission
- Audit event formatting

## Future Work (RFC-0108 Phases 1-5)

- **Phase 1** ✅: Route hints and dialer (done in :routes module)
- **Phase 2** (this): Route planner policy (done in :policy module)
- **Phase 3**: SSH tunnel library and Engine adapter (done in :ssh-tunnel, :tunnel-manager)
- **Phase 4**: Android SSH client with Keystore vault
- **Phase 5**: Bounded continue-then-cancel (RFC-0107)

Integration points deferred:
- Engine split planner uses RoutePlannerPolicy.isSafeForSplit() to decide which peers can participate
- Peer listener uses RemoteAccessPolicy.filterAllowedRoutes() before accepting connections
- Audit subsystem logs RemoteAccessAuditEvent when policy changes

## License

EUPL-1.2
