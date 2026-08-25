# Plan: Dictator as Aidos SDK's first consumer

Status: Proposed 2026-08-16
Relates to: RFC-0103 (Aidos Engine — Shared Local Inference Service), RFC-0021, RFC-0022, RFC-0023

## Why this plan exists

RFC-0103 exists because "a second app, independent of Aidos Agent, is already in development and
needs the same local LLM/embedding/STT inference." That app is Dictator
(`jsilvanus/dictator`) — a voice-first document editor whose Android port lives in
`dictator-kotlin/`. This plan makes Dictator the SDK's first real consumer.

It is worth stating plainly that Dictator, not Aidos Agent, gets there first. RFC-0103 says
"Aidos Agent is Aidos SDK's first consumer, not a special case baked into it: any other app on
the device links the same library and gets identical behaviour." Nothing consumes `sdk/` today —
not `agent/`, not anything. Building against a third-party app first is the stronger test of that
claim, because a genuine third party cannot quietly reach for `kernel` or for Agent-internal
types when the SDK's own surface falls short.

## What is actually built today

Established by reading the code on `main` at `1a28dc6`, not by reading status lines.
**Superseded by the Status lines on each phase below as of 2026-08-24** — S0 and S1 turned out to
already be done by the time this plan's SDK work started, and S2/S3 have since had real code
land. This section is kept as the historical baseline the phases were written against; check the
phase Status lines for current state, not this section, per the same "don't trust a status line
over the code" rule CLAUDE.md/lessons.md apply everywhere else.

**Engine is real.** `engine/androidapp` has a working Binder surface
(`IEngineHandshake.aidl`, `EngineHandshakeImpl`), a Ktor server bound to `127.0.0.1` with bearer
auth (`EngineHttpServer`), and all three endpoints — `/v1/chat/completions`, `/v1/embeddings`,
`/v1/audio/transcriptions`. The user-approval model from RFC-0103's Trust model section is built
and merged (`e26aee4`): `AppApprovalStore`, `AppApprovalManager`, `ConnectedAppsScreen`, and the
three-state `APPROVED` / `PENDING_APPROVAL` / `DENIED` handshake result.

**The SDK is not.** `sdk/` is two files, and:

- `EngineClientImpl.initialize()` contains no Binder code at all. It sets `isConnected = false`
  and returns `false` unconditionally, under a comment saying proper integration "requires Android
  framework access."
- The SDK's internal `HandshakeResponse` has no `status` field, so it cannot represent
  `PENDING_APPROVAL` or `DENIED` — it does not model the contract Engine actually returns.
- `EngineModelAdapter.kt` does not compile. `escapeJsonString` is missing its closing brace (the
  file has 55 `{` against 54 `}`), which makes `convertHttpResponseToKernel` a local function
  declared after a `return`.
- That same file imports `dev.aidos.kernel.*` and `kotlinx.serialization.json.*`, but
  `sdk/build.gradle.kts` declares neither the serialization plugin nor a dependency on `:kernel`,
  and `sdk/settings.gradle.kts` does not `include(":kernel")`.
- `sdk/build.gradle.kts` pins Kotlin 2.1.0 while `agent/` and `engine/` are both on 2.4.10.
- `okhttp` is a declared dependency and is never used; the transport is `HttpURLConnection`.
- There is no SSE client, so `/v1/chat/completions` with `stream: true` is unreachable.
- There are no tests, and no `jvm()` target to run any on.

CI reflects this: `build-sdk` is red on `main` (run 31900887492), alongside `test-agent` and
`test-engine`.

## The blocker, and why it is one line of manifest

Dictator cannot reach the approval flow that was built for it.

```xml
<!-- engine/androidapp/src/androidMain/AndroidManifest.xml -->
<permission android:name="fi.italeino.aidos.engine.HANDSHAKE"
            android:protectionLevel="signature" />                    <!-- :9 -->

<service android:name="fi.italeino.aidos.engine.EngineService"
         android:permission="fi.italeino.aidos.engine.HANDSHAKE">     <!-- :64 -->
```

