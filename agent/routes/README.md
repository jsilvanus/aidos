# Routes: RFC-0108 Phase 1

Kotlin Multiplatform library for route discovery, dialing, and fallback.

**Status**: Phase 1 implementation of RFC-0108: AIDIN Remote Routes.

## Overview

RFC-0108 enables phones and other clients to reach home-based Engine instances through multiple routes: LAN (mDNS), direct connections (overlay networks, forwarded ports), and SSH reverse tunnels.

Phase 1 defines:
- **Route model**: Lan, Direct, SshReverse (sealed interface)
- **RouteHints**: Ordered list of routes to try (always starts with Lan)
- **RouteDialer**: Coroutine-based dialer that tries routes in order, measures RTT, and redials on network change
- **DialerState**: Observable state for UI/metrics
- **DialResult**: Success (with RTT) or Failure

## Usage

```kotlin
// Define routes: LAN first, then direct address, then SSH tunnel
val hints = RouteHints(
    listOf(
        Route.Lan,
        Route.Direct("peer-tailscale", 8766),
        Route.SshReverse("home", "home.example.com", 47100)
    )
)

// Provide dial functions for each route type
val dialers = mapOf<Class<out Route>, suspend (Route) -> Long>(
    Route.Lan::class.java to { route ->
        // Attempt mDNS discovery, return RTT
        mdnsDiscoveryWithRtt(route)
    },
    Route.Direct::class.java to { route ->
        // Attempt direct connection, return RTT
        connectWithRtt(route as Route.Direct)
    },
    Route.SshReverse::class.java to { route ->
        // SSH tunnel already open; just verify
        verifyTunnelWithRtt(route as Route.SshReverse)
    }
)

// Create dialer
val dialer = RouteDialer(hints, dialers, scope)

// Start dialing
dialer.startDial()

// Observe state
dialer.state.collect { state ->
    when (state) {
        is DialerState.Connected -> println("Connected via ${state.route} (RTT: ${state.rttMillis}ms)")
        is DialerState.Dialing -> println("Trying route ${state.routeIndex}...")
        is DialerState.AllFailed -> println("All ${state.routeCount} routes failed")
        DialerState.Idle -> {}
    }
}

// On network change (Wi-Fi → cellular, online → offline), redial
onNetworkChange { dialer.onNetworkChange() }

// Stop when done
dialer.stop()
```

## Design Decisions

**Route as sealed interface**: Allows future route types (e.g., Relay) without breaking existing code.

**Dial functions return RTT**: Caller implements connection logic; dialer only times it. This keeps the library transport-agnostic.

**RouteHints always start with Lan**: Ensures we try local discovery first, then fallback to hints. Makes the happy path fast (no options to pick wrong order).

**RouteLatency tracks failures as -1 RTT**: Allows the planner and UI to show "attempted but failed" without extra state.

**Fast redial on network change**: Immediately restart dialing when network changes, rather than waiting for timeout. Phones switch Wi-Fi↔cellular often.

**Per-route timeout**: Each route gets its own timeout window (default 15s), not a global budget. If the first route takes 14s, the second still gets 15s.

## Testing

All tests are in-process, using Kotlin Multiplatform test framework:

```bash
cd agent && gradle :routes:jvmTest
```

Tests cover:
- Happy path (dial succeeds on first route)
- Fallback (first route fails, second succeeds)
- RTT measurement
- Timeout per route
- Stop/cancellation
- Route validation

## Future Work (RFC-0108 Phases 2-5)

- **Phase 2**: Per-route latency tracking for split-planner policy
- **Phase 3**: SSH tunnel library (already complete in `agent/ssh-tunnel`)
- **Phase 4**: Phone-side SSH with Android Keystore vault
- **Phase 5**: Bounded continue-then-cancel (RFC-0107)

## License

EUPL-1.2
