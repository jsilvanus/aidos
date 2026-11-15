# RFC-0118: Aidos Engine Android Host

Status: Draft (2026-11-15, written from D49, `sdk/CONTRACT.md` and the code). Partially implemented: written and reviewed by reading; the Android targets do not compile in the cloud environment and nothing has run on a physical device.

Related: D49 (one host app, multi-process), D24 (foreground service), D54; RFC-0050 (Android agent app), RFC-0103 (Engine), RFC-0104 (locality), RFC-0105 (groups), RFC-0111/0112 (UI), RFC-0121 (SDK contract), RFC-0035 (secrets).

## Abstract

On Android the Aidos Engine is one installed app, `fi.italeino.aidos.engine`, that hosts the model Engine and (per D49) the agent runtime behind a single persistent notification. This RFC defines that host: its processes, its foreground service, how other apps find and are admitted to it, and what it stores. RFC-0050 covers the agent-side Android app behaviour; RFC-0103 covers the API the host serves.

## Motivation

D49 settled the shape (one APK, a main process and an isolated `:native_inference` process, two launchers) but the host itself has no RFC. Its parts are scattered: handshake and approval in RFC-0103 and the contract file, the foreground-service rule in D24, the UI in RFC-0111/0112. A reader cannot tell what the host must do, which of it is built, and which Android constraints are still open.

## Goals

- One always-on host app with one user-facing notification.
- Native crashes and low-memory kills in llama.cpp or whisper.cpp must not take down the UI, the Intent Graph or SQLite state.
- Any other app on the device can use the Engine only after the user approves it, and the approval is revocable.
- All model data, tokens and approvals live in app-private, encrypted storage.

## Non-goals

- Running the Engine without a foreground service. Android gives no indefinite background execution; background Runs prepare only (D24).
- A remote-client Android build (D19).
- The desktop host (RFC-0115 and `DesktopEngineHost`).

## Design

### Processes

The **main process** (~120 to 150 MB) hosts the activities, the System Assistant overlay, project management (`.aidos/state.db`), the Intent Graph engine, the HTTP server on `127.0.0.1` and the handshake service. The **`:native_inference` process** hosts model weights and native runtimes (2 to 4 GB). The main process talks to it over Binder. If it dies (segfault or Low Memory Killer), the main process catches `DeadObjectException`, records the fault, tells the user, and warm-restarts inference without losing typing or transactions.

### Discovery and admission

The handshake service is bound with action `fi.italeino.aidos.engine.HANDSHAKE`, explicitly targeted at the Engine package. The caller is identified by Binder's verified UID, resolved to a package. First contact records the app as `PENDING` and notifies the user once; the user approves or denies in Connected Apps; denial is sticky. `APPROVED` apps receive an ephemeral port and a per-app bearer token that is valid only while approved. The wire details are the frozen contract (RFC-0121). A separate per-app grant, default off, lets an app run agents (RFC-0120).

### Foreground service

The Engine host service is declared with foreground-service type `specialUse` (decided by the owner 2026-11-15, D49): it has no timeout, but needs the special-use subtype property in the manifest and a Play Console declaration if distributed through Google Play. Model downloads use `dataSync` or user-initiated data-transfer jobs, and the microphone type is added while STT is active. `dataSync` for the host itself is rejected: recent Android versions time it out and block starting it from a boot receiver. The design never promises indefinite background execution beyond what the device test (`docs/android-fgs-device-test.md`) confirms; scheduled work runs through WorkManager and only prepares.

### Storage and secrets

App approvals and licence acceptances are stored in encrypted stores (`EncryptedAppApprovalStore`, `EncryptedLicenseAcceptanceStore`). Model files live in app-private storage. Vault keys use the Android Keystore (`AndroidKeystoreVault`, RFC-0035), whose Gradle target is disabled until it can be built.

### Screens

Home, Models (browse, download, licence acceptance), Model detail and config, Providers, Connected Apps, Agents, MCP Tools, Groups, Settings, Test Chat. Presenters and view models are JVM-testable; the Compose screens are not.

