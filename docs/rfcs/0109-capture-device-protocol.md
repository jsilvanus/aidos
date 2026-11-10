# RFC-0109: Capture Device Protocol (ACP) and Engine Receiving Side

Status: Draft (2026-11-10, revised same day after owner review). Not implemented. Planning only: no code accompanies this RFC.

Revision 2 (owner, 2026-11-10): (1) **buttons are user-configured bindings**, any number per device, each binding routing its speech to a chosen sink; (2) **trust is configured by the user per binding**, not fixed at UNTRUSTED, because untrusted input would defeat hands-free operation (RFC-0057); (3) **Omi is deferred and may be removed**; (4) **the code must be modular so it can be split out** of this repository later; (5) pairing stays mandatory.

Related: RFC-0027 (trust and taint), RFC-0035 (secrets and vault), RFC-0036 (settings), RFC-0046 (identity and device identity), RFC-0056 (retention), RFC-0057 (glanceable and hands-free), RFC-0103 (Engine), RFC-0104 (locality), RFC-0105 (inference groups), RFC-0107 (remote agent hosting); D47, D48, D49, D51, D52. Hardware study: `plans/ai-cube-esp32.md` and `plans/ai-button-existing-products.md` in the project files. Engine API v1 (`sdk/CONTRACT.md`) is frozen and is not changed here.

Evidence labels: **[sourced]** = read from a vendor or project page today; **[known]** = background knowledge, not re-checked; **[estimate]** = arithmetic or guess to be replaced by a measurement; **[decision]** = a choice this RFC proposes.

## Abstract

