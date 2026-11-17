# AI button: existing products that could join Aidos

> **Update 2026-11-16.** This survey predates RFC-0109 (Capture Device Protocol, draft). Two of its
> recommendations no longer hold: Omi is **deferred and may be removed** (RFC-0109 rev. 2), so "buy one
> Omi" and "a profile that is a superset of Omi's service" are not the plan; ACP has its own service
> and any third-party button integrates by implementing ACP. The product facts below were not
> re-checked on this date. See section 0a of `docs/plans/ai-cube-esp32.md`.

Scope: buy/integrate options only (custom ESP32 build is another thread).
Date: 2026-10-26. Source is web search plus a few page fetches; confidence is marked per claim. Prices/availability move fast, verify before buying.

## Bottom line

1. **No shipping product matches the "fidget cube with display, PTT, offline storage, phone-pairable" idea.** The market is pendants, clips, cards and rings, all screenless.
2. **Omi is the only open option that can be integrated today.** MIT licensed hardware/firmware/app, documented BLE audio protocol (Opus/PCM 16 kHz), so an Aidos client can talk to it directly and send audio to the Engine's `/v1/audio/transcriptions`. It is also the best dev reference for our own cube (the BLE audio service and packet framing can be copied).
3. **Plaud works only through a partner-gated, proprietary SDK tied to Plaud cloud.** Poor fit for an offline-first, privacy-first ecosystem.
4. **Flic is a good trigger, not a recorder.** Use it as a cheap push button (start/stop dictation), not as an audio device.
5. **Limitless is dead, Bee is Amazon-locked.** Skip.
6. **M5Stack ATOMS3R + Echo Base style devkits are the nearest "buy" hardware to the cube** (display + mic + speaker + ESP32-S3, off the shelf), and bridge into the ESP32 thread. Treat as prototype hardware, not a product.

## Comparison

| Product | Form | Display | Offline storage | Phone link | Openness | Fit for Aidos |
|---|---|---|---|---|---|---|
| **Omi** (Based Hardware) | Pendant/clip, nRF + Zephyr | No | Not confirmed in docs I read | BLE, documented | MIT; firmware, app, backend on GitHub; SDKs in TS/Go/Rust/C++/Dart/Python/Swift/RN | **High.** Direct BLE client in Aidos; STT swap to Engine |
| **Omi Glass dev kit** | ESP32-S3 + camera + audio | No | n/a | BLE | Open | Medium, reference for camera/vision |
| **Plaud NotePin S / Note Pro** | Pin / card | No | Yes (device records, syncs) | BLE + Wi-Fi | Proprietary SDK binaries (Apache-2.0 only for the sample code); partner account, JWT, Plaud cloud servers | **Low.** Locked to cloud and partner onboarding. SDK does expose live PCM 16 kHz and MP3 export, but gated |
| **Flic 2 / Twist / Hub** | Programmable button | No | n/a | BLE (to phone or Hub) | Hub SDK, local scripting, MQTT; Twist rotation not exposed in SDK as of last forum posts; mini hub has no SDK | **Medium as trigger only.** Button events to Aidos Agent actions |
| **Limitless pendant** | Pendant | No | Yes | BLE | Closed; Meta bought it, sales ended Dec 2025, service ended in EU/UK | **None** |
| **Bee (Amazon)** Frontier $75 / Eclipse $100, launched 2026-10-15 | Wristband / phone attachment | Unknown | Unknown | BLE | Closed, Alexa+ integrated | **None** (cloud-bound) |
| **Sandbar Stream ring** | Ring, whisper capture | No | Says no audio stored | BLE | No API found; app works only with the ring | **None** |
| **Friend pendant** | Pendant | No | n/a | BLE | No dev access found | **None** |
| **M5Stack ATOMS3R + Echo Base** | Tiny ESP32-S3 devkit, 0.85" LCD, mic, speaker | Yes (tiny) | SD via other modules | BLE/Wi-Fi | Fully open (Arduino/UIFlow/MicroPython) | **Prototype platform**, ties to ESP32 thread |