### Notifications

One persistent service notification, plus approval prompts (new app, new agent grant, tool approvals). Notifications never carry correctness; they point to state.

## Security

- Binder's verified UID, not a caller-supplied name, identifies the app. A token is useless once approval is revoked (the next request returns 401).
- The HTTP server binds loopback only; LAN exposure is a separate, off-by-default desktop feature (RFC-0051 LAN).
- Native isolation bounds the damage of a native crash; it is not a sandbox against malicious model files, so packages come from known sources.

## MVP

Built and read, not run on a device: the handshake service and approval flow, encrypted approval and licence stores, model loader and catalog, the Android llama.cpp, ONNX decision, STT and vision backends, notification manager, and the screens listed above. The Binder path (`EngineHandshakeImpl`, `EngineService.onBind`, the notification and `PendingIntent`) is verified by reading. The first real Dictator to Engine approval on a phone is the acceptance test.

## Open Questions

- Does `specialUse` survive OEM battery management over hours with the screen off? Decided type, unverified: see the device test plan.
- Do process-death and interrupted-agent-job recovery tests pass on a device?
- Phone as a helper in an inference group needs the llama.cpp RPC binaries packaged as `jniLibs` (Android blocks execution from app data); tracked under RFC-0105.

## Implementation Files

```yaml
files:
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/EngineService.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/MainActivity.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/approval/AppApprovalManager.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/approval/EncryptedAppApprovalStore.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/binder/EngineHandshakeImpl.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/binder/HandshakeResult.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/inference/AndroidLlamaCppAdapter.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/inference/AndroidLlamaCppInferenceBackend.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/inference/AndroidOnnxDecisionBackend.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/inference/AndroidOnnxSttBackend.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/inference/AndroidOnnxVisionBackend.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/inference/InferenceTester.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/license/EncryptedLicenseAcceptanceStore.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/loading/ModelLoader.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/navigation/NavHost.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/navigation/Routes.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/notification/AppNotificationManager.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/suggestions/SuggestionPreferences.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/AgentsScreen.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/AgentsViewModel.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ConnectedAppsScreen.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ConnectedAppsViewModel.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/DeviceProfileProvider.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/GroupsScreen.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/HomeScreen.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/McpToolsScreen.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/McpToolsViewModel.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ModelConfigScreen.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ModelDetailScreen.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ModelDetailViewModel.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ModelsScreen.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ModelsViewModel.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ProviderDetailScreen.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/SettingsScreen.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/SettingsStore.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/StatusViewModel.kt
  - engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/TestChatScreen.kt
  - engine/androidapp/src/androidUnitTest/kotlin/fi/italeino/aidos/engine/ui/PackageValidationTest.kt
  - engine/androidapp/src/androidMain/AndroidManifest.xml
  - engine/androidapp/src/androidMain/res/values/strings.xml
  - engine/androidapp/src/androidMain/res/values/themes.xml
```

## Post-MVP Work

Checklist for work beyond the MVP. Each item has a stable id; set `status` to `partial` or `done` and list the implementing `files` (checked by `scripts/rfc/postmvp.py`).

```yaml
post_mvp:
  - id: "0118.1"
    title: "Run the foreground-service device test (specialUse chosen) and record the results"
    status: todo
    phase: P1
  - id: "0118.2"
    title: "Run the handshake, approval and notification flow on a physical device"
    status: todo
    phase: P1
  - id: "0118.3"
    title: "Process-death and interrupted-agent-job recovery tests on a device"
    status: todo
    phase: P1
  - id: "0118.4"
    title: "Enable the Android Keystore vault target and wire it in the host"
    status: todo
    phase: P1
  - id: "0118.5"
    title: "Instrumented tests for the Compose screens"
    status: todo
    phase: P2
  - id: "0118.6"
    title: "Android listener for LAN sharing (RFC-0051 LAN)"
    status: todo
    phase: P3
  - id: "0118.7"
    title: "Phone as inference-group helper with packaged llama.cpp RPC binaries"
    status: todo
    phase: P3
```