Android grants a `signature` permission only to apps signed with the declaring app's certificate,
and the service is gated on holding it. A differently-signed caller — `com.dictator.android` —
receives a `SecurityException` at `bindService`. It never reaches `performHandshake()`, so
`AppApprovalManager.checkApproval()` never runs, so it never records a `PENDING` row, so it never
appears on ConnectedAppsScreen. **The approval UI can only ever offer apps that already cleared
the signature gate**, which is precisely the set of apps that did not need approval to connect.

RFC-0103's own reasoning already rejects this outcome. Its Trust model section adopted user
approval because "F-Droid rebuilds and re-signs submitted apps by default… If Engine and Agent are
both distributed through F-Droid with different re-signing configurations, they will not match on
certificate — the handshake fails silently in production while working in local debug builds,
which is exactly the failure mode worth avoiding." Keeping `protectionLevel="signature"`
reintroduces that failure verbatim.

Two passages in RFC-0103 still assert the old model and contradict the section above:

- Trust model: "The handshake's `signature` protection level remains an OS-enforced initial gate
  for technical safety: only apps with the permission can *reach* the handshake at all."
- Non-goals: "This RFC does not open Aidos Engine to differently-signed (third-party) clients. v1
  is signature-only; opening it further is Future Work and is not designed here."

Phase S1 amends both, in its own commit, per CLAUDE.md's RFC-update process.

## Decisions taken

Recorded here because each closes off an alternative someone will otherwise re-open.

**D-1 · The SDK's primary surface does not depend on `kernel`.** A third-party app should not
have to link Aidos's frozen contract types — `TrustLevel`, `Permission`, `ToolDescriptor`,
`Turn`, `ContentBlock` — to ask for a chat completion. The SDK splits into two published
artifacts: `aidos-sdk-client` (transport, handshake, plain OpenAI-shaped types, no `kernel`) and
`aidos-sdk-adapters` (the `ModelAdapter` implementations RFC-0103 MVP item 5 requires, depending
on both `:kernel` and the client). Dictator consumes only the first. Agent, which already depends
on `kernel`, consumes both.

**D-2 · Approval, not signature, is the gate.** Per above and the owner's explicit confirmation.

**D-3 · Streaming is a hard requirement, and it means real streaming.** See Phase S4 — SSE
framing in the SDK is necessary but not sufficient.

**D-4 · Dictator consumes the SDK from GitHub Packages**, reusing the
`maven.pkg.github.com` pattern already present in `agent/settings.gradle.kts` and
`engine/settings.gradle.kts` for `gitsema-kotlin`. This gives a real version boundary between the
repos, which RFC-0103 asks for: the SDK is "versioned and distributed independently of both Aidos
Agent and Aidos Engine."

**D-5 · The SDK serves all three capabilities; Dictator consumes two of them.** LLM chat,
embeddings, and STT are all in the SDK's client surface — that is RFC-0103 MVP item 5, and it is
not contingent on any one consumer wanting all three. Dictator takes LLM chat and STT.

Embeddings deliberately have **no Dictator-side phase**. Nothing in Dictator consumes vectors
today — a `grep` for embeddings across both the web app and `dictator-kotlin` returns only
`EmbeddedPackagingStrategy.ts` and `c2pa-manifest.ts`, which are "embedded" in the unrelated
file-packaging sense. Wiring a provider method with no caller would be plumbing that rots until
something uses it. The feature that would use it — semantic search over dictated documents —
carries its own design questions (vector storage in the SQLDelight schema and whether it
participates in sync, embedding granularity against Dictator's existing paragraph-level identity,
when vectors are recomputed, similarity search without a native SQLite vector index) that have
nothing to do with talking to Aidos Engine. It gets scoped on its own merits, separately. When it
is, the SDK side is already built and waiting.

## Phases

Each phase states its done-when condition. A phase is not finished because its code exists; it is
finished when the condition holds.

