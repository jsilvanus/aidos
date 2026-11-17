# AI cube: ESP32 hardware and protocol plan

Status: proposal, 2026-10-26; brought in line with Aidos as of 2026-11-17 (section 0a). Post-MVP work,
like RFC-0109. Not decided by Juha
beyond section 0. Nothing here has been built or measured.
Companion files: existing-products survey (`docs/plans/ai-button-existing-products.md`), shopping list
(`docs/plans/ai-cube-prototype-gear.md`).
Related: **RFC-0109 Capture Device Protocol (draft)**, which now owns the protocol and the Engine
receiving side; RFC-0117 (speech), RFC-0128 (hardware acceleration, draft), `docs/threat-model.md`,
`docs/stt.md`, `sdk/CONTRACT.md` (Engine API v1, additive-only since D63), D47 in `docs/decisions.md`.

**Who owns what.** This file is the **hardware and firmware** plan. The wire protocol, pairing, trust,
bindings and the host bridge are specified in RFC-0109. Where the two disagree, RFC-0109 wins and this
file is the bug. Section 6 below is kept as a summary only.

Naming: "Aidos Engine" is the model and agent provider on desktop and Android. The cube talks to
an Engine host; it does not run an Engine.

Evidence labels used below: **[sourced]** = found in a web source linked at the end, **[known]** =
my background knowledge of the parts, not re-checked today, **[estimate]** = my arithmetic or guess,
to be replaced by a measurement in Phase 0. Nothing here has been built or measured.

## 0. Decisions from Juha (2026-10-26)

- Main job: the cube is a **source into the Aidos running on the phone** (companion option A). Dictation
  and memory capture both flow phone-ward; the cube is not a standalone assistant in v1.
- Offline store-and-forward is a **first-class role**, not a fallback: the cube must record with no host
  present and sync automatically when one becomes available. Requirements: recordings are durable
  (written to storage before any BLE send, never streamed-only); sync is resumable (`rec_get` with
  offset) and idempotent (content hash + id); delete only after host ack; storage-full behaviour is
  explicit (warn on display and haptic at 80%, refuse new recordings rather than overwrite unsynced
  ones unless the user sets oldest-synced-first); auto-sync triggers on BLE reconnect and, if
  configured, on known Wi-Fi. Because this makes storage the main feature, **microSD or eMMC is in the
  v1 BOM, not just in the prototype**, and recording timestamps carry a time-quality flag until the
  first phone time sync.
- Display: **explore both e-paper and colour LCD.** Plan: build the Phase 1 breadboard with an SPI display
  socket and the same card renderer, and swap 1.54" 240x240 IPS LCD and 1.54" 200x200 e-paper on the
  same firmware. LCD shows live dictation; e-paper shows a persistent memory/status card (last
  transcript, battery, storage, pairing state). The card protocol (section 6) gets a `refresh` hint
  (`live` | `static`) so the cube can pick partial refresh on e-paper and avoid redrawing per word.
  Open decision after Phase 1: pick one, or ship a variant of each.
- Speaker: **deferred to v2.** v1 uses haptics and display only; drop the amp and speaker from the v1 BOM
  and keep the I2S pins free on the custom board.

## 0a. What changed in Aidos since this plan was written (2026-11-16)

Each line says what is true in the repository now and what it changes for the cube.

