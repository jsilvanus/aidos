# RFC-0108: AIDIN Remote Routes (reaching a peer from outside the LAN)

Status: Draft (proposed with D53, 2026-11-10). Not implemented.

Related: RFC-0105 (inference groups, peer link), RFC-0104 (locality), RFC-0107 (remote agent hosting), RFC-0035 (secrets), RFC-0042 (egress), RFC-0027 (trust and taint), RFC-0051 (LAN sharing, not used here), D47, D50, D52, D53. Study: `plans/remote-routes-home-gpu.md` in the project files.

## Abstract

A phone away from home should be able to use the home desktop Engine's models (`aidos:chat`) and agent runtime (RFC-0107). The AIDIN peer link already authenticates and encrypts end to end with node keys, but peers are only found by mDNS on the LAN. This RFC adds **routes**: ways to open a byte stream to a known peer. The Engine can manage an outbound **SSH reverse tunnel** to a user-owned server (the "autossh to a VPS" setup) as one route kind. A route is a transport, not a placement: the carrier is never trusted and sees only ciphertext.

## Motivation

- RFC-0105 discovery is LAN only; RFC-0051 excludes the internet; D52 needs the peer link to be reachable from anywhere to be useful.
- Users already run SSH reverse tunnels (autossh) and overlay networks (Tailscale, WireGuard). Both should work without Aidos knowing the product.
- Cloudflare-style HTTP tunnels terminate TLS at a third party and would break node-key mTLS, so they are not a peer-link route.

## Design

### Route model

A peer record (pair or group member) gains an ordered list of route hints. A route is one of:

