# ssh-tunnel (`fi.italeino.sshtunnel`)

Standalone SSH tunnel library: hold one `-R` (reverse) or `-L` (local) forward up, reconnect with
capped backoff, report state. RFC-0108. **Kernel-free: depends on no other Aidos module**, so other
apps can use it. Aidos-specific wiring (vault key, egress grant, audit) belongs in Engine adapters.

- `SshTunnel(spec, KeyProvider, HostKeyPolicy, SshTransport, scope)`: `start()`, `stop()`, `state`.
- `KeyProvider` hands the private key per connection attempt; the library wipes its copy and never stores it.
- `HostKeyPolicy.pinned("SHA256:...")`; a mismatch is terminal (`HostKeyChanged`), never retried or re-trusted.
- `AuthorizedKeys.forReverse/forLocal` build the restricted server line (`restrict,port-forwarding,permitlisten=...,command="/bin/false"`).
- `KeyGen.ed25519(comment)` (JVM) returns the private key once and the public line to paste.
- `MinaTransport` (JVM) is Apache MINA SSHD with Bouncy Castle for Ed25519. An Android transport is a separate implementation of `SshTransport` (RFC-0108 phase 4); the rest is shared.
- Tests: fake-transport state machine tests, plus real SSH over loopback against an in-process MINA server. No external server.

Run: `cd agent && gradle :ssh-tunnel:jvmTest`.

To move this to its own repository, copy the directory and its `build.gradle.kts`; there is no other coupling.