## Integration paths per candidate

### Omi (recommended first target)
- Device advertises as `Omi`; audio service `19B10000-E8F2-537E-4F6C-D104768A1214`, audio data `…0001`, codec `…0002`. Packets: 2-byte counter + 1-byte chunk index + payload; Opus 16 kHz default on firmware >= 1.0.3 (also PCM/µ-law).
- Path: **Aidos Agent (Android) BLE client -> decode Opus -> Engine STT (Whisper ONNX)**. On Android that is an in-process call in the joined host app (D49); on desktop, loopback HTTP to the Engine.
- Heavy compute (large Whisper, LLM) can go out through AIDIN distributed inference per the locality rules (default local_only; remote must be explicit).
- Their backend is Python/FastAPI/Firebase and ships with Deepgram/Soniox; custom STT endpoint support was NOT confirmed, so we would bypass their app and talk BLE ourselves rather than reconfigure theirs.
- Unknowns to check on real hardware: button events, haptics, offline/SD sync (not in the protocol page I read), battery behaviour.

### Plaud
- SDK needs a partner account, per-user JWT and Plaud server domain; firmware updates also come from Plaud. Binaries are proprietary. Good audio quality and storage, but would put a third party in the loop and block offline use. Only consider if Juha wants to ingest Plaud recordings as files (MP3 export), not as a live device.

### Flic
- Hub SDK gives local scripting/MQTT, so a Hub (or phone app action) can fire a webhook to an Engine/Agent endpoint to toggle recording. Cheap "push-to-talk remote" for the phone/desktop, no audio on the button. Twist support in SDK was still "roadmap" per forum posts, so do not plan around rotation.

### M5Stack-class devkits
- Same BLE audio service pattern as Omi could be implemented on the ESP32-S3, giving a display-equipped prototype quickly. Hand-off to the ESP32 thread: reuse Omi's BLE audio framing so one Aidos BLE client serves both.

## Recommended next steps

1. Buy one Omi (list $179, promos seen at $129) and test the BLE path into the Engine's `/v1/audio/transcriptions`. Cheap, answers the offline-storage and button-event unknowns.
2. Specify an "Aidos audio device" BLE profile that is a superset of Omi's service so Omi, a Flic-triggered phone and the custom cube all look the same to the Agent.
3. Optionally a Flic 2 for a push-to-talk trigger demo on desktop.
4. Skip Plaud/Bee/Limitless/Sandbar/Friend.

## Gaps and caveats

- Search tooling returned thin results for Pocket, Heyday and Friend; I found nothing on APIs for them and did not verify.
- Omi price and offline-storage support are unconfirmed beyond one third-party listing.
- Plaud SDK platform details came from the public repo README; Plaud's own support page says there is no general public API.
- Limitless/Bee facts come from news coverage; Bee Eclipse "soon" availability.

## Sources
- Omi repo: https://github.com/BasedHardware/omi ; BLE protocol: https://docs.omi.me/doc/developer/Protocol
- Plaud SDK: https://github.com/Plaud-AI/plaud-sdk-public ; devices: https://docs.plaud.ai/plaud-embedded/devices ; API-access FAQ: https://support.plaud.ai/hc/en-us/articles/60726890231449-How-can-I-get-API-access-to-my-Plaud-data
- Limitless/Meta: https://www.therundown.ai/tools/limitless
- Bee: https://techcrunch.com/2026/10/15/amazons-bee-launches-two-new-personal-ai-devices-starting-at-75/
- Sandbar Stream: https://techcrunch.com/2026/03/10/sandbar-secures-23m-series-a-for-its-ai-note-taking-ring
- Flic Twist SDK forum: https://community.flic.io/post/21129
- M5Stack ATOMS3R kits: https://www.mouser.com/en/new/m5stack/m5stack-atoms3r-kits/
- Voice pendant reference (ESP32, offline Android companion): https://github.com/Mayankpratapsingh022/Voice-Wearable-Pendant-Offline-inference
