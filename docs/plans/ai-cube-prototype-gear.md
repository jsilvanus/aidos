# AI cube: gear and equipment for the prototype

> **Update 2026-11-16.** Omi was deferred in RFC-0109 (rev. 2) and may be removed, so the Omi DevKit 2
> below is **not** recommended any more; skip it unless Juha revives Omi compatibility. Phase 1 now
> starts over USB CDC, so a plain USB-C data cable is the only link hardware needed at first. For
> judging microphones use Whisper small with Silero VAD on the desktop Engine, never tiny (it fails
> Finnish). See section 0a of `docs/plans/ai-cube-esp32.md`.

Status: suggestion, 2026-11-10. Based on `docs/plans/ai-cube-esp32.md` (sections 0, 4, 5, 7) and
`docs/plans/omi-products.md`. Nothing here is ordered or tested.

Evidence labels as in the plan: [known] = background knowledge, [estimate] = my guess, [verify] =
check the exact variant on the product page or schematic before buying. I did not look up current
prices or stock, so costs are rough ranges only.

## 1. Order in three tiers

Buy tier A now, tier B when Phase 0 passes, tier C only when Phase 3 is done.

| Tier | Goal | Rough cost [estimate] |
|---|---|---|
| A. Phase 0 starter | Record Finnish speech to storage, measure quality and current, prove the sync path to the laptop | about 80-150 EUR (parts) + tools you lack |
| B. Phase 1-3 breadboard | Full cube behaviour in a 3D-printed shell, both displays | about another 100-200 EUR |
| C. Custom PCB | One board, one enclosure | PCB fab + assembly, quotes needed |

## 2. Tier A: Phase 0 starter (order first)

Spares matter: order 2 of every module that can be killed by a wiring mistake (S3, mic, displays).

| Item | Why | Notes |
|---|---|---|
| 2 x ESP32-S3-DevKitC-1 **N16R8** (16 MB flash, 8 MB octal PSRAM) | The cube's MCU | [verify] "R8" means octal PSRAM; GPIO 35-37 are then reserved, which affects wiring |
| 2 x I2S MEMS mic breakout (ICS-43434 or INMP441 class) | Voice capture | [known]; get one of each to compare quality |
| 1 x microSD breakout (SPI or SDMMC) + 2 x 32 GB card (A1/A2, name brand) | Offline recording store | Cheap cards fail; buy two brands |
| 1 x 1.54" 240x240 IPS LCD (ST7789, SPI) | Live dictation display | |
| 1 x 1.54" 200x200 e-paper (SPI, partial refresh capable) | Memory/status card display | [verify] the exact controller and that it supports partial refresh; some "fast" variants do not |
| 1 x large tactile or arcade-style push button, 1 x EC11 rotary encoder with push | Push-to-talk, scroll | |
| 1 x 6-axis IMU breakout (LIS2DW12 or BMI270) | Tap/shake/face-up | [known] |
| 1 x DRV2605L breakout + LRA haptic motor | Confirmation feedback | Get an LRA, not an eccentric motor, for DRV2605L |
| 1 x 603450 LiPo (about 1000 mAh) with protection circuit + 1 x LiPo charger board with USB-C | Power | JST-PH vs other connectors: [verify] polarity, because swapped polarity between vendors will destroy boards |
| 1 x fuel gauge breakout (MAX17048) | Battery percentage | |
| Breadboard, jumper wires (M-M, M-F), header pins, 2 x USB-C data cables | Wiring | Use data cables, not charge-only |

**Phone-free recording reference (cheap, optional).** One Omi DevKit 2 (survey: speaker, haptic motor,
programmable button, 8 GB storage). Use it to test the Omi-compatible BLE audio path from the host
before our firmware exists. Caveat from the survey: BLE control of its speaker and haptics is not
exposed without a firmware patch.

**Fastest "already assembled" alternative for Phase 0 only:** M5Stack AtomS3R + Atomic Echo Base
(ESP32-S3, small display, mic, speaker) [sourced via the survey, verify kit] or the Waveshare
ESP32-S3-AUDIO-Board / DFRobot DFR1221 from the plan's section 5. Good for audio and BLE firmware
work in a day; they do not replace the breadboard because you cannot swap displays or add an SD slot
(M5 kit) the way the cube needs.

## 3. Tools and test equipment