### S0 · The SDK compiles

**Status (2026-08-24): Done.** Kotlin 2.4.10, the serialization plugin, and a `jvm()` target were
all already in place before this plan's SDK work started; `gradle jvmTest` is green.

The smallest change that turns `build-sdk` green, so every later phase has a baseline.

- Close the brace in `EngineModelAdapter.kt`.
- Move `sdk/` to Kotlin 2.4.10, matching `agent/` and `engine/`. Mandatory before `sdk/` can
  include `:kernel` by path in S3 — two Kotlin versions cannot share one source-included module.
- Add the `kotlin("plugin.serialization")` plugin and the `kotlinx-serialization-json` dependency
  that the existing code already imports.
- Add a `jvm()` target so pure logic is testable off-device. CI's current comment — "sdk/ is an
  Android library with no jvm() target and no tests yet" — stops being true here.

*Done when:* `cd sdk && gradle build` succeeds, and the `build-sdk` CI job is green.

### S1 · User approval becomes reachable

**Status (2026-08-24): Done**, all of it, before this plan's SDK work started: the permission is
`protectionLevel="normal"` with the exact rationale below already in RFC-0103's Trust model
section (the contradictory `signature`-only passages quoted below are gone), and
`EngineHandshakeImpl.buildApprovedResult()` already builds `capabilities.models` from
`modelRuntime.catalog()`, not a hardcoded placeholder list. Device-level verification of the
*done when* condition (a differently-signed app actually transitioning PENDING → APPROVED) is
still outstanding — nothing here confirms that end-to-end on hardware.

- `protectionLevel="signature"` → `"normal"` on `fi.italeino.aidos.engine.HANDSHAKE`. The
  permission stays declared, so it remains visible and inspectable in a caller's manifest ("this
  app talks to Aidos Engine"); the OS simply grants it on request rather than on certificate
  match. `AppApprovalStore` becomes the real decision point, which is what the Trust model
  section already intends.
- The `android:permission` attribute on `EngineService` stays. Losing it entirely would remove the
  technical gate RFC-0103 wants; downgrading its protection level is the whole change.
- **Separate commit**: amend RFC-0103's Trust model paragraph and the Non-goals bullet quoted
  above, so the RFC stops asserting signature-only in two places while designing user-approval in
  a third. Add a one-line note that the SDK ships as two artifacts (D-1).
- Replace the hardcoded placeholder capability list in `EngineHandshakeImpl.buildApprovedResult()`
  — currently literal `llama-7b` and `nomic-embed-text` entries behind a
  `TODO(RFC-0103 Phase B)` — with the real catalog. A client cannot select a model against
  placeholders, and RFC-0103 is explicit that `capabilities.models` is "the ENABLED set only."

*Done when:* a debug-signed app with a different certificate binds `EngineService`, appears as
PENDING on ConnectedAppsScreen, and transitions to APPROVED after the user taps Approve — with
`capabilities.models` listing models that are actually installed.

### S2 · The SDK client becomes real