1. **RFC-0109 exists (draft, three owner revisions on 2026-11-10).** It replaces section 6 of this plan.
   Differences that matter for hardware and firmware:
   - The device declares **inputs** (buttons, motion, touch with position, rotary, sliders) in `hello`,
     and the user binds each `(input, event)` to a sink on the host. So the fidget faces are not
     firmware features with fixed meanings: firmware only reports events, the phone decides what they do.
     This makes "modular fidget mechanics" (section 4) cheap: a new face is a new input entry.
   - **Default trust is TRUSTED** per binding (owner, rev. 3), because untrusted input would defeat
     hands-free use. This raises the stakes of pairing and of the device key: a paired cube can start
     agent actions by voice. See the threat rows in section 8a.
   - **Omi is deferred and may be removed.** The Omi-compatible audio leg is optional, not the basis.
     Do not buy an Omi DevKit as a reference unless that changes.
   - Transports: BLE first, **USB CDC** as the development and wired transport, Wi-Fi/TLS later. USB CDC
     comes free with the S3's native USB and lets Phase 1 run with no radio work at all.
   - Codec registry: 1 = Opus 16 kHz mono 20 ms (default), 2 = IMA ADPCM, 3 = PCM16. The firmware plan
     below already matches. MTU floor 185 bytes.
   - Device identity is **ECDSA P-256**, generated on the device at first boot, never exported. The S3
     has hardware support through mbedtls; use the DS peripheral or eFuse-protected key storage with
     flash encryption and secure boot v2.
   - **The host can never start the microphone.** No message for it exists. Firmware must not add one.
   - Limits the firmware must respect: control message at most 4096 bytes, at most 100 audio frames per
     second, Opus frame at most 1275 bytes, recording length 2 h by default.
2. **D49 is settled.** Android runs Engine and Agent in one host app with one foreground service of type
   `specialUse` (package `fi.italeino.aidos.engine`). The "open D49 item" in section 6 is closed; what
   remains is whether connected-device audio needs an added `connectedDevice` type, listed as a
   follow-up in RFC-0109.
3. **Speech-to-text is real on both hosts.** `POST /v1/audio/transcriptions`, Whisper on ONNX Runtime.
   Measured on desktop CPU (RFC-0117): tiny is unusable for Finnish (CER 0.14), **small is usable (CER
   0.04, 250 MB)**, large-v3-turbo is exact but 1.1 GB. Fine-tuned models from `liturgos-auditor` load
   after an ONNX export. The route takes a base64 WAV in JSON or, since 2026-11-16, an OpenAI-style multipart upload of the
   WAV bytes, which is the better fit for the bridge (no base64 copy).
   Audio over 30 s is cut into windows with no timestamps, so **the cube should cut recordings into
   utterance-sized pieces or the bridge must**, and the bridge transcodes Opus or ADPCM to 16 kHz WAV.
4. **Silero VAD is in the Engine** (v5, about 2 MB, same ONNX Runtime). With it installed, the Engine
   finds speech before Whisper runs. Consequences: the cube does not need its own VAD for accuracy; a
   long toggle-recording with silences is handled on the host; an on-cube energy gate is still worth
   having only to save storage and BLE airtime (Phase 3, optional).
5. **Text-to-speech is built** (RFC-0117 amendment, `engine/tts`, `docs/tts.md`, merged 2026-11-17): Piper
   `fi_FI-harri-medium` for Finnish with a pure-Kotlin phonemizer, Piper `en_US-ljspeech-high` for
   English, `POST /v1/audio/speech` returning 22.05 kHz 16-bit WAV, no streaming. For the cube's v2
   speaker this means: replies arrive as whole short clips, the host resamples and encodes to Opus, and
   the cube only needs a decoder and the reserved ACP message types. Nothing to do in v1 except keep the
   I2S pins free, as already decided.
6. **Hardware acceleration is RFC-0128 (draft).** It concerns the Engine host (desktop GPU, phone GPU
   and NPU), not the cube. Effect on the cube: Whisper small on a phone CPU is the slow step of the
   whole chain, so live dictation latency in Phase 2 depends on 0128 landing or on a short utterance
   length. Measure end-to-end latency on the reference phone (OnePlus 8) before promising "live" text
   on the display; until then treat the LCD's live view as near-live (text after each pause).
7. **The threat model exists** (`docs/threat-model.md`) and has no capture-device rows yet, because
   RFC-0109 is unbuilt. Section 8a below lists the rows the cube adds. CLAUDE.md now requires the
   threat model to be updated in the same PR as any new listener, credential or trust boundary.
8. **Engine API v1 is frozen additive-only (D63).** The cube never calls the Engine API directly in
   v1; the bridge does. The delivery endpoint for captures is an additive endpoint outside the frozen
   set (RFC-0109 follow-up). In option B (standalone over Wi-Fi) the cube would become an API client,
   and then per-device tokens, the loopback-only admin token and LAN pairing (RFC-0126) apply.
