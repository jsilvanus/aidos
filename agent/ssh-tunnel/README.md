# ssh-tunnel (`fi.italeino.sshtunnel`)

Standalone SSH tunnel library: hold one `-R` (reverse) or `-L` (local) forward up, reconnect with
capped backoff, report state. RFC-0108. **Kernel-free: depends on no other Aidos module**, so other
apps can use it. Aidos-specific wiring (vault key, egress grant, audit) belongs in Engine adapters.

- `SshTunnel(spec, KeyProvider, HostKeyPolicy, SshTransport, scope)`: `start()`, `stop()`, `state`.
- `KeyProvider` hands the private key per connection attempt; the library wipes its copy and never stores it.
- `HostKeyPolicy.pinned("SHA256:...")`; a mismatch is terminal (`HostKeyChanged`), never retried or re-trusted.
- `AuthorizedKeys.forReverse/forLocal` build the restricted server line (`restrict,port-forwarding,permitlisten=...,command="/bin/false"`).
- `KeyGen.ed25519(comment)` (JVM) returns the private key once and the public line to paste.
- `KeyGen.ed25519(comment)` has its own `openssh-key-v1` writer on Bouncy Castle's lightweight API (no MINA, no provider registration).
- Two transports implement `SshTransport`: `SshjTransport` (sshj, pure Java, **the one for Android**, also fine on the desktop JVM) and `MinaTransport` (Apache MINA SSHD, desktop JVM). Both pass the same `TransportContract` suite against an in-process server: reverse forward, local forward, wrong host key, unknown key, refused forward, reconnect.

### Android notes (RFC-0108 phase 4)

`SshjTransport` is plain Java and is exercised on the JVM only. **It has not been run on an Android device or emulator.** What an Android app has to do, untested:

- Put a full Bouncy Castle on the classpath and register it before the first connection (`Security.removeProvider("BC"); Security.insertProviderAt(BouncyCastleProvider(), 1)`); Android's built-in `BC` is cut down and sshj cannot use it.
- Run the tunnel in a foreground service and call `start()` again (or restart the tunnel) on network-change callbacks; a Wi-Fi to cellular switch drops the TCP stream.
- Keep the private key encrypted at rest (Android Keystore wraps an AES key; the Keystore cannot sign Ed25519 for sshj) and decrypt it only inside the `KeyProvider`.
- A local forward (`-L`) cannot learn at connect time that the server's `permitopen` refuses the target; the refusal shows up per connection, not as `ForwardRefused`.
- Tests: fake-transport state machine tests, plus real SSH over loopback against an in-process MINA server. No external server.

Run: `cd agent && gradle :ssh-tunnel:jvmTest`.

To move this to its own repository, copy the directory and its `build.gradle.kts`; there is no other coupling.
