# Android foreground-service device test plan

Decision: D49 (2026-11-15): the Engine host runs as a `specialUse` foreground service. This plan is the device test that confirms or overturns it. RFC-0118.

Run on at least two devices: a Pixel or other near-stock Android on the newest release, and a Samsung (One UI) because OEM battery management is the main unknown. Record model, Android version, build, battery state.

## Setup
1. Install the debug APK of `fi.italeino.aidos.engine`. Declare `FOREGROUND_SERVICE_SPECIAL_USE` and the subtype property in the manifest; grant the notification permission. Do not whitelist the app from battery optimisation for the first run; repeat the key cases with the whitelist on and record the difference.
2. Load one small chat model (about 1 GB) so memory pressure is realistic.

## Cases
| # | Case | Pass condition |
|---|---|---|
| 1 | Start the host, lock the screen, leave it 4 hours on battery | Notification still present; `GET /v1/models` through a connected app answers after unlock |
| 2 | Same overnight (8 hours), idle | Same; note whether Doze delayed anything |
| 3 | Start, then reboot | Host does not auto-start from the boot receiver unless the user opens the app; no crash loop |
| 4 | Kill `:native_inference` (`adb shell am kill` or LMK under load) | Main process stays alive; user is told; inference warm-restarts; no lost typing, SQLite intact |
| 5 | Memory pressure: open heavy apps until the Engine is trimmed | Host survives or restarts with state recovered; record what the OS did |
| 6 | Interrupted agent job: start a run, kill the host process | The run is reported as failed or resumable, never silently lost; no duplicate effect on retry |
| 7 | Model download of 1 GB with the screen off (`dataSync` / data-transfer job) | Completes or resumes; SHA-256 verified; no timeout kill within 6 hours |
| 8 | STT in use with the microphone type | Foreground start from the background is refused or allowed as documented; record |
| 9 | First contact from a second app (Dictator) | Pending notification once; approve; token works; revoke then 401 |
| 10 | Switch comparison: repeat 1 with `dataSync` instead of `specialUse` | Record the time at which the system stops or warns the service |

## Record
For each case: pass/fail, time to failure, the logcat lines from `ActivityManager` about the service, and the OEM battery setting in effect. Open an issue per failure and link it from RFC-0118's checklist item.