9. **Desktop is now always-running Engine and Agent services with a tray (RFC-0115).** The Phase 1
   desktop rig talks to that service on loopback with a normal per-app token; it must not use the
   admin token file.

## 1. The short answer

- **Build it as a companion first.** The cube is a capture device with a small display and haptics.
  Speech-to-text, the agent and all memory live on the phone (Aidos Engine host on Android) or a
  desktop engine. This matches how the Engine already works: Whisper ONNX for STT, Finnish needing
  whisper-small or larger (project memory).
- **Use an ESP32-S3 for the prototype.** It has Wi-Fi and BLE 5, dual core with vector instructions,
  octal PSRAM, native USB, I2S/PDM audio, and a large ecosystem with ready dev boards. Its weak spot
  is BLE standby power and no LE Audio (LC3) in the Espressif stack [known]; neither matters for
  push-to-talk.
- **Push-to-talk removes the hard part.** With a button as the wake source there is no always-on
  wake-word listening, which is where battery life normally dies. The S3 sleeps until the button is
  pressed.
- **Running Whisper on the cube is not realistic.** ESP-SR on the S3 does wake words and a fixed
  command list (MultiNet, up to 300 commands) [sourced], not open dictation. Whisper-tiny is about
  39M parameters [known], far beyond 8 MB PSRAM at usable speed [estimate]. Plan for no on-device
  STT beyond optional "yes/no/next" style commands.
- **"The cube does Aidos stuff itself" means the cube is an AIDIN client,** not a smaller Engine.
  Same protocol over Wi-Fi instead of BLE (section 6). Design the protocol transport-neutral and both
  of Juha's options fall out of one design.
- **Privacy is a design input, not an afterthought.** In church use the cube may be near pastoral
  conversations. Section 8 sets hard rules (recording indicator, encrypted storage, no always-on
  capture, delete on device).

Assumed form factor: about 50 x 50 x 30 mm, display on a 50 x 50 face. 30 mm is generous; the stack
(display + PCB + battery) is roughly 15 mm [estimate], the rest houses fidget mechanisms and the
speaker chamber. If Juha meant the display on a 50 x 30 face, the BOM does not change, only the
display choice.

## 2. Architecture options

| Option | What the cube does | Where compute runs | Verdict |
|---|---|---|---|
| A. Companion | Record, buffer, show cards, haptics | Phone Engine host (or desktop engine) | **Build first.** Works offline (records to storage), syncs later. |
| B. Standalone client | Same, plus Wi-Fi to an AIDIN anchor or desktop engine | Anchor node | Phase 4. Needs device identity and TLS on the cube; wider placement under D47, so explicit opt-in. |
| C. On-device STT | Runs a speech model | Cube | Rejected for open dictation; only keyword/command spotting. |

A and B share one application protocol (section 6); only the transport differs (BLE vs Wi-Fi/TLS).

## 3. SoC choice

| Chip | For | Against | Verdict |
|---|---|---|---|
| **ESP32-S3** (N16R8: 16 MB flash, 8 MB octal PSRAM) | Wi-Fi + BLE, PSRAM, vector instructions for Opus/ADPCM, native USB, PDM/I2S, huge dev-board choice, ESP-SR available [sourced] | Higher sleep and BLE power than Nordic; no LC3 | **Prototype and v1.** |
| ESP32-C6 | Cheaper, Wi-Fi 6, BLE 5, Thread | Single 160 MHz RISC-V, no PSRAM, tight for display + audio | Only for a screenless variant. |
| ESP32-P4 (+ C6 radio) | Strong CPU, MIPI display | No radio on chip, overkill, more power | No. |
| nRF54L15 / nRF5340 | Much better BLE power, LE Audio capable on Nordic stack [sourced, vendor table, check] | No Wi-Fi, weaker for display UI, different toolchain (Zephyr) | Revisit in Phase 4 if battery life disappoints. |