A small voice device (the planned ESP32 "AI cube", or someone else's button) captures speech and hands it to an Aidos Engine over Bluetooth LE. This RFC defines two things:

1. **ACP, the Aidos Capture Protocol**: an open, versioned, transport-neutral protocol (GATT services, CBOR control messages, audio framing, recording sync, pairing, device configuration, display cards) that anyone can implement to build an Aidos-compatible button without Aidos code.
2. **The receiving side**: how an Engine host (Android first, desktop too) pairs a device, treats it as a revocable principal, receives live and stored audio, decodes it, runs speech-to-text through the existing Engine STT, and delivers the transcript to a destination, under the existing trust, locality and grant rules.

The cube is the first device, not the only one. The Engine knows devices by the protocol, never by product.

## Motivation

- Juha's decisions for the cube (plan §0): it is a **source into the Aidos on the phone**; offline store-and-forward is a first-class role; microSD/eMMC is in the v1 BOM; display is e-paper or LCD; speaker is deferred; no always-on capture.
- The Engine already has the sink: `POST /v1/audio/transcriptions` takes a base64 WAV and runs Whisper ONNX on both hosts (`docs/stt.md`; Finnish needs whisper-small or larger). What is missing is everything between a Bluetooth radio and that endpoint.
- Without a written protocol, firmware and host would drift, and nobody else could build a button. Writing it before the firmware exists is cheap; changing a shipped GATT table is not.
- The only open BLE audio device on the market, Omi (MIT), has a documented audio service but no storage sync, no cards, no config and no identity proof [sourced: docs.omi.me Protocol page documents audio, battery and device-info services only]. ACP keeps Omi's audio service as an optional compatibility leg and adds what Aidos needs.

## Goals

1. Define the GATT layout, message types, audio framing, recording sync, pairing and configuration so a third party can implement a device from this text and test vectors alone.
2. Define the Engine-side components, their placement in the existing modules, and how they obey RFC-0027, RFC-0104 and the per-app grant model.
3. Be transport-neutral: the same messages run over BLE (v1), USB CDC (development and wired use) and later Wi-Fi/TLS (RFC-0105 device principal).
4. Make the protocol testable in CI with no radio: byte-level test vectors, a software virtual device, a fake transport.
5. Keep the privacy rules of the cube plan (§8) enforceable at the protocol level where a protocol can enforce them.

## Non-goals

- Firmware design, hardware choice, enclosure (cube hardware thread).
- Speaker output and spoken replies (v2; reserved message types only).
- On-device speech recognition.
- A cloud service. ACP terminates at the user's own Engine host.
- iOS hosts. The Android and desktop Engines are the hosts.
- Changing Engine API v1. Delivery to apps is additive (see Delivery).

## Design

### Roles

| Party | Role |
|---|---|
| **Device** | BLE peripheral and GATT server. Owns the microphone, button, storage, optional display and haptics. Initiates all recording. |
| **Host** | An Aidos Engine (phone or desktop). BLE central and GATT client. Pairs, authenticates, receives audio, syncs stored recordings, sets config, pushes cards. |
| **Bridge** | The Engine component that implements the host side of ACP (below). |
| **Sink** | Where a transcript goes: an app (Dictator, Messenger) or an agent run. |

**Normative: the host can never start the microphone.** There is no host-to-device "record" message. Recording starts only from a physical gesture on the device (RFC cube plan §8.1). A device that accepts a remote start is not ACP-conformant.

### Transport

#### BLE (v1)

The device advertises the ACP service UUID, its name, and a 2-byte capability summary in manufacturer data (so a host can filter a scan without connecting). The host connects as central, negotiates a larger ATT MTU (**required: at least 185 bytes** [decision]; Android requests up to 517 [known]) and, during streaming, a 15-30 ms connection interval. A device that cannot reach the MTU floor is not conformant; the floor keeps one 20 ms audio frame and one control chunk in a single notification.

**ACP service** `97fdc04e-aeef-41b1-a046-f558dfb1bef2` (random UUIDv4 generated for this RFC [decision]; the characteristic UUIDs below share the base and differ in the first group).

| Characteristic | UUID first group | Properties | Purpose |
|---|---|---|---|
| ACP Info | `97fdc0a1` | read | CBOR `info` blob: protocol versions, device class, vendor, firmware, capability list, L2CAP PSM if any. Readable before bonding; contains no secrets. |
| ACP Control | `97fdc0a2` | write without response, indicate | Request and response messages (CBOR, fragmented). Both directions. Requires an encrypted, bonded link. |
| ACP Audio | `97fdc0a3` | notify | Live audio frames (binary, framing below). |
| ACP Sync | `97fdc0a4` | notify, write without response | Bulk transfer of stored recordings when no L2CAP channel is used. |
| ACP Events | `97fdc0a5` | notify | Small unsolicited events: button, battery, storage, recording state. |

Standard services are used where they exist: Battery Service `0x180F` / Battery Level `0x2A19` and Device Information `0x180A` [sourced, same as Omi]. The Events characteristic carries battery too, so a host that ignores the standard service loses nothing.

**Omi compatibility: deferred, may be removed** [owner, rev. 2]. Omi's audio service (`19B10000-E8F2-537E-4F6C-D104768A1214`) [sourced] is not part of ACP v1. Nothing in this RFC depends on it, and the host has no Omi adapter planned. If it is ever wanted it is a separate adapter module outside `capture-core`, and an Omi device would have no application identity, so it would need its own trust rules. Until then, the RFC makes no promise about it.

**L2CAP connection-oriented channel** (optional): the device publishes a PSM in ACP Info. Bulk sync prefers it (Android 10+ [known]); otherwise ACP Sync is used. Both carry the same chunks.

#### Other transports

Same messages, different carrier. **USB CDC** (the cube's native USB): control and audio multiplexed by the stream framing below; it is the development transport and the CI path for hardware-in-the-loop tests. **Wi-Fi/TLS** (device as an AIDIN client, cube plan option B): out of scope here, designed later as an RFC-0105 device principal; the messages do not change, so it needs no new protocol.

### Control messages

CBOR (RFC 8949) maps with unsigned-integer keys. Envelope:

| Key | Name | Type | Meaning |
|---|---|---|---|
| 0 | `v` | uint | Protocol major version of the sender (1). |
| 1 | `t` | uint | Message type. |
| 2 | `id` | uint | Sender-chosen request id; responses echo it in key 3. |
| 3 | `re` | uint | `id` this message answers (responses only). |
| 4 | `b` | map | Body, defined per type. |

Unknown keys are ignored; unknown message types get an `error` with code `unsupported`. Vendor extensions use types `0x8000` and above and text keys prefixed `x-<vendor>-`. Maximum message size 4096 bytes [decision]; a host drops a connection that exceeds it.

**Chunking.** A message is cut into chunks that fit one ATT payload. Each chunk starts with a 2-byte header: `flags` (bit 0 = first, bit 1 = last) and a 1-byte chunk counter that increments within the message; the rest is a CBOR slice. Reassembly state is per direction and discarded on disconnect or on a counter gap.

Message types (`D→H` device to host, `H→D` host to device):

| Type | Name | Dir | Body (key: meaning) |
|---|---|---|---|
| 0x01 | `hello` | D→H | `versions[]`, `fw`, `caps[]`, `devpub` (COSE_Key, P-256), `bat`, `storage{free,total}`, `unsynced`, `clock{t,q}` |
| 0x02 | `auth_challenge` | H→D | `nonce` (32 bytes), `host` (host key fingerprint) |
| 0x03 | `auth_response` | D→H | `sig` over the transcript below |
| 0x04 | `pair_request` | D→H | `devpub`, `name`, `oob?`; sent only while the device is in pairing mode |
| 0x05 | `pair_accept` | H→D | `hostpub`, `hostname`, `grants[]` (granted capability names) |
| 0x06 | `unpair` | H→D | none; device deletes that host's bond data |
| 0x07 | `error` | both | `code`, `detail?` |
| 0x10 | `time_set` | H→D | `unix_ms`, `tz?` |
| 0x11 | `config_get` | H→D | `keys[]` or empty for all |
| 0x12 | `config_value` | D→H | `values{}` |
| 0x13 | `config_set` | H→D | `values{}`; answer is `config_value` or `error` |
| 0x20 | `rec_open` | D→H | `rec`, `codec`, `rate`, `frame_ms`, `t0{unix_ms,q}`, `mode` (`live` or `stored`), `preroll_ms` |
| 0x21 | `rec_close` | D→H | `rec`, `frames`, `bytes`, `reason` (`released`, `toggled`, `full`, `battery`) |
| 0x22 | `rec_list` | H→D | `after?`, `limit?` |
| 0x23 | `rec_list_resp` | D→H | `recs[]` of `{rec, t0, dur_ms, bytes, codec, sha256, synced}` |
| 0x24 | `rec_get` | H→D | `rec`, `offset`, `len?`; data then arrives as sync chunks |
| 0x25 | `rec_ack` | H→D | `rec`, `sha256`; device may now delete |
| 0x26 | `rec_delete` | H→D | `rec`; refused if not acked unless `force` and the user confirmed |
| 0x30 | `card` | H→D | card body below |
| 0x31 | `haptic` | H→D | `pattern` (small registry) |
| 0x40 | `wipe` | H→D | requires user confirmation on the device |
| 0x80 | `speak` | H→D | reserved for v2; v1 devices answer `unsupported` |

**Events** (ACP Events, unchunked, a CBOR map each): `button{id, kind}` (`press`, `release`, `long`, `click2`), `rec_state{state, rec}`, `battery{pct, charging}`, `storage{free, unsynced, warn}`, `indicator{on}`. Events are advisory UI and logging input; they carry no authority.

**Error codes**: `unsupported`, `bad_request`, `not_paired`, `not_authorized`, `busy`, `too_large`, `no_such_rec`, `storage_full`, `locked_key`, `internal`.

### Audio

#### Codecs

| Codec id | Name | Frame | Typical rate |
|---|---|---|---|
| 1 | `opus` | one Opus packet, 16 kHz mono, 20 ms, CBR or constrained VBR, 16-32 kbps | default |
| 2 | `adpcm` | IMA ADPCM, 16 kHz mono, 20 ms (320 samples), frame starts with 4-byte block header `{pred:s16le, index:u8, 0}` | 64 kbps; fallback where Opus is not yet measured on the device |
| 3 | `pcm16` | 16 kHz mono PCM16 LE, 20 ms = 640 bytes | debugging and conformance; needs the MTU floor plus chunking, so live use is discouraged |

Codec ids 1-3 are the v1 registry; new ids are added by RFC amendment, not by vendors. The 20 ms frame fits the MTU floor at Opus rates (24 kbps = 60 bytes) and ADPCM (164 bytes). Raw throughput is far below BLE limits [estimate]; the constraint is connection-interval scheduling, not bandwidth.

#### Live framing (ACP Audio and the USB stream)

Each notification is **one frame**:

```
u16le seq        per recording, starts at 0, wraps at 65536
u8    flags      bit0 SOS (first frame), bit1 EOS (last frame), bit2 PREROLL (captured before the press)
u8[]  payload    exactly one codec frame
```

Context (codec, rate, frame length, start time) comes from `rec_open`, sent on ACP Control before the first frame; a host that sees frames without an open recording drops them. Frame time is `t0 + n * frame_ms`, where `n` counts from the first PREROLL frame, so a rolling pre-roll buffer (cube plan: 300-500 ms) keeps the first syllable and the timeline stays honest.

**Live audio is best effort.** A gap in `seq` is concealed (Opus PLC; zero-fill for ADPCM/PCM), counted and reported. It never blocks the transcript. **Stored recordings are authoritative**: when the device has storage, every recording is written to storage before or while it is streamed, and the host reconciles through sync (see below), replacing a lossy live transcript if the stored copy differs materially. A device without storage may declare `live_only`; its recordings cannot be re-fetched.

#### Stored recordings and sync

Stored format: one file per recording, **Ogg Opus** (RFC 7845) when codec is Opus, otherwise a WAV container, plus the metadata the `rec_list` entry carries. Content identity is `sha256` of the file bytes; `rec` is a UUIDv7 generated on the device (RFC-0046 uses UUIDv7 for ids). Sync is:

1. Host `rec_list` (cursor `after`), device answers with unsynced entries first.
2. Host `rec_get{rec, offset}`. Device sends chunks on the L2CAP channel or ACP Sync: `u32le offset` then up to MTU-8 bytes, last chunk marked by a following `rec_close`-style trailer `{rec, bytes, sha256}` on Control.
3. Host verifies `sha256`, commits the recording, sends `rec_ack{rec, sha256}`. **The device deletes only after `rec_ack`** (cube plan §0). Repeating step 2 with a nonzero `offset` resumes a cut transfer. Syncing the same `rec` twice is idempotent: the host keys on `(device id, rec)`.
4. Storage-full behaviour is the device's, set by config (`storage_policy`: `refuse_new`, the default, or `overwrite_oldest_synced`); it never overwrites an unsynced recording unless the user explicitly sets that. The `storage` event warns at 80%.

Bulk volume: an hour of 16 kbps Opus is about 7 MB; at a realistic 20-100 kB/s over GATT or L2CAP that is minutes, not seconds [estimate, to be measured in the cube's Phase 1].

**Timestamps.** `t0.q` is a time-quality flag: `0` unset RTC, `1` set once and drifting, `2` set from the host this boot. The host sends `time_set` on every authenticated connect. The bridge stores both the device time and its own receive time, so a recording made with quality 0 still sorts correctly relative to its neighbours.

### Pairing, identity and trust

Layered, each layer adding something the one below cannot:

1. **BLE LE Secure Connections bonding** gives link encryption. **"Just Works" is not conformant for Control** [decision]. A device with a display uses passkey entry (it shows six digits, the host asks for them) or numeric comparison. A device without a display uses out-of-band data: a QR code on the device or box carries `{devpub fingerprint, 128-bit secret}`; the host scans it and the secret is used as the LE OOB value, so nothing is accepted from a stranger in range.
2. **Device key.** The device generates an **ECDSA P-256 key pair** at first boot and never exports the private key. P-256 rather than Ed25519 [decision]: Android Keystore verifies P-256 on every API level the Engine targets, while Ed25519 only arrived in API 33 [known], and ESP32 mbedtls accelerates it [known]. The **device id** is the SHA-256 of the public key; UIs show the first 4 bytes grouped.
3. **Application pairing.** In pairing mode (button held, visible on the display or by LED) the device sends `pair_request`. The host shows an approval card in the Engine UI (same shape as Connected Apps: name, device id, capabilities asked for). The user must confirm **on the host screen and on the device** (button). On accept the host sends `pair_accept` with its own public key; the device stores the host key fingerprint and accepts config, time and unpair only from hosts it has paired with (a device may keep up to 4, default 2: phone and desktop [decision]).
4. **Per-connection authentication.** Bonding alone is not an identity: a cloned or replayed bond must not become a trusted capture source. After each connect the host sends `auth_challenge`; the device signs `"ACP1-auth" ‖ nonce ‖ devpub ‖ host fingerprint` with the device key. The host rejects a connection whose signature does not match the key it paired. Audio received before authentication is dropped.
5. **Device principal.** The host records the device as an actor of kind device (RFC-0046 ActorKind; the exact enum value is for the implementer to confirm against the code), with revocable **grants** chosen at approval: `capture` (audio in), `cards` (host may push to the display), `config` (host may change settings), `sync` (host may pull stored recordings). Revocation sets the grant row to revoked, drops the bond, and sends `unpair` at the next contact. Taking effect is immediate on the host: frames from a revoked device are dropped even if the radio link is up.
6. **Recordings at rest on the device** are the device's responsibility (cube plan §8.3: flash encryption, per-file keys wrapped by the device key). The BLE link is encrypted, so sync carries decrypted file bytes; no application-layer encryption is added in v1 [decision]. If the Wi-Fi transport is added, TLS covers it.

**Pairing is mandatory** [owner, rev. 2]. A device that has not completed pairing and per-connection authentication can send nothing the host will act on. There is no unauthenticated capture mode in v1.

### Buttons, bindings and trust (RFC-0027)

#### Buttons are bindings the user configures

A device declares its buttons in `hello` (`buttons[]`: `{id, label, kinds[]}`, where kinds are the gestures it can detect: `press`, `hold`, `click2`, `long`). A single-button device declares one; a cube may declare several. The user then creates **bindings** on the host, any number, one per `(button, gesture)`:

```
binding = {
  device, button, gesture,           // what triggers it
  action:  record | event,           // record speech, or just report the gesture
  sink:    agent | app:<id> | ...,   // where the transcript goes (see Delivery)
  trust:   TRUSTED | PROJECT | UNTRUSTED,   // chosen by the user, see below
  language?, prompt?,                // STT hints per binding (e.g. liturgical vocabulary)
  mode:    hold | toggle             // push-to-talk or toggle
}
```

Examples: button 1 hold -> speech-to-text -> agent; button 2 hold -> speech-to-text -> Dictator; button 2 double-click -> event only (a host action such as "bookmark"). Bindings are stored host-side in settings (RFC-0036 scope, per device) and survive replacing a device. The **device only needs to know which gestures start a recording**: the host pushes that as the `bindings` config key (`[{button, gesture, mode}]`), and `rec_open` carries the `{button, gesture}` that started the recording. The host looks up the binding; **routing and trust are decided on the host, never by the device**, so a compromised device cannot choose a more privileged sink or higher trust than the user configured. The no-remote-start rule is unchanged: a binding tells the device *which physical gesture* records, it cannot start a recording.

#### Trust is configured by the user

A fixed UNTRUSTED rule would make hands-free operation impossible (RFC-0057): every agent instruction from a button would need a screen tap. So each binding carries the trust level the user chooses, and the transcript enters the system at exactly that level.

**Proposal [decision, needs an RFC-0027 amendment]:** add to the RFC-0027 origin table:

| Origin | Level |
|---|---|
| Transcript of audio from a paired, authenticated capture device | the `trust` of the binding that started the recording (TRUSTED, PROJECT or UNTRUSTED) |

Guardrails that keep this a deliberate choice and not a default:

1. **No silent default.** Creating a binding with an agent sink asks for the trust level; the setup screen explains the trade-off in one sentence (a TRUSTED binding means anyone who can make the device hear them can instruct the agent with your authority). The recommendation shown is the user's own to override.
2. **Ceiling per device.** At pairing approval the user sets the highest trust any binding on that device may use (default PROJECT [decision, open question 3]). Raising a binding above the ceiling, and raising the ceiling, happens only on a host screen. A device can never change its own binding trust, ceiling or sink.
3. **Authentication first.** Trust applies only to a connection that passed the per-connection signature check; before that, frames are dropped.
4. **Revocation and audit.** Revoking the device kills every binding at once. Audit records the binding id and the trust level applied to each capture (never content), so "what could this button do" is answerable afterwards.
5. **Everything else in RFC-0027 still applies.** TRUSTED input removes taint from the transcript itself; it does not remove capability checks, approvals for UNSAFE effects, or taint picked up later in the run from tool results. RFC-0057's limits on what may be approved by voice remain.
6. **Dictation sinks need no authority.** A binding whose sink is a document or Dictator field is data entry; its trust level only matters if that text later becomes agent input.

Per RFC process this RFC does not edit RFC-0027. The amendment text above is the proposal; it goes in a separate commit once approved.

### Delivery to sinks and per-app grants

- The Bridge produces a **capture record**: `{deviceId, rec, t0, duration, language, text, source: live|stored, trust: <binding trust>, binding}`. Audio is discarded after the transcript is committed unless the user sets otherwise; whether RFC-0056 has a retention class that can express "raw audio" must be checked in code before this is promised [unverified].
- **Sinks** are chosen per binding (above) in Engine settings (RFC-0036; a `devices.<deviceId>.*` namespace in user scope is proposed, to be checked against the settings registry). v1 sinks: (a) **an app**, delivered through the SDK, and (b) **an agent run** on the Engine's agent runtime. Different bindings of one device may use different sinks at the same time. An app only receives captures if the user granted it capture access for that device, an additive per-app grant alongside the D49 agent grant; apps with no grant see nothing.
- **Engine API v1 is frozen.** Delivery to apps uses new additive endpoints and an additive SDK interface (for example `GET /v1/captures?since=` plus a Binder callback, shape to be settled in phase 2). Nothing in `sdk/CONTRACT.md`'s frozen set changes; the tripwire tests stay green. Dictator is the first consumer (open question 2 asks whether the cube is a Dictator input device or an app Dictator reads from).
- **Locality (D47/RFC-0104).** BLE from the user's own device to their own host is local. Transcription goes through the same router as other STT: default `local_only`; a peer helper only if the user's limits allow it, exactly as for any audio sent to `/v1/audio/transcriptions`. The cube never causes audio to leave the user's devices by itself.
- **Audit** (RFC-0003/0046): pair, unpair, grant change, connect/disconnect, recording received (device id, rec id, duration, bytes, outcome), sync result, config change. Never audio or transcript content.

### Engine-side components

**Modularity requirement** [owner, rev. 2]: the capture code must be splittable into its own repository later. So: `capture-core` and the BLE/transport modules depend on nothing in the kernel, Engine or agent (like `mcp-core` and `mcp-policy`); the only module that touches Aidos is `capture-bridge`, behind small interfaces it defines (`CaptureSink`, `DevicePrincipalStore`, `TranscriberPort`, `AuditPort`, `BindingStore`). A dependency test fails the build if `capture-core` or a transport module imports a kernel or Engine package. Following the `mcp-core`/`mcp-policy` precedent:

| Module (proposed) | Contents | Platform |
|---|---|---|
| `capture-core` | CBOR codec use, message types, chunker/reassembler, frame parser, Opus/ADPCM decode interface, the session state machine (hello, auth, record, sync), test vectors. No BLE, no kernel. | Kotlin Multiplatform, CI-testable |
| `capture-bridge` | Pairing store (grants, bonded host keys), device registry, sink routing, STT hand-off, audit, taint labelling. Binds the kernel and settings. | Common |
| `capture-ble-android` | Companion Device Manager association, BLE scan/connect, GATT client, L2CAP channel, foreground-service integration in the combined host app (D49). | Android |
| `capture-ble-desktop` | Desktop BLE central. See below. | Desktop |

**Android.** Pair through the Companion Device Manager so the OS can wake the app on a device event [known]; request the Android 12+ Bluetooth runtime permissions [known]. The foreground-service type needed while receiving live audio is part of the open D49 item "FGS type needs a real device"; it is decided there, not here. Opus decoding uses a library to be chosen (a JVM/Android binding of libopus; license and build checked in phase 2), and the decoded PCM is wrapped as WAV and passed to the in-process STT, the same path a remote call takes.

**Desktop.** A Kotlin/JVM BLE central is poorly served by libraries [known], so the reference host is a **Node ESM tool** (the user's stack) using a maintained `noble` fork, speaking to the desktop Engine's loopback API like any other client. Which noble fork works on Windows, macOS and Linux (and whether Windows needs a different binding) is unverified and is the first desktop task [unverified]. How that tool authenticates to the Engine (per-app token as in `sdk/CONTRACT.md` versus the RFC-0052 socket token) is open question 5. Fallback if desktop BLE is a time sink: the USB CDC transport above, which the cube has natively.

**Test without hardware.** `capture-core` ships (1) byte-exact **test vectors** for every message type, chunking edge cases, frame headers, the auth transcript and a sync resume, and (2) a **virtual device**, a pure-software ACP peer (Node, so third parties can reuse it) that connects to the bridge over loopback and plays scripted scenarios: pair, record, drop mid-sync, resume, revoke, malicious inputs. CI runs these. Per the project rule, no test loads a real model; the STT hand-off is mocked, and real-model runs are Juha's (env-gated) as for the other modalities.

### Device configuration

`config_get`/`config_set` over Control, authorised by the `config` grant. Keys are a registry in this RFC (extended by amendment; vendors use `x-<vendor>-…`):

| Key | Type | Default | Notes |
|---|---|---|---|
| `name` | text | device class | Shown in the host UI. |
| `bindings` | list of `{button, gesture, mode}` | one `hold` per button | Which gestures record, pushed by the host from the user's bindings. Sink and trust are never sent to the device. |
| `codec` | 1, 2 or 3 | 1 | Applies to the next recording. |
| `bitrate` | uint (bps) | 24000 | Opus only; device clamps. |
| `preroll_ms` | uint | 400 | 0-1000. |
| `live_stream` | bool | true | If false, record and sync only. |
| `auto_sync` | `on_connect`, `manual` | `on_connect` | |
| `storage_policy` | `refuse_new`, `overwrite_oldest_synced` | `refuse_new` | See sync. |
| `display_refresh` | `live`, `static` | per device | e-paper variants pick `static`. |
| `haptics` | `off`, `low`, `normal` | `normal` | |
| `sleep_s` | uint | 20 | Display off timeout. |

**Locked keys.** Some behaviour is not configurable by design: the recording indicator, the no-remote-start rule, the requirement that `rec_ack` precedes deletion, and the local wipe gesture. A `config_set` naming one returns `locked_key`. The device reports the indicator state in the `indicator` event; the host cannot verify it, so this is a labelling aid, not an assurance (the hardware requirement lives in the cube plan §8.2).

Host-side desired config is stored per device in settings and re-applied on connect, so replacing a device or re-pairing does not lose it.

### Cards

Phone to cube display, "cards, not pixels" (plan §6): `{title, lines[] (max 6, UTF-8, device truncates), glyph (registry id), progress (0-100), haptic?, ttl_s, refresh (live|static)}`. The device chooses layout and font. The `refresh` hint lets an e-paper device update a persistent "last transcript" card with partial refresh and skip per-word redraws. Cards require the `cards` grant. A card is display text only: no URLs are followed, nothing on a card can trigger an action on the host.

The bridge sends: transcript ready, recording received, sync progress, pairing code, low storage on the host. Card content is transcript text, which is special-category data under GDPR when it reveals belief or practice, and may contain pastoral matter, so a per-device setting `cards.show_transcript` (default off) governs whether transcript text reaches the display at all (see Privacy).

### Versioning and openness

- Protocol major version in every message (`v`) and in ACP Info. Hosts and devices pick the highest common major version from `versions[]` in `hello`; no common version is a clean disconnect with `unsupported`. Minor changes are additive only (new optional keys, new types, new registry entries).
- **The wire format is open.** [decision] The protocol text, UUIDs, registries and test vectors will be extracted from this RFC to `docs/protocol/acp-v1.md` and a `test-vectors/` folder when the RFC is Accepted, and released under permissive terms (CC0 for the specification and vectors; the reference code stays under the repository's EUPL-1.2). Anyone may build and sell a conformant device without a licence from Aidos. Open: whether to register the UUID base with anyone; a random UUIDv4 needs no registration.
- A **conformance checklist** (pairing requirements, locked behaviours, MTU floor, ack-before-delete, no remote start) and the virtual device give third parties a way to claim "ACP v1 compatible".

## Security considerations

| Threat | Control |
|---|---|
| Rogue peripheral in range tries to become a capture source | LESC bonding (no Just Works), host-side explicit approval, per-connection signature check; audio dropped until authenticated. |
| Cloned or replayed bond | Challenge-response with a fresh 32-byte nonce bound to the host fingerprint. |
| Stolen paired device used to inject commands | Revocation takes effect on the host immediately; trust is capped by the per-device ceiling and revoked with the device; capabilities and UNSAFE-effect approvals still apply (RFC-0027). |
| Someone near the user speaks to the device, or replays audio | Residual risk accepted by the user when they set a TRUSTED binding; the ceiling, per-binding trust, hold-to-talk gesture and audit limit it. Prefer PROJECT/UNTRUSTED bindings for devices used in shared rooms. |
| Malformed audio exploits the decoder | Decode in a bounded context; frame size and rate limits (max 100 frames/s, max frame 1275 bytes for Opus, max recording length 2 h by default [decision]); fuzz the parser and decoder wrapper in CI. |
| CBOR resource exhaustion | 4096-byte message cap, map/array depth and element limits, reject indefinite-length items. |
| Sync flooding the disk | Per-device storage quota on the host, stop at quota and tell the user. |
| Host used to record people covertly | The host cannot start the mic; the indicator is device-enforced (cube plan §8.2). |
| Lost cube leaks recordings | Device-side encryption at rest and one-gesture wipe (cube plan §8); the host can request `wipe`, but it needs on-device confirmation. |

Rejected frames and failed authentications are audited with counts, not content.

## Privacy

Spoken confession and counselling are pastoral confidentiality, and religious belief is special-category data under GDPR. Requirements for the receiving side: default `local_only`; raw audio not retained after the transcript is committed unless the user opts in; transcripts follow the retention classes of the sink; the Engine UI shows the capture history per device and can delete it; the user guide marks "do not record" situations. Before any member-facing deployment, run the design past the church-data-protection advisor skill. These are requirements on the Engine; the cube's own are in the cube plan §8.

## Alternatives considered

- **Use only Omi's service.** Simplest, but no identity, no storage sync, no config, no cards. Deferred, see above.
- **A custom binary framing for control instead of CBOR.** Smaller, but every extension needs a spec edit and third-party debugging is harder. CBOR is self-describing, has mature libraries on ESP-IDF, Kotlin and Node [known], and the messages are tiny.
- **LE Audio (LC3, isochronous channels).** Better power and standard, but the Espressif stack has none [known] and Android support is patchy; revisit if the Nordic variant is built.
- **Classic Bluetooth (HFP/A2DP).** Phone-microphone profiles; wrong shape for a source device with storage.
- **Phone as BLE peripheral.** Reverses roles and kills background reconnect; rejected.

## Phases

0. **This RFC plus `capture-core`** with test vectors and the virtual device. Pure Kotlin/Node, runs in CI, no radio. Exit: vectors green, an independent reader can implement a device from the text.
1. **Desktop reference host** (Node ESM) against the virtual device, then against the cube's USB CDC transport, then BLE once the noble choice is verified. Exit: Juha holds button 1, speech reaches the agent; holds button 2, text appears in Dictator on the laptop. Pairs with the cube's own Phase 1.
2. **Android bridge** in the combined host app: CDM pairing, passkey flow, live Opus, sync, additive delivery endpoint, Dictator integration. Needs a real device; carries the same risk as other never-run Android code (project memory).
3. **Binding editor UI, cards, capture history UI.**
4. **Wi-Fi transport** as an RFC-0105 device principal (after RFC-0107/0108 settle).

## Open questions for Juha

1. **Name.** ACP is a working name carried over from the cube plan; "Aidos Capture Protocol" or something button-specific? It will be in third parties' docs.
2. **Dictator.** Is the cube a Dictator input device (Dictator receives captures and owns the UX), or its own memory app that Dictator reads from? Changes who holds the per-app capture grant first.
3. **Trust ceiling.** I proposed a per-device ceiling (default PROJECT) so TRUSTED needs one deliberate host-screen step per device. Is that the right default, or should new devices default to TRUSTED-capable since you pair them yourself?
4. **Openness terms.** CC0 for the spec and vectors, EUPL-1.2 for reference code: agreed?
5. **Desktop authorisation.** The Node bridge as a normal per-app client with a token, or a dedicated local socket under RFC-0052?
6. **Hands-free confirmation.** Should a binding be able to require a spoken or haptic confirmation (not a screen tap) before an effectful agent action, within RFC-0057's limits?
7. **Transcript on the display.** `cards.show_transcript` default off protects pastoral material but removes the main use of the e-paper card. Default on for a personal device with a lock gesture instead?

## Required follow-ups outside this RFC

- Dependency test enforcing the modularity rule above.
- RFC-0027 amendment (origin rows) after approval.
- RFC-0036: `devices.*` setting namespace.
- `sdk/CONTRACT.md`: additive captures endpoint, outside the frozen set.
- D49: foreground-service type for connected-device audio.
- `docs/rfcs/STATUS.md`: row added with this RFC.
- A decisions entry (D54) if Juha accepts the open-spec and trust proposals.