| Kind | Meaning |
|---|---|
| `lan` | mDNS discovery (today's behaviour) |
| `direct` | `host:port` the peer can be dialled at: a Tailscale/WireGuard/ZeroTier address, a forwarded router port, a tunnel's public end |
| `ssh-reverse` | Engine-managed outbound SSH session that publishes this Engine's peer port on a server the user owns (below). The *other* side dials the server's `host:port` as a `direct` route |

The dialer tries `lan` first, then hints in order, with fast redial on network change. The TLS handshake always verifies the peer's node key, so a hint can point at anything without becoming trusted. The QR/pair record carries hints; remote-first pairing (QR that already holds a hint) is allowed but the desktop must confirm the six-digit code on screen.

### Placement and locality

A route does not change placement (D47, RFC-0104). A peer reached over a tunnel is the same key-identified node; its `local` is the origin's `cluster`. Nothing a carrier can see is plaintext.

### What may travel over a remote route

Allowed: delegation (`aidos:chat`, `peer:<node>/<model>`), RFC-0107 agent endpoints and the tool relay, heartbeats.
Refused: **layer split** (RFC-0105 phase 3) and any helper role. The planner measures round-trip time per route and only offers split over a route classed `lan`. (Measured spike overhead is about 10 ms per token per helper on loopback; WAN would be unusable.)

### Remote-access switch

"Share with group" opens the peer listener for LAN peers. Using a peer over a remote route is a separate **per-peer opt-in** ("Allow remote access"), off by default, shown in the UI and written to the audit log. A peer that is not allowed is rejected at the listener even when the tunnel delivers its connection. The listener is not rebound wider; the tunnel connects to the existing port.

### SSH tunnel (`ssh-reverse`)

Implemented in-process with an SSH library (sshj or Apache MINA SSHD), not by shelling out: Windows has no autossh, and in-process lets the key come from the vault and the host key be pinned. The manager does what autossh does: keepalive, reconnect with capped exponential backoff, and a state shown in the UI (connecting, up, host key changed, auth refused).

**Tunnel key page.** The user creates a tunnel in Settings. The Engine generates an Ed25519 key pair, stores the private key in the vault, and shows only the public key as a ready-to-paste `authorized_keys` line:

    restrict,port-forwarding,permitlisten="127.0.0.1:<port>",command="/bin/false" ssh-ed25519 AAAA… aidos-tunnel-<nodeId>

`restrict` removes pty, agent, X11 and user-rc; `port-forwarding` re-enables only forwarding; `permitlisten` limits it to the one reverse port; the forced command gives no shell. The page also shows the sshd line needed to let the phone reach the port directly (`GatewayPorts clientspecified`) with a plain statement that this exposes an mTLS port to the internet. Private keys are never displayed, exported or written to disk by the Engine.

**Setup key (installer), optional.** Pasting a line into `authorized_keys` by hand is the default. To let the Engine install it instead, as farcmd-mcp does, the user may supply a **setup key**: an existing SSH key for an admin account on the server, kept under a passphrase. Rules:

- Vault kind `SSH_SETUP_KEY` (third kind alongside tunnel and command keys), stored encrypted with its own passphrase that the Engine does not keep. The user types the passphrase for each install.
- It is used only by the installer, once: connect, append the restricted tunnel-key line, verify the line is there, disconnect. It is never used to carry the tunnel and never resolvable by the tunnel manager.
- After the install the user is offered to delete it from the vault. Re-install, rotate and remove run the same way and ask for the passphrase again.
- The installer shows the exact line and the host fingerprint and waits for confirmation before writing. It touches only the Aidos-marked line (`aidos-tunnel-<nodeId>`) and never edits other entries.
- The same setup-key mechanism is what a future command-key RFC would use to install a verifier and per-command keys on a target (farcmd's master key plus root-owned integrity verifier).

**Host key.** The server's host key fingerprint is shown at setup and the user confirms it. It is pinned. A change stops the tunnel and raises a notice; no silent trust-on-first-use on reconnect.

**Vault.** The key is a new `SecretKind.SSH_TUNNEL_KEY` (RFC-0035 amendment) with `allowedConsumers = [tunnel manager]`, so other consumers cannot resolve it. The redactor already covers boundaries.

**Egress (RFC-0042).** The server `host:port` is a scoped egress destination the user grants once. Connect, disconnect and failure events are audited; contents never.

### Two kinds of SSH keys (not interchangeable)

| | Tunnel key (this RFC) | Command key (future RFC, not designed here) |
|---|---|---|
| Purpose | carry the peer link | let the agent run specific commands on a machine |
| Server side | `restrict,permitlisten=…,command="/bin/false"` | `restrict,command="<one script>"`, one key per command |
| Vault kind | `SSH_TUNNEL_KEY` | `SSH_COMMAND_KEY` (reserved name) |

A third kind, `SSH_SETUP_KEY` (passphrase-locked, installer only), serves both and carries neither.

| Governed by | tunnel manager, remote-access switch | agent permissions, approval, taint |

The vault kinds are separate so a tunnel key can never be used to run a command and vice versa. A later RFC for agent-run commands over SSH can follow the pattern of the user's farcmd-mcp: one Ed25519 key per command pinned with `restrict,command=…` to a single installed script, risk levels with human approval for high levels, an integrity check of the installed script before each run, and an audit chain. That is out of scope here; this RFC only reserves the separation.

### Liveness and failure

Heartbeat 3 s and grace 20 s were chosen for LAN. On a remote route the grace is configurable per route (default 60 s) and a dropped stream is redialled at once. For RFC-0107's open question: a desktop-hosted run **continues for a bounded window (default 5 minutes, configurable) after the origin drops and is resumable by `seq`**, then is cancelled. Tool calls that need the origin's tool relay wait or fail with a clear status during the window.

### Phone side

Phase 1 needs no SSH on the phone: it dials the server's public port as `direct`. Phone-side SSH (a `-L`/jump forward so the server exposes only sshd) needs an SSH client in the Android host and Keystore-backed key storage, which the vault does not yet have (JVM only). It is phase 4.

## Threats

| Threat | Mitigation |
|---|---|
| Carrier (VPS, overlay coordinator) reads traffic | End-to-end mTLS with node keys; carrier sees ciphertext and timing only |
| Carrier impersonates a peer | Needs a node private key; handshake verifies key against the trust store |
| Stolen tunnel key | `restrict` + `permitlisten` + forced command: can only squat the port (black-hole), not read traffic or get a shell |
| Stolen phone | Revoke its pair record on the desktop; the tunnel holds no phone secret |
| Open port on the server | Peer listener accepts only keys in the trust store; unauthenticated callers fail the handshake; remote-access switch per peer |
| Changed server host key | Tunnel stops, user notified, no auto re-trust |
| Layer split over WAN | Refused by planner on non-`lan` routes |

## Not covered

Cloudflare Tunnel and other TLS-terminating proxies for the peer link (they would be `remote` placement and are a RFC-0051 concern for generic clients); running commands over SSH (future RFC); Tailscale-specific integration (works as `direct`); NAT traversal of our own.

## Phases

1. Route hints in pair records and QR, dialer fallback, "Add address" UI on desktop and Android, docs for Tailscale/WireGuard. Deterministic in-memory transport tests.
2. Per-route RTT, split planner refuses non-`lan`, per-peer remote-access switch and audit.
3. Desktop SSH reverse tunnel: tunnel key page, vault kind, host pinning, backoff, state UI. Tested against an in-process SSH server, never a real server.
4. Phone-side SSH and Android Keystore vault.
5. RFC-0107 bounded continue-then-cancel behaviour.

## Amendments to other RFCs (made with implementation)

- RFC-0035: `SSH_TUNNEL_KEY`, `SSH_COMMAND_KEY` and `SSH_SETUP_KEY` kinds (setup key passphrase-locked, never kept by the Engine); generate/show-public-key/rotate for the tunnel key moves into scope.
- RFC-0042: tunnel server as a scoped egress destination.
- RFC-0105: route hints in pair records; remote routes never carry helper roles.
- RFC-0107: the bounded continue-then-cancel rule.