Firmware: ESP-IDF 5.x in C/C++ with the NimBLE host (lighter than Bluedroid) and LVGL for the UI.
MicroPython is fine for a first-day button/LED test but not for audio pipelines.

## 4. Subsystems

**Audio in.** One I2S MEMS mic is enough for push-to-talk (ICS-43434 or INMP441 class [known]); the S3
also supports PDM mics [known]. Two mics only matter for beamforming/AEC, skip for now. Sample 16 kHz
mono. Codec/ADC pairs (ES8311 + ES7210) appear on S3 audio boards [sourced] and are the route if
the cube later needs echo cancellation while playing TTS.

**Audio out (optional).** Small 8 ohm speaker with an I2S class-D amp (MAX98357A class) for beeps and
short spoken confirmations. Do not plan long TTS on the cube; TTS runs on the host and streams down.
Haptics (DRV2605L + LRA) is the better confirmation channel for a fidget device and costs less
power than the speaker [known].

**Display.** Candidates on a 50 x 50 face:
- 1.54" 240x240 IPS (ST7789, SPI): square, cheap, colour. Default.
- 1.28" round 240x240 (GC9A01): matches the "button/orb" look; smaller usable area.
- 1.54" e-paper 200x200: near-zero power for a persistent "memory card" view, slow refresh (about 1-2 s
  full, more ghosting), no good for live dictation text. Candidate for a second variant.
Backlight dominates display power; auto-off after a few seconds, wake on button/IMU.

**Controls (the fidget part).** Main push-to-talk: large tactile click button. Extras that map to
UI: rotary encoder or thumb wheel (scroll recordings/cards), a slider or second click (mark/bookmark
moment), IMU (LIS2DW12 or BMI270 [known]) for tap, shake and which-face-is-up as a mode switch. Keep
mechanics modular so the fidget faces can change without new PCBs.

**Storage and capacity** (16 kHz mono):

| Encoding | Rate | Per hour | 8 MB flash | 32 GB microSD |
|---|---|---|---|---|
| Raw PCM16 | 256 kbps | 115 MB | 4 min | about 280 h |
| IMA ADPCM | 64 kbps | 29 MB | 17 min | about 1100 h |
| Opus voice | 16-24 kbps | 7-11 MB | 45-70 min | about 3000+ h |
(All arithmetic [estimate].) Plan: Phase 1 stores ADPCM or WAV (trivial CPU), Phase 2 moves to Opus
once an S3 Opus encoder is measured for CPU and current draw [unverified; ports exist, performance
not checked]. Medium: microSD for the prototype (easy to pull and inspect). For the product, soldered
eMMC or SPI NAND (a 1 Gbit NAND holds about 17 h at 16 kbps [estimate]) is smaller and more robust.
Files: one Ogg/Opus (or WAV) file per press, sidecar JSON with start time, duration, battery, firmware
version, and a content hash.

**Time.** Internal RTC drifts; sync from the phone on every connect and store a "time quality" flag
per recording. Optional external RTC (PCF8563/RV-3028 class [known]) if timestamps matter offline.

**Power and USB-C.** Single-cell LiPo with a charger IC (BQ25185 or MCP73831 class [known]), a fuel
gauge (MAX17048 [known]), USB-C with 5.1k CC resistors, native S3 USB for flashing, debug and
mass-storage of recordings. Battery fits the footprint at 603450 (about 6 x 34 x 50 mm, about
1000 mAh [known]) under the PCB.

**Order-of-magnitude budget** (all [estimate], to be measured in Phase 0): deep sleep with the board
leakage about 0.1-0.3 mA, so a 1000 mAh cell gives weeks to months of standby; recording with the
screen off about 60-90 mA, so 10+ hours of continuous capture; screen and BLE streaming about 120-180
mA, so several hours of live dictation. Typical use (a few minutes of speech per day) is dominated by
standby leakage, so choose regulators and the gauge/IMU for quiescent current.

## 5. Prototype BOM