**Status (2026-08-24): Done**, module split included. `sdk/client/` exists with no `:kernel`
dependency; the handshake models all statuses as a sealed `HandshakeOutcome` (`PendingApproval`
surfaces its `PendingIntent` via the Android-only `AndroidEngineClient.pendingApprovalIntent()`,
kept off the shared interface since `PendingIntent` doesn't exist on `jvm()`); the `<queries>`
entry for Android 11+ package visibility is in the client's manifest; transport is OkHttp with
401-triggered re-handshake-and-retry (fixing, in passing, a pre-existing bug where the
`Authorization` header sent the literal string `"******"` instead of the real token); all three
endpoints have typed methods plus a `streamChatCompletion(): Flow<ChatCompletionChunk>` backed by
an `SseFrameParser`; `EngineAvailability` is the structured degradation signal the "one signal"
bullet below asks for. 16 tests pass on `jvm()`, including MockWebServer-backed coverage of the
401 retry and SSE paths, not just pure parsing. Not done: the actual device-level "streams
token-by-token from a real Engine" verification in the *done when* line — that needs S4 anyway,
since Engine still buffers the full response before framing it (see S4).

- **Handshake.** Copy `IEngineHandshake.aidl` into the SDK, `bindService` against
  `fi.italeino.aidos.engine/.EngineService`, and model all three response states as a sealed
  result. `PENDING_APPROVAL` surfaces its `PendingIntent` to the caller rather than being
  flattened into a failure — a caller must be able to send the user to ConnectedAppsScreen.
- **Package visibility.** Android 11+ filters cross-package visibility, so consuming apps need a
  `<queries>` entry for `fi.italeino.aidos.engine`. Ship it in the SDK's own manifest so it
  merges into every consumer rather than being each app's problem to remember.
- **Session lifecycle.** Re-handshake when Engine restarts: the port is ephemeral and the token is
  scoped to one handshake, per RFC-0103. A 401, a connection refusal, or a changed port all mean
  "re-handshake," not "fail."
- **Transport.** Replace `HttpURLConnection` with OkHttp, which is already a declared dependency.
- **All three endpoints, regardless of who consumes them.** `/v1/chat/completions`,
  `/v1/embeddings`, and `/v1/audio/transcriptions` each get a typed client method. Embeddings ship
  here even though no phase of this plan has Dictator calling them (D-5): the SDK's surface is set
  by RFC-0103 MVP item 5 and by Engine's endpoints, not by what its first consumer happens to
  need. Leaving a hole for the one capability nobody asked for yet would just mean the next
  consumer files a bug.
- **SSE (D-3).** A streaming chat call exposed as a `Flow` of deltas, parsing `data:` frames and
  terminating on `data: [DONE]` — the exact framing `EngineHttpServer.streamChatCompletions`
  emits. This is the capability the owner called out as required.
- **Typed negotiation.** `apiVersion` as a strict compatibility check and `capabilities` as a
  typed model/endpoint list, so no consumer parses the handshake JSON itself.
- **One degradation signal.** Engine not installed, bind refused, handshake failed, approval
  pending, or `apiVersion` incompatible all resolve to one caller-facing state, per RFC-0103's
  Degradation section — with enough structure that a UI can tell "not installed" (offer install)
  from "pending approval" (offer deep link).
- **Wire shape note.** `/v1/audio/transcriptions` currently accepts base64 in a JSON body
  (`TranscriptionRequest.file`), not multipart as the real OpenAI API does. The SDK matches what
  Engine actually accepts. Aligning to multipart later is an Engine-side change and is out of
  scope here; recorded so it is not mistaken for an SDK bug.
- **Tests**, on the `jvm()` target: SSE frame parsing including split frames and `[DONE]`,
  request/response JSON mapping, capability and version negotiation, and the degradation state
  machine. The Binder half needs an instrumented test or a device and is not gated on here.

*Done when:* SDK tests pass on `jvm()`, and a sample app streams a completion token-by-token from
a real Engine on a device.

### S3 · Adapters artifact and publishing

**Status (2026-08-25): Done**, with one open caveat below. The module split landed as part of S2
above: `sdk/adapters/` exists, depends on `:kernel` and `:client`, and its `ModelAdapter` classes
are public (they were `internal` pre-split, which would have made them unreachable from outside
`sdk/` even after publishing). `EngineTranscriptionAdapter` is also fixed: `ContentBlock` gained a
proper `Audio(mimeType, data)` case (kernel change, RFC-0021's `ContentBlock` sealed interface),
and both the adapter and Engine's own `handleTranscriptions` (which fed it the `ContentBlock.Image`
audio/wav hack in the first place) now use it.

Maven publishing is wired: both modules apply `maven-publish`, group `fi.italeino.aidos.sdk`,
artifacts renamed to `aidos-sdk-client`/`aidos-sdk-adapters` (Kotlin Multiplatform's default
artifactId is the Gradle project name — "client"/"adapters" — so this needs an explicit rename),
publishing to `https://maven.pkg.github.com/jsilvanus/aidos` per D-4. A new
`.github/workflows/sdk-publish.yml` runs `gradle jvmTest` then `gradle publish` on push to `main`
(paths `sdk/**`, `kernel/**`) or `workflow_dispatch`, versioning the same way
`android-build-and-publish.yml` already versions the Agent APK (`0.1.0-build.<run>+<sha8>` — no
real semver policy exists yet). Also fixed while wiring this: `:kernel` and `:client` were
`implementation` dependencies in `adapters/build.gradle.kts`, but both appear in public
constructor/supertype signatures (`ModelAdapter`, `ModelRequest`/`ModelResponse`,
`AidosEngineClient`) — `implementation` would have left them off a consumer's compile classpath
while still pulling them in at runtime. Both are `api` now.

**Open caveat, not fixed here because it's a decision, not a bug:** `:kernel` is never published —
by design, per RFC-0103 ("frozen contract types... depended on by everything, depends on none,"
source-included everywhere). Generating `aidos-sdk-adapters`'s POM locally shows exactly what that
means for this artifact: its `:kernel` dependency resolves to
`groupId=aidos-sdk, artifactId=kernel-jvm, version=unspecified` — Gradle's placeholder for a
subproject that never declared its own coordinates, referencing nothing that exists on GitHub
Packages. A consumer *inside* this monorepo (Agent, this SDK itself) never notices, since it also
source-includes `:kernel` and never touches the published POM. A genuinely external consumer
(Dictator) resolving `aidos-sdk-adapters` alone from GitHub Packages will hit an unresolvable
dependency. Publishing `:kernel` too (giving it real coordinates) or vendoring its types into this
artifact would fix it; neither is done pending an explicit decision on what "kernel is never
published" should mean once something that depends on it is.

- New module `sdk/adapters/`, published as `aidos-sdk-adapters`, depending on `:kernel` and on
  `aidos-sdk-client`. The `ModelAdapter` implementations for LLM, embedding, and STT move here —
  satisfying RFC-0103 MVP item 5 without imposing `kernel` on third-party consumers.
- Fix `EngineTranscriptionAdapter` while moving it: it currently reads audio out of a
  `ContentBlock.Image`, which is how Engine's own `handleTranscriptions` happens to pass audio
  internally, but is not a sane public shape for an STT adapter.
- Maven publishing for both artifacts to `maven.pkg.github.com/jsilvanus/aidos`, with a CI job to
  publish on tag.

*Done when:* both artifacts resolve from GitHub Packages in a clean project, and `agent/` can
depend on `aidos-sdk-adapters` without a source dependency on `sdk/`.

### S4 · Engine streams for real

**Status (2026-08-24): Done.** `ModelAdapter` gained an `invokeStreaming()` method (default
implementation falls back to `invoke()`, so every existing adapter — `AnthropicAdapter`, the SDK's
own `EngineLocalModelAdapter`, test fakes — keeps compiling unchanged); `LlamaCppAdapter` overrides
it with the real per-token loop it already had internally (it was iterating `model.generate()`'s
tokens one at a time and only *buffering* them before this — the token-by-token JNI callback was
never the bottleneck), and `invoke()` is now implemented in terms of it rather than duplicating the
generation loop. `EngineHttpServer.streamChatCompletions` calls `invokeStreaming()` directly instead
of chopping an already-complete response. Verified against the real llama.cpp binding via the
checked-in ROT13 GGUF fixture (`engine/modelruntime`'s `GgufRot13FixtureTest`), not just mocked —
`invokeStreaming()` now has its own passing test alongside the existing `invoke()` one. Not
verified: the *done when* line's "first SSE frame arrives measurably before generation completes,
on a device with a real model" — that needs an actual multi-token production model and a device,
neither available in this sandbox.

`EngineHttpServer.streamChatCompletions(call, response, modelId)` used to take an **already-complete**
`ModelResponse` and splits `response.text` on whitespace after generation has finished. The SSE
framing is correct and the chunks are well-formed, but time-to-first-token equals full generation
time. On a phone-sized model that is the difference between a chat panel that feels alive and one
that appears frozen.

This is why D-3 says SSE in the SDK is necessary but not sufficient. The fix is token-callback
streaming from the inference backend up through `ModelAdapter`, so `handleChatCompletions` can
emit frames as tokens are produced rather than after.

Scheduled rather than merely documented, because "the SDK has streaming" is not a claim worth
making while the bytes still arrive all at once. It does not block D1 — Dictator's provider
consumes the same SSE either way, and simply gets better when this lands.

*Done when:* first SSE frame arrives measurably before generation completes, verified on a device
with a real model.

### D0 · Dictator toolchain

Forced, not optional: `dictator-kotlin` is on Kotlin 1.9.25 and Java 11, and a Kotlin 2.4.10 AAR
cannot be read by a 1.9.25 compiler. Everything after this phase depends on it.

- Kotlin 1.9.25 → 2.4.10; JVM 11 → 21, matching `jvmToolchain(21)` across `kernel/`, `agent/`,
  and `engine/`.
- Consequences worth naming in advance: Compose moves to the `kotlin("plugin.compose")` Gradle
  plugin; SQLDelight 2.0.1 needs a Kotlin-2.x-compatible release; Hilt 2.50's `kapt` should move
  to KSP.
- `.github/workflows/ci.yml` in Dictator does not build `dictator-kotlin` at all today. Add a
  Gradle job, so this phase's regressions are visible rather than discovered later.

*Done when:* `cd dictator-kotlin && ./gradlew build` passes on Kotlin 2.4.10 / JVM 21, in CI.

### D1 · LLM chat through Engine

One structural constraint drives the design: `dictator-core` is a KMP module with **only** a
`jvm()` target, and the SDK is Android-only. `AidosProvider` therefore cannot live in
`dictator-core`.

- Add a registration seam to `AiProviderFactory`, which is a hardcoded `when` over
  `ModelProvider` today. `ModelProvider.AIDOS` and the seam live in `dictator-core`; the
  implementation lives in `dictator-android` and registers itself at startup.
- `AidosProvider : BaseAiProvider` implements `askInline` against the non-streaming endpoint and
  `chat` against the SDK's SSE flow, mapping to Dictator's existing `AiStreamChunk` vocabulary.
- `ProviderPolicyManager` gains an `aidos` policy: `processingLocations = ["local"]`,
  `dataRetentionDays = 0`, `usesDataForTraining = false` — the shape the existing `ollama` entry
  already uses, distinguished as on-device via Aidos Engine.
- Check that `SensitiveDataDetector` and the privacy-approval path do not raise cloud-egress
  warnings for a provider that never leaves the device. A local provider tripping a "this will be
  sent to a third party" dialog would be both wrong and training users to dismiss the warning
  that matters.
- Settings surface for the degradation states from S2: not installed (offer install), pending
  approval (deep link to ConnectedAppsScreen), incompatible version, Engine present and ready.
  Falls back to the user's configured provider, per RFC-0103's Degradation section.

*Done when:* a user selects Aidos as their provider in Dictator, dictation-mode AI and the chat
panel both work against a real Engine, and removing Engine falls back cleanly instead of erroring.

### D2 · Offline dictation (STT)

Opt-in, alongside `SpeechRecognizer` rather than replacing it. The reason is a real UX difference,
not caution: `AndroidVoiceServiceImpl` uses `android.speech.SpeechRecognizer`, which delivers
continuous partial results, and Dictator's trigger-phrase UX and text-to-cursor flow are built on
that. Engine's `/v1/audio/transcriptions` is utterance-at-a-time — no partials.

- Audio capture via `AudioRecord` with utterance segmentation, buffered to WAV and posted to
  Engine.
- Surfaced as an "offline dictation" mode with its tradeoff stated in the UI: no partial results,
  higher per-utterance latency, and nothing leaves the device.
- The privacy argument is the strongest in this plan. On most devices `SpeechRecognizer` is
  cloud-backed, so a privacy-first editor for a church deployment currently ships its users'
  dictated speech off-device by default. This phase is what makes Dictator's privacy claims true
  of its primary input path.

*Done when:* a user can dictate a document in airplane mode with Engine installed.

## Sequencing

```
S0 ─┬─► S1 ─────────────┐
    │                   ├─► D1 ─► D2
    └─► S2 ─► S3 ───────┘
                S4 (parallel; improves D1, blocks nothing)

D0 ────────────────────► (prerequisite for D1)
```

S0 unblocks everything. S1 and S2 are independent of each other and can run in parallel. D0 is
independent of all Aidos work and can start immediately — it is the long pole on the Dictator
side, so starting it early is worth more than sequencing it neatly.

## Risks

**D0 is the riskiest phase.** A Kotlin 1.9 → 2.4 jump across Compose, SQLDelight, and Hilt is
where this plan is most likely to lose time, and it is a prerequisite rather than something that
can be deferred. It also touches every Kotlin module in Dictator, so its blast radius is the whole
Android port. Mitigation: do it first, in its own PR, with CI added in the same PR.

**Engine's capability list must become real before D1 is testable.** Folded into S1 for that
reason.

**Approval UX across two apps is easy to get subtly wrong.** The first-run path — Dictator
installed, Engine installed, no approval yet — crosses an app boundary via a `PendingIntent` and
needs to be walked on a real device, not reasoned about.

**Dictator's web app is out of scope by construction**, and this should not be re-litigated later:
the SDK reaches Engine over `127.0.0.1` on the same device, which a Next.js server cannot do.
`lib/ai/providers/` is unaffected by every phase here.

**~~`engine/androidapp`'s `jvmTest` cannot currently exercise `EngineHttpServer` at all~~ — fixed.**
Originally found while verifying S4: `EngineHttpServer`, `TokenManager`, and the OpenAI-shaped
request/response types (`OpenAiSchema.kt`) were declared only in `androidMain`, and `jvm()`/
`androidTarget()` are separate KMP compilation targets, so `jvmTest` couldn't see them regardless
of dependency wiring — confirmed by a clean-baseline `git stash` comparison at the time. Fixed the
same shape as `sdk/client`'s split: `EngineHttpServer`, `OpenAiSchema.kt`, `TokenManager`, and
`AppApprovalStore` (interface + `AppApprovalRecord`/`AppApprovalStatus` — none of it Android-only)
moved into a new `jvmAndAndroidMain` source set; `AppApprovalManager` and
`EncryptedAppApprovalStore` (genuinely Android-only: `PendingIntent`, `Context`,
`EncryptedSharedPreferences`) stayed in `androidMain`, which now `dependsOn` the shared source set.
`UiModels.kt` moved too — its two Compose imports (`Color`, `MaterialTheme`) turned out to be
unused, so the whole file was already portable.

Doing the move surfaced real, previously-uncaught bugs, exactly because this code had never
compiled on any target before: `EngineHttpServer` read `ToolCallOutput.toolCall`, a property that
doesn't exist (kernel's field is `call`); the module never applied
`kotlin("plugin.serialization")` despite `@Serializable` classes needing it (a runtime
`SerializationException` waiting to happen, not a compile error, so nothing had caught it either);
and both `EngineHttpServerTest.kt` and `HttpModelClientSerializationTest.kt`'s older tests asserted
behavior and API shapes (`ChatCompletionResponse.firstContent`, a `Message` class, a
"model name is required" 400 Engine doesn't actually send) that never matched the real code. All
fixed; the scaffold-style tests that hand-rolled Ktor routing instead of testing
`EngineHttpServer` directly were rewritten to use it via a new `EngineHttpServer.installInto()`.
`gradle :androidapp:jvmTest` from `engine/` is green: 46 tests, 0 failures — this was the same
suite reported red on `main` at the top of this document.
