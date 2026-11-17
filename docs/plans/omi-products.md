# Omi (Based Hardware) products and what they offer Aidos

Date: 2026-10-26. Companion to `ai-button-existing-products.md`.
Method: Omi docs pages, a release note and web search. **I could not read the firmware source** (GitHub API returned 403 and raw/tree pages gave no file contents), so anything about BLE characteristics beyond the public protocol page is unverified. Items marked (unverified) need a look at `omi/firmware` in the repo or a real device.

## Answer to "can Omi give input back?"

Partly, on hardware; not yet, in software.

- **DevKit 1 (original pendant):** microphone only. No output.
- **DevKit 2 (Omi, v2.0.0):** has a **speaker and a haptic motor**. The release note says both play on startup, and that "software control over them is planned for a future release". So the hardware can give feedback, but the public BLE protocol page documents **no** characteristic to drive them. Today the firmware uses them only for its own cues (boot sound/buzz, recording state).
- **Omi Glass dev kit:** the docs list no speaker/output.
- **No display on any of them.**
- Because firmware is open (MIT, Zephyr), exposing a "play tone / buzz / play audio" characteristic is a firmware change, not a hardware change. Playing spoken replies through the DevKit 2 speaker would need audio-out over BLE, which does not exist in the documented protocol (unverified in source).

## Product line

| Product | MCU | Capabilities (from docs) | Output to user | Notes |
|---|---|---|---|---|
| **Omi DevKit 1** | Seeed XIAO nRF52840 Sense | Mic, BLE audio streaming, on/off switch | None | Original pendant. V2 firmware is not compatible with it |
| **Omi DevKit 2 / "Omi"** | XIAO nRF52840 | Mic, **8 GB onboard storage** for standalone recording, **programmable button**, speaker, haptic motor, necklace form | Speaker + haptic (firmware-controlled for now) | Firmware v2.0.0 `omi_v2.0.0.uf2`, bootloader 0.9.0. Button: hold ~3 s stops recording, long press resumes. Storage endpoints exist in firmware; the release note says SD testing needed a later app release and points to `firmware/testing/sdcard_test_files` |
| **Omi Glass dev kit** | XIAO ESP32-S3 Sense | Camera + audio, glasses form, six 150 mAh + one 250 mAh battery | Not specified | Arduino `.ino` firmware (MIT), flashed via Arduino IDE; connects to Omi app. Limited pre-built dev kit sold by Based Hardware; price not listed on the docs page |

Price: only one third-party listing seen ($179 list, $129 promo for the pendant). Glass price unknown.

## What is public and usable for Aidos

- **BLE (documented):** discovery by name `Omi`. Services: Battery `0x180F`/`0x2A19`; Device Info `0x180A` (manufacturer, model, hw rev, fw rev); Audio Streaming `19B10000-E8F2-537E-4F6C-D104768A1214` with audio data `…0001` (notify) and codec `…0002`. Codec values: 0 PCM16k, 1 PCM8k, 10 µ-law16k, 11 µ-law8k, 20 Opus16k (default since fw 1.0.3). Packet header: 2-byte LE counter + 1-byte chunk index; 160 samples per audio packet, split over MTU.
- **Not documented on the protocol page:** button events, storage / offline sync, speaker, haptic, LED. These very likely exist as extra services in firmware (the DevKit 2 has the hardware and the release note mentions "endpoints for the onboard memory") (unverified).
- **SDKs listed in the repo README:** shared BLE UUIDs and framing for TS/Go/Rust/C++/Dart; a Python device SDK (BLE + Opus + Deepgram); Swift and React Native SDKs.
- **Licence:** MIT for firmware, app, backend and SDKs.
- **Backend:** Python/FastAPI with Firebase; STT is Soniox/Deepgram in their stack. Transcription requires the phone app. A custom STT endpoint is not documented, so for Aidos we bypass their app and speak BLE directly.

## Fit for Aidos

| Need | Omi DevKit 2 | Verdict |
|---|---|---|
| Push-to-talk / record | Programmable button + always-on mic | Likely OK, button event format unverified |
| Offline recording | 8 GB storage, standalone mode | Hardware yes, sync protocol unverified |
| Phone pairing | BLE, documented audio | Yes. Aidos Agent (Android) BLE client -> Opus decode -> Engine STT |
| Feedback to user | Speaker + haptic, but no documented control | Needs firmware work |
| Display | None | No. The cube idea needs another device |
| Cloud independence | Audio path can skip Omi cloud | Yes if we write our own client |

## Options for the "give input back" gap

1. **Phone-side output only:** reply through phone speaker / earbuds; Omi is mic-only. Zero firmware work, least cube-like.
2. **Patch Omi firmware** to add a small control service (haptic pulse patterns, tone ids, LED) and later an audio-out channel. Fork the repo (MIT). Realistic for haptic/tones; full TTS audio-out over BLE is bigger and unverified.
3. **Skip Omi hardware; reuse its BLE audio framing on the custom ESP32 cube** (display + mic + speaker). The cube then needs no Omi hardware and Aidos has one BLE client for both.

Recommendation: buy one DevKit 2 to learn the real BLE surface (button, storage, haptic), and use it as the Aidos BLE reference. Plan the display/feedback cube on ESP32 per option 3.

## To verify next (needs repo access or the device)

1. List the BLE services in `omi/firmware` (button, storage, speaker, haptic, features).
2. Whether storage sync is exposed over BLE and in what format.
3. Whether any firmware accepts audio data to play on the speaker.
4. Omi Glass: output hardware, BLE profile, price.

## Sources
- https://github.com/BasedHardware/omi
- https://docs.omi.me/doc/developer/Protocol.md
- https://docs.omi.me/doc/hardware/DevKit2.md
- https://docs.omi.me/doc/hardware/omiGlass.md
- https://newreleases.io/project/github/BasedHardware/omi/release/v2.0.0-Omi