**Step 0, fastest path: buy a dev board with mic, speaker, display, LiPo charger already on it.**
Found, with specs from the vendor pages; verify the exact mic type and codec on the schematic
before buying:
- DFRobot DFR1221: ESP32-S3, 1.85" round 360x360 touch, I2S digital mic and I2S DAC, Qi charging [sourced].
- Waveshare 1.85" round ESP32-S3 board: LiPo connector with charge circuit [sourced; audio hardware not confirmed].
- Waveshare ESP32-S3-AUDIO-Board: ES8311 codec, ES7210 mic interface, dual digital mic array, LCD/camera connectors [sourced]. Not cube-shaped but the best audio reference.
These are for firmware and STT-quality work, not the final shape.

**Step 0b, M5Stack route (from the survey).** An M5Stack AtomS3R with Atomic Echo Base style kit is
ESP32-S3 with a small display, mic and speaker in a tiny case [sourced via the survey; verify the
exact kit and codec]. It is the quickest way to run Phase 0/1 firmware and the Omi-compatible BLE
audio service on something that already looks like a small device. No SD slot, so recordings go to
internal flash (about an hour at Opus 16 kbps, table in section 4), enough for the sync tests.

**Step 1, breadboard that matches the cube.**

| Item | Part (class) | Note |
|---|---|---|
| MCU | ESP32-S3-DevKitC-1 N16R8 | USB native, PSRAM |
| Mic | ICS-43434 or INMP441 breakout | I2S |
| Display | 1.54" 240x240 ST7789 SPI | |
| Storage | microSD breakout (SDMMC 1-bit) | |
| Buttons | Large tactile/arcade switch, EC11 encoder | |
| IMU | LIS2DW12 or BMI270 breakout | |
| Haptics | DRV2605L + LRA | |
| Speaker | MAX98357A + 8 ohm 1511 speaker | optional |
| Power | 603450 LiPo (about 1000 mAh) + BQ25185/MCP73831 board + MAX17048 | |
| Enclosure | 3D-printed 50x50x30 shell | |
Rough cost of Step 1 parts: tens of euros, not hundreds [estimate]; not itemised because I did
not check prices.

**Step 2, custom board.** ESP32-S3-WROOM-1 N16R8 module (about 18 x 25.5 mm [known]) on a 46 x 46 mm
4-layer PCB, all of the above on one board, display on the top face, battery below. Defer until
Phase 3 results are in.

## 6. Cube <-> Aidos protocol ("ACP", working name)