| Tool | Need | Why |
|---|---|---|
| Soldering iron with temperature control (about 300-350 C), leaded or lead-free solder, flux, desoldering braid | Must | Pin headers on breakouts, battery leads |
| Digital multimeter | Must | Continuity, battery voltage, polarity checks before first power-up |
| USB power meter (inline USB-C, shows mA) | Must | First look at current draw |
| **Proper current profiler** (Nordic Power Profiler Kit II, or Joulescope) | Should, by Phase 0 exit | Deep-sleep current in microamps and wake spikes. A USB power meter cannot see them; this decides whether the "weeks of standby" estimate is real [estimate] |
| Cheap logic analyzer (8-channel USB, Saleae-compatible) | Should | Debug I2S, SPI and display timing |
| Bench or lab power supply, 0-5 V, current limited | Nice | Safe first power-up of a custom board |
| Reference audio recording setup: your phone, a good lavalier or USB mic, a quiet room and a nave or hall | Must for the quality test | Compare the cube's recordings to a phone recording of the same Finnish lines |
| Sound-level app or meter | Nice | Log room level to explain bad takes |
| Helping hands / PCB holder, tweezers, flush cutters, side cutters | Should | |
| Heat-shrink, Kapton tape, small screwdrivers | Should | Battery and enclosure work |
| LiPo-safe storage (fire-resistant pouch or metal box) and a charging spot away from flammables | Must | Never charge or leave cells unattended; stop on any swelling |

If you have no electronics bench at all, the lowest-cost route is to start with the M5Stack or Waveshare
all-in-one boards for Phase 0 and buy the soldering gear and current profiler before tier B.

## 4. Tier B: add when Phase 0 passes

- 3D printing: either a printer (PLA/PETG, 0.2 mm layers is fine for 50 x 50 x 30 mm shells) or an
  online print service. Resin gives cleaner buttons and display bezels; FDM is enough for fit checks.
- Switch and fidget parts: tactile switches of different travel and force, a small thumb wheel
  or rotary dial, a slide switch, small neodymium magnets for a magnetic back plate, M2 screws and
  heat-set inserts. Buy assortments; fidget feel is found by trying.
- Wiring upgrade: perfboard or a "FeatherWing/proto" board, 28-30 AWG silicone wire, JST-SH/PH
  connectors, so the breadboard does not fall apart in a pocket.
- Extra e-paper and LCD variants if the first ones disappoint (both are cheap).
- A second battery of the same type, to test run time while measuring the first.
- Optional, v2-only: MAX98357A + 8 ohm speaker. Deferred per your decision, so do not buy yet.
- Calipers (digital) for the enclosure.

## 5. Tier C: custom PCB (later)

- KiCad (free) on your computer; ESP32-S3-WROOM-1 N16R8 module footprint; 4-layer board about 46 x 46 mm.
- A JLCPCB/PCBWay-style fab and assembly service, a hot air station or reflow oven if you want to
  hand-assemble, and a USB-C ESD/TVS parts list. Do the schematic review before ordering, including
  the recording indicator hard-wired to the mic enable (plan section 8).
- Quote and lead time unknown, ask after Phase 3.

## 6. Software and accounts (free)

- ESP-IDF 5.x toolchain on a Linux or Windows machine (USB flashing and a serial monitor). Needed
  from day one.
- Node.js (ESM) for the desktop sync/test tool in Phase 1, per your usual stack. A Bluetooth 5 adapter
  on the laptop if it has none built in.
- Android phone with BLE 5 and USB debugging, plus the Aidos host app build, for Phase 2. The Android
  leg is untested on a device so far (project memory), so keep the phone available for it.
- A Finnish test set: 30-50 lines of liturgical and everyday dictation read aloud, with the correct
  transcript written down, for the word-error-rate comparison in Phase 0/1.
- Optional: an oscilloscope. Not needed for v1.

## 7. What to do first (concrete)

1. Order tier A (including spares) and one Omi DevKit 2 if you want the BLE audio reference.
2. Get the current profiler (PPK2 class) as soon as you decide to take the standby estimates seriously;
   it is the single most useful measurement tool for this device.
3. Do Phase 0: record to SD, listen, compare to phone, measure current, check the first-syllable
   pre-roll. Report back; that decides whether to buy tier B.

## 8. Risks specific to this gear list

- Display modules vary by seller; the same product name ships different controllers. Check the
  controller and voltage on the listing and schematic.
- ESP32-S3 octal PSRAM boards reserve some GPIOs; wire after reading the board pinout.
- LiPo handling: polarity, protection circuit, and charging unattended are the only real hazards here.
- Microphone quality depends more on the port and acoustic path in the enclosure than on the part;
  expect to reprint the case at least once.