Goal: transport-neutral so BLE (companion) and Wi-Fi/TLS (standalone, AIDIN) carry the same
messages. Control messages are small CBOR maps with a version field, mirroring the version
negotiation already in the Engine API (aidos#82). **Superseded by RFC-0109** for every detail in this
section: read the RFC for UUIDs, message types, framing, pairing and bindings. The text below is the
original sketch, corrected where it is now wrong. Audio is Opus frames (20 ms) with sequence
numbers and timestamps, never JSON.

**Omi compatibility (from the products survey).** Omi is MIT-licensed with a documented BLE audio
service (audio service `19B10000-E8F2-537E-4F6C-D104768A1214`, data characteristic `...0001`, codec
`...0002`; packets are a 2-byte counter + 1-byte chunk index + payload; Opus 16 kHz default) [sourced
via the survey; check the Omi protocol page before coding]. Status 2026-11-16: **Omi is deferred and may be
removed (RFC-0109 rev. 2)**; ACP has its own audio characteristic and framing, and the Omi service is
an optional compatibility leg at most. Original proposal, no longer the plan: the **audio leg of ACP
is a strict superset of Omi's service**. The cube exposes Omi's audio service and framing unchanged, and
adds a separate Aidos service for control, storage sync and cards. Result: one Aidos BLE client in the
host app serves Omi, the custom cube, and an M5Stack prototype; a Flic-triggered phone is a fourth
source with no BLE audio. Cost: we inherit Omi's framing limits (no per-packet timestamp), so the
cube adds a timestamp in the Aidos control channel per recording instead.

**BLE transport (Phase 1-2).**
- Omi-compatible audio service as above, plus one custom Aidos GATT service (128-bit UUIDs).
- Control characteristic: write + indicate. Messages: `hello{version, fw, caps}`, `time_set`,
  `rec_list`, `rec_get{id, offset}`, `rec_ack`, `rec_delete`, `config`, `status{battery, storage}`.
- Live audio: L2CAP connection-oriented channel (Android 10+ API [known]) for the Opus stream, falling
  back to notifications on an audio characteristic. 16-24 kbps is far below BLE 2M PHY throughput.
- Display/haptics channel, phone to cube: **cards**, not pixels. A card is `{title, lines[], glyph,
  progress, haptic, ttl}`; the cube renders it. Keeps the cube dumb and the protocol tiny.
- Bulk sync of stored recordings over the same channel: one hour of Opus at 16 kbps is about 7 MB,
  which takes minutes at realistic phone BLE throughput [estimate]. Optional fast path: cube joins
  Wi-Fi and uploads over HTTPS when home/office Wi-Fi is known.

**Pairing and trust.**
- BLE LE Secure Connections. The cube has a display and a button, so use passkey entry: cube shows a
  6-digit code, the host app asks for it. No "just works" pairing.
- On top of BLE bonding, the host treats the cube as a **device principal** with its own revocable
  token, approved in the Engine UI like an app today (per-app bearer tokens in `sdk/CONTRACT.md`).
  Device key pair generated on the cube; this is also the identity the cube later presents to AIDIN
  (mTLS), so Phase 4 does not need re-pairing.
- Android: pair through the Companion Device Manager so the OS can keep the connection and wake the
  app [known]. The host service needs the right foreground-service types (connectedDevice,
  likely microphone-adjacent handling). D49 is settled (one FGS, `specialUse`); whether a
  `connectedDevice` type must be added for the bridge is a listed follow-up of RFC-0109.

**Host side.**
- Android: a "cube bridge" module in the combined host app (D49). It receives audio, calls the
  Engine's STT in-process, returns a transcript card, and hands text to the Agent or to Dictator
  through the SDK.
- Desktop: a small Node ESM tool that connects, pulls recordings or live audio, and POSTs to the
  loopback Engine STT endpoint with a normal per-app token. **Start over USB CDC** (Node `serialport`),
  which RFC-0109 defines as a transport and which needs no BLE stack; add BLE (`@abandonware/noble` or
  equivalent [known], choice to be verified on Windows, where Juha's desktop runs) afterwards. This is
  the Phase 1 test rig and needs no phone. The tool runs on the same machine as the Engine service
  (RFC-0115), so loopback is enough and LAN sharing (RFC-0126) is not involved.
- Dictator: becomes one consumer of cube text. Decide with Juha whether the cube is a Dictator input
  device or a separate "memory" app that Dictator reads from (open question 2).

**Locality (D47).** BLE to the user's own phone is local. Wi-Fi to a desktop or anchor counts as wider
placement and needs an explicit user setting, off by default.

## 7. Phases

Each phase ends with something Juha can hold and judge.

Mapping to RFC-0109's phases: its Phase 0 (protocol core, test vectors, a software virtual device, all
in CI with no radio) has no hardware in it and can run before or beside the cube's Phase 0. Its Phase
1 (desktop reference host) pairs with Phase 1 here, its Phase 2 (Android bridge) with Phase 2 here.

**Phase 0, feasibility on a dev board (days).** Flash a dev board, record 16 kHz mic audio to
microSD, press-to-record, show a recording indicator. Measure: audio quality at arm's length and 1 m
in a nave-like room, Opus encode CPU and current, deep-sleep current, boot-to-recording latency from a
button press (first syllable must not be lost: keep a rolling 300-500 ms pre-roll buffer).
Exit: Finnish speech recorded this way transcribes acceptably on the desktop Engine with **Whisper
small** and Silero VAD installed (tiny is known to fail Finnish, so do not judge the microphone with
it); compare against a phone recording of the same lines, and against a `liturgos-auditor` fine-tuned
model if one is exported. Add to the measurements: ECDSA P-256 sign time on the S3, and whether flash
encryption plus secure boot leaves the USB mass-storage and debug workflow usable.

**Phase 1, recorder plus desktop sync (1-2 weeks).** Real firmware: PTT, SD files, sidecar metadata,
ACP control and file transfer **over USB CDC first, then BLE**, tested against RFC-0109's test vectors
and virtual host; Node ESM tool that syncs and sends recordings to the loopback Engine STT.
Add the corpus test: 30-50 Finnish lines (liturgical, plus ordinary dictation) for word error rate.
Exit: Juha dictates into the cube, text appears on the laptop. Never run against a real loaded model
in CI (project rule); Juha runs it.

**Phase 2, Android companion (needs Juha's device).** Cube bridge in the host app, CDM pairing,
passkey flow, live Opus streaming, transcript cards on the display, Dictator integration.
Exit: cube + phone dictation works with the screen off. This leg depends on Android code that has
never been run on a device (project memory), so it carries the same risk as the Binder leg.

**Phase 3, fidget UX and enclosure.** Encoder, haptics, IMU face-up modes, 3D-printed shell, battery
life measurement against the section 4 estimates. Decide whether the speaker survives.

**Phase 4, standalone/AIDIN and custom PCB.** Wi-Fi transport, device identity into AIDIN (only after
the AIDIN draft RFC is accepted), optional nRF variant if standby power is poor, custom 4-layer PCB.

**Omi DevKit 2 as BLE reference (from the products survey, `docs/plans/omi-products.md`). On hold since
Omi was deferred in RFC-0109; kept for the record.** DevKit 2 has a
speaker, haptic motor, programmable button (long press records) and 8 GB onboard storage, but BLE
control of speaker and haptics is not exposed yet (needs an MIT-licensed firmware patch) [sourced via
the survey]. Suggestion: buy one as the reference for the Omi-compatible audio leg and for host-side
testing; display, cards and feedback live only on the custom cube.

## 8. Privacy rules (hard requirements)

1. No always-on capture. Recording only while the button is held or after a toggle with a clearly
   visible state.
2. A recording indicator that cannot be disabled in software (LED on the same GPIO net as the mic
   enable if possible, otherwise firmware-enforced and tested).
3. Recordings encrypted at rest (ESP32-S3 flash encryption plus per-file keys wrapped by a device key
   exchanged at pairing); a lost cube must not leak a confession or counselling session.
4. Delete from the cube on host acknowledgement, and a one-gesture "wipe now".
5. No audio leaves the user's own devices without an explicit setting (D47 default local_only).
6. Church context: religious belief is special-category data under GDPR; pastoral confidentiality
   applies to spoken confession and counselling. Mark "do not record" situations in the user guide.
   Use the church-data-protection advisor skill before any member-facing deployment.

## 8a. Threats the cube adds (to go into `docs/threat-model.md` when RFC-0109 is built)

The cube is a new trust boundary: a radio peripheral whose input is TRUSTED by default and can start
agent actions. Rows to add, with the control that this plan or RFC-0109 already names:

| Attacker | Path | Control | Residual risk |
|---|---|---|---|
| Someone nearby with a radio | Pairs a fake device, or connects to the cube | LE Secure Connections with passkey, never Just Works; approval on the host screen **and** a button press on the cube; device key checked on every connect | A user who approves a stranger's pairing request |
| Someone nearby | Replays or injects audio or input events on the link | Bonded encrypted link; challenge signed by the P-256 device key per session; sequence numbers | Radio-level denial of service |
| Thief or finder of the cube | Reads stored recordings | Flash encryption, per-file keys wrapped by a key that needs the host; secure boot so firmware cannot be swapped to dump keys; "wipe now" gesture | Recordings made since the last sync are lost with the device; a determined lab attack on the chip |
| Thief or finder | Uses the still-paired cube to speak commands to the owner's agent while in range | Revoke the device principal on the host; optional per-binding confirmation (RFC-0109 open question 6); bindings to effectful sinks should default to a confirmation | Until revoked, the cube is a voice key to TRUSTED input |
| Another person speaking near the cube | Their speech becomes TRUSTED input (spoken prompt injection) | Push-to-talk only, no always-on capture; the agent's approval gates for effects still apply | The holder pressing the button while someone else talks |
| Malicious firmware update or USB host | Replaces firmware, reads flash over USB | Secure boot v2, signed OTA only, USB mass storage exposes only encrypted or already-synced files, JTAG disabled by eFuse in product builds | Development units are open by design: never use one for real pastoral material |
| Malformed audio from a hostile "device" | Exploits the decoder in the Engine | RFC-0109 limits and fuzzing; decode in a bounded context | Decoder bugs |
| Bystanders | Are recorded without knowing | Recording indicator tied to mic power (rule 2), audible or haptic start cue | Social, not technical |

Rule changes that follow: (1) rule 3 above needs secure boot and disabled JTAG to mean anything, so
they are part of the v1 product build, not optional hardening; (2) because input is TRUSTED, a lost
cube is a security event, not only a privacy one, and the Engine UI needs a visible "revoke device".

## 9. Open questions for Juha

1. Primary job: dictation into Dictator, or an ambient memory of the day, or both? (Changes whether
   live streaming or batch sync is the main path.)
2. Is the cube a Dictator input device, or its own app that Dictator reads from?
3. Do you want the speaker (spoken replies) in v1, or haptics and display only?
4. Is the display on the 50 x 50 face, and do you want colour LCD or e-paper?
5. Prototype hardware: are you happy to order dev boards now (Phase 0), and can you test Android in
   Phase 2?
6. Any hard battery target (for example a week of normal use)?

Answered since 2026-10-26: question 1 and the first half of 4 by section 0 (source into the phone,
offline first; explore both displays); question 3 (speaker deferred to v2).

New, from the 2026-11-16 alignment:

7. ~~May RFC-0109's Phase 0 start now?~~ Answered by Juha 2026-11-17: **no, RFC-0109 stays post-MVP,
   Phase 0 included.** So the cube's own Phase 0 (dev board, recording, measurements) is the only part
   that can move before MVP, and it needs no Aidos code.
8. Omi: deferred or removed? It decides whether the firmware carries the second GATT service at all.
9. Bindings to effectful agent actions from a TRUSTED cube: require a confirmation on the device
   (second press or a haptic prompt) by default? (RFC-0109 open question 6, restated because the
   threat table makes it the main residual risk.)
10. Transcript on the display defaults to off in RFC-0109 to protect pastoral material, which removes
    the main use of the e-paper card. Default on with a lock gesture for a personal device?

## Sources

- [DFRobot DFR1221 1.85" round ESP32-S3 board wiki](https://wiki.dfrobot.com/SKU_DFR1221_ESP32_S3_1_85inch_Round_Display_Development_Board)
- [Waveshare ESP32-S3-AUDIO-Board](https://www.waveshare.com/product/esp32-related/esp32-s3-audio-board.htm)
- [CNX Software: ESP32-S3-AUDIO-Board](https://www.cnx-software.com/?p=156657)
- [CNX Software: ESP-SR on ESP32 and ESP32-S3](https://cnx-software.com/?p=116545)
- [Espressif ESP-SR docs](https://docs.espressif.com/projects/esp-sr/en/latest/esp32s3/flash_model/README.html)
- [Omi repo](https://github.com/BasedHardware/omi) and [Omi BLE protocol](https://docs.omi.me/doc/developer/Protocol)
- [M5Stack ATOMS3R kits (Mouser)](https://www.mouser.com/en/new/m5stack/m5stack-atoms3r-kits/)
- [Nordic nRF5340 Audio DK](https://www.nordicsemi.com/Products/Development-hardware/nRF5340-Audio-DK/)
- [Ezurio: nRF54 vs nRF53 vs nRF52](https://www.ezurio.com/resources/blog/nordic-nrf54-vs-nrf53-vs-nrf52-which-bluetooth-le-generation-is-right-for-your-next-product)
