# RFC-0128: Hardware Acceleration (desktop GPU, mobile GPU and NPU)

Status: Draft (2026-11-16). Not implemented. Planning only: no code accompanies this RFC.
Non-MVP Work: **Remaining**

Related: RFC-0125 (inference backends; D39 says Aidos owns execution constraints and backends own accelerator mechanics), RFC-0022 (local models, run settings, memory admission), RFC-0117 (speech and vision on ONNX Runtime; its item 0117.3 is taken over by this RFC), RFC-0049 (platform profiles), RFC-0103 and RFC-0121 (Engine and API v1, additive only, D63), RFC-0104 (locality), RFC-0105 (inference groups), RFC-0118 (Android host); D36 to D45, D48, D49, D62.

Evidence labels: **[code]** = read in this repository on 2026-11-16; **[sourced]** = read on a vendor or project page on 2026-11-16 (list under Sources); **[known]** = background knowledge, not re-checked; **[unverified]** = believed but must be confirmed before it is built on; **[decision]** = a choice this RFC proposes.

## Abstract

The Engine runs every model on the CPU today. This RFC defines how the Engine finds the accelerators a device has (desktop GPUs, phone GPUs, phone NPUs), how a user or the Engine chooses one per model, how the two runtimes (llama.cpp and ONNX Runtime) are built and configured to use it, and how the Engine falls back to the CPU safely when an accelerator is missing, slower, wrong or crashes.

## Motivation

Local models are only useful when they answer fast enough. On a desktop with a discrete GPU, llama.cpp typically generates several times more tokens per second on the GPU than on the CPU **[known]**. On a phone, Whisper small is the smallest model that is usable for Finnish (RFC-0117), and whether it is fast enough on a mid-range phone is that RFC's first open question.

Three facts about the current code make this more than a build flag:

1. **The GPU setting exists but does nothing.** `ModelRunSettings.gpuLayers` is stored per model, shown in the model detail card, and passed to `ModelParameters.setGpuLayers` by both `LlamaCppAdapter` (desktop) and `AndroidLlamaCppAdapter` **[code]**. The native libraries it reaches are CPU builds: the `de.kherud:llama:4.2.0` jar ships CPU natives for Linux, macOS and Windows, and the only GPU build published for that version is a `cuda12-linux-x86-64` classifier jar **[sourced]**. The Android `libjllama.so` checked in under `engine/androidapp/src/androidMain/jniLibs/arm64-v8a` is a 5 MB library with no GPU backend **[unverified: no build recipe is recorded in the repository]**. A user who sets GPU layers today gets no error and no speed-up.
2. **ONNX models never ask for an accelerator.** `OrtWhisperGraphs`, `OrtVisionRunner` and `OrtDecisionRunner` create `OrtSession.SessionOptions` with an optimisation level and a thread count and nothing else **[code]**. RFC-0117 lists "GPU or NNAPI execution providers" as a non-goal.
3. **The Engine does not know what hardware it has.** `DeviceProfile.hasAccelerator` is hard-coded `false` on both hosts, and `thermalHeadroom` is a placeholder **[code]**.

A note on vocabulary, because the question that started this RFC was "phones do not have a GPU but something else". Phones do have GPUs (Qualcomm Adreno, Arm Mali and Immortalis, Samsung Xclipse, Imagination PowerVR). Most recent phones also have an **NPU**: a separate processor built for neural network arithmetic (Qualcomm Hexagon HTP, MediaTek APU, Google Tensor TPU, Samsung NPU). The two behave very differently for our purposes, which is why this RFC treats them as different device kinds.

## Goals

1. An untouched model keeps working exactly as today. Acceleration can only make a load faster, never make it fail.
2. One user-facing choice per model: Auto, CPU, GPU, NPU. No backend names (CUDA, Vulkan, QNN) in the ordinary UI.
3. The Engine reports what it actually used, per loaded model, with the reason when it fell back.
4. Desktop LLM inference uses a discrete or integrated GPU on Windows and Linux without the user installing a toolkit.
5. Android STT and vision can use the phone's NPU or GPU where the model allows it, proven on a physical device.
6. Memory admission (RFC-0022) stays correct when weights live in video memory.
7. A crashing GPU driver cannot put the Engine in a crash loop.
8. Nothing in API v1 changes except additive fields (D63).

## Non-goals

- New model formats or runtimes (ExecuTorch, LiteRT, MLX, MLC). They are surveyed in `docs/model-formats-and-runtimes.md`; this RFC accelerates the two runtimes the Engine already ships.
- Training or fine-tuning on a GPU.
- Multi-GPU scheduling and splitting one model across several local GPUs. llama.cpp can do it; the Engine will not expose it in this RFC.
- Quantising or converting models on the device.
- Remote GPUs. A home GPU reached over a route is RFC-0108, and a peer's GPU is RFC-0105. This RFC only makes those peers faster once they run it.
- iOS.

## Current state

| Runtime | Host | Accelerator used today | What blocks acceleration |
|---|---|---|---|
| llama.cpp (chat, embeddings) | Desktop JVM | none | CPU-only native library in the binding's jar **[sourced]** |
| llama.cpp | Android | none | CPU-only `libjllama.so` **[unverified]** |
| llama.cpp child processes (RFC-0105 split) | Desktop | none | `llama-rpc-native.yml` builds `llama-server` without a GPU backend; the supervisor already passes `--n-gpu-layers` **[code]** |
| ONNX Runtime 1.22.0 (decisions, STT, vision) | Desktop JVM | none | artifact `onnxruntime` is CPU only; no provider is registered **[code]** **[sourced]** |
| ONNX Runtime 1.22.0 | Android | none | no provider is registered **[code]** |

ONNX Runtime is pinned at 1.22.0 in `engine/stt`, `engine/vision` and `engine/decisions`; Maven Central has 1.32.0 for `onnxruntime`, `onnxruntime_gpu` and `onnxruntime-android`, and 1.29.0 for `onnxruntime-android-qnn` **[sourced]**.

## What each runtime can use

### llama.cpp

llama.cpp selects its accelerator at build time. The backends relevant here **[sourced]**:

| Backend | Build flag | Reaches | Notes |
|---|---|---|---|
| Vulkan | `GGML_VULKAN` | NVIDIA, AMD and Intel GPUs on Windows and Linux; Android GPUs in principle | One binary for all three desktop vendors. Needs only the GPU driver at run time. On Android it is unreliable: a 2026 upstream issue reports corrupted output and regressions on Adreno, and Mali results are poor |
| CUDA | `GGML_CUDA` | NVIDIA only | Fastest on NVIDIA. Needs the CUDA runtime libraries, which are several hundred megabytes **[known]** |
| Metal | on by default on macOS | Apple Silicon and Intel Macs | Whether the binding's macOS native has it enabled is **[unverified]** |
| OpenCL | `GGML_OPENCL` | Qualcomm Adreno 750, 810, 830, 840, X1-85, X2-90; Android on Snapdragon 8 Gen 3 and 8 Elite; Windows 11 on Arm | Written by Qualcomm for Adreno. Supports the common quantisations including Q4_0, Q4_K, Q5_K, Q6_K, Q8_0. Not for Mali |
| SYCL, HIP, OpenVINO, CANN, WebGPU, Hexagon | various | Intel, AMD ROCm, Intel NPU, Ascend, browsers, Snapdragon NPU | Out of scope for the first phases |

Two build options matter for packaging. `GGML_BACKEND_DL` builds each backend as its own dynamic library, so one llama.cpp build can load whichever backends work on the machine. Several backends can also be compiled into one binary. Devices are chosen at run time (`--list-devices`, `--device`, `-ngl`) **[sourced]**.

The Java binding looks for its native library first in the directory named by the system property `de.kherud.llama.lib.path`, then on `java.library.path`, then inside the jar **[sourced]**. The Engine can therefore supply its own GPU-enabled build without forking the binding, provided the build matches the binding's JNI surface.

There is no practical NPU path for GGUF models on phones today. The Hexagon backend exists upstream but is new **[unverified]**.

### ONNX Runtime

ONNX Runtime selects accelerators per session through **execution providers** (EPs). A session lists providers in priority order; the runtime gives each provider the parts of the graph it supports and runs the rest on the CPU **[sourced]**. The Java API exposes `addCUDA`, `addDirectML`, `addNnapi`, `addXnnpack`, `addQnn`, `addCoreML`, `addOpenVINO`, `addTensorrt` and `addWebGPU` on `SessionOptions` **[sourced]**. A method existing does not mean the native library in a given artifact contains the provider.

| Provider | Host | Reaches | Packaging | Constraints |
|---|---|---|---|---|
| CUDA | Desktop | NVIDIA | `com.microsoft.onnxruntime:onnxruntime_gpu`, Windows x64 and Linux x64 **[sourced]** | Needs CUDA and cuDNN installed on the machine **[known]** |
| DirectML | Windows | Any DirectX 12 GPU (NVIDIA, AMD, Intel, Adreno) | No official Maven artifact; official packages are NuGet. A third-party Java build exists **[sourced]** | In sustained engineering: supported, but Microsoft moved new work to Windows ML. Requires memory patterns off and sequential execution **[sourced]** |
| WebGPU | Desktop | Any GPU with a Vulkan, D3D12 or Metal driver | Java method exists; whether the Maven natives include it is **[unverified]** | Newer provider; operator coverage to be measured |
| XNNPACK | Android, desktop | CPU (optimised kernels) | In `onnxruntime-android` **[unverified]** | Not an accelerator, but often faster than the default CPU provider on Arm |
| NNAPI | Android 8.1+ | Whatever the vendor driver offers: GPU, DSP, NPU | In `onnxruntime-android` **[sourced: "published on Maven"]** | Deprecated by Google in Android 15, still present. Limited operator set, constant-input requirements, quality varies by vendor **[sourced]** |
| QNN | Android and Windows on Snapdragon | Hexagon NPU (`htp`), Adreno GPU (`gpu`) | `com.microsoft.onnxruntime:onnxruntime-android-qnn` **[sourced]** | NPU runs only quantised (QDQ, 8 or 16 bit) models with fixed shapes and no loops; the GPU backend takes fp32 and fp16. Can cache the compiled graph as a context binary **[sourced]** |
| Core ML | macOS | Apple GPU and Neural Engine | In the macOS natives **[unverified]** | Out of scope for the first phases |

### Consequences for the models the Engine ships

- **Whisper.** The encoder has one fixed-shape input (30 seconds of log-mel) and no control flow. It is the expensive part of a short utterance and the natural candidate for an NPU or GPU. The merged decoder has dynamic shapes, a cache branch and runs once per token; providers that need fixed shapes cannot take it. **[decision]** A package may run its graphs on different providers: encoder accelerated, decoder on the CPU.
- **SmolVLM.** The same split: the vision encoder is fixed-shape, the decoder is not.
- **Laya decisions.** One encoder-only pass with dynamic sequence length. It can run on a GPU provider; an NPU needs a fixed, padded length and a quantised export.
- **GGUF chat models.** GPU only, through llama.cpp. On a phone this is limited to recent Adreno GPUs.
- **Shared memory.** Integrated GPUs and every phone share system RAM with the GPU **[known]**. Offloading there changes speed, not how much RAM the model needs.

## Design

### 1. Vocabulary (kernel-free types in `engine/modelruntime`)

The kernel is frozen at G0 and gains nothing. The types below live beside `ModelRunSettings`.

```kotlin
/** What the user asks for. AUTO lets the Engine decide; the others are constraints, not hints. */
enum class AccelerationPreference { AUTO, CPU, GPU, NPU }

enum class ComputeKind { CPU, GPU, NPU }

/** One device a runtime can execute on, as reported by a probe. */
data class ComputeDevice(
    val id: String,              // stable within a boot, e.g. "vulkan:0", "qnn:htp", "cpu"
    val kind: ComputeKind,
    val name: String,            // "NVIDIA GeForce RTX 3060", "Adreno 750"
    val vendor: String?,
    val api: String,             // "vulkan", "cuda", "metal", "opencl", "directml", "qnn", "nnapi", "cpu"
    val dedicatedMemoryBytes: Long?,   // null when memory is shared with system RAM
    val sharesSystemMemory: Boolean,
)

/** What a loaded model actually runs on. One entry per graph for multi-graph packages. */
data class AccelerationReport(
    val requested: AccelerationPreference,
    val parts: List<Part>,
    val fallbackReason: String?,       // null when the request was satisfied
) {
    data class Part(val name: String, val device: ComputeDevice, val offloadedLayers: Int? = null, val totalLayers: Int? = null)
}
```

`ModelRunSettings` gains `acceleration: AccelerationPreference? = null` (null = the Engine default, which is `AUTO`). `gpuLayers` stays as the advanced override it already is: null now means "as many as fit" when a GPU is used, and an explicit `0` still forces the CPU. `DeviceProfile.hasAccelerator` is derived from the inventory instead of being a constant.

This follows D39: callers state a constraint in Aidos terms, and each backend turns it into provider mechanics. No `loadWithCuda()` appears anywhere.

### 2. Inventory

Each backend contributes a probe that answers "which devices can you run on, on this machine, with the native libraries present". The Engine merges the answers into one inventory, computed once at start and again when a native pack is installed.

- llama.cpp: load the native library and enumerate backend devices. If the binding does not expose device enumeration, a small JNI shim or `llama-server --list-devices` from the pinned build provides it **[unverified which]**.
- ONNX Runtime: `OrtEnvironment.getAvailableProviders()` says which providers the loaded native library contains **[known]**; device names come from the platform (DXGI adapter list on Windows, `Build.SOC_MODEL` and `SOC_MANUFACTURER` on Android 12+).
- A probe failure is an empty answer, never an Engine failure.

### 3. Selection, verification and fallback

Loading a model with preference `P`:

1. Build the candidate ladder for the model's runtime and kind, most capable first, ending in the CPU. Example for a Whisper encoder on a Snapdragon phone with `AUTO`: QNN HTP (only if the package contains a quantised fixed-shape encoder), QNN GPU, NNAPI, XNNPACK, CPU. Example for a GGUF on Windows: Vulkan device with the most dedicated memory, CPU.
2. Skip candidates the verdict cache marks bad (below) and candidates that cannot hold the model: for a device with dedicated memory, pick the largest `gpuLayers` whose estimated bytes fit in free video memory with a margin; partial offload is allowed for llama.cpp.
3. Try the first remaining candidate. Creating the session or loading the model must succeed, and a short warm-up inference must return finite output. For ONNX graphs the Engine also checks that the provider actually took the heavy nodes: if most of the graph was assigned back to the CPU, the candidate is treated as not accelerated and skipped, because copying tensors to an accelerator for a handful of nodes is usually slower than not using it.
4. On failure, record the verdict and try the next candidate. The CPU is always last and is never skipped.
5. `CPU` means step 1 has one entry. `GPU` and `NPU` remove the other kind from the ladder but keep the CPU at the end: **[decision]** an explicit GPU request that cannot be met loads on the CPU and says so in the report, rather than refusing. A refusal would break goal 1, and the report plus the UI badge make the fallback visible.

**Verdict cache.** Results are remembered per (device id and driver version, runtime version, model digest, provider): `ok` with measured speed, `slower-than-cpu`, `failed` with the error, or `crashed`. An entry expires when the driver or runtime version changes.

**Crash guard.** A faulty GPU driver can kill the process from native code, where no Kotlin `try` can catch it. Before the first attempt on a (device, provider, model) combination the Engine writes an "attempt in progress" marker to disk and removes it after the warm-up. A marker found at start means the previous attempt took the process down: the combination is marked `crashed` and skipped. The user can clear verdicts from Settings.

**Speed check.** `AUTO` should not pick an accelerator that is slower than the CPU, which happens on weak integrated GPUs and with NNAPI drivers. The first successful accelerated load of a model runs a fixed micro-benchmark on both the candidate and the CPU and stores the result; `AUTO` keeps the faster. An explicit `GPU` or `NPU` skips the comparison.

### 4. llama.cpp natives

**[decision]** The Engine builds its own llama.cpp JNI library from one pinned llama.cpp commit in CI, next to the existing `llama-rpc-native` workflow, and loads it through `de.kherud.llama.lib.path`. This is the same conclusion `plans/inference-backend-study.md` reached for split inference: one pinned tag, our own build of the JNI library, `llama-server` and `ggml-rpc-server`. The binding's own jar natives remain the fallback when our build is absent.

**Desktop, phase 1: Vulkan.** One build per OS (Windows x64, Linux x64) with `GGML_VULKAN`, CPU variants included. It covers NVIDIA, AMD and Intel with one binary and needs no vendor toolkit, which is what goal 4 asks for. **[decision]** Vulkan before CUDA: it is the only option that gives every desktop GPU owner acceleration from the installer alone.

**Desktop, later: CUDA native pack.** NVIDIA owners can install an optional pack built with `GGML_CUDA`. It is larger and NVIDIA-only, so it is a download, not part of the installer. With `GGML_BACKEND_DL` the pack is one more backend library beside the Vulkan one, and the ladder prefers CUDA when both are present.

**macOS.** Confirm whether the shipped native uses Metal. If it does, only the inventory and report need work.

**Android.** A `libjllama.so` built with `GGML_OPENCL` for arm64, used only when the inventory shows a supported Adreno GPU. `libOpenCL.so` is a vendor library: on Android 12 and later the manifest must declare it with `uses-native-library` (not required) for the app to load it **[unverified]**. Every other phone keeps the CPU path. Vulkan on Android is not attempted until upstream reports it sound.

**Child processes (RFC-0105).** The split supervisors already pass `--n-gpu-layers`. Once `llama-server` is built with the same backends, the same selection logic supplies the value.

**Native packs.** A native pack is a set of libraries for one (OS, architecture, backend) with a manifest listing each file's SHA-256. Packs that ship with the installer live in the application directory. Downloaded packs go through the existing download manager, are verified against a digest pinned in the Engine build, and are loaded only from a directory the Engine owns. See Security.

### 5. ONNX Runtime providers

A small `OrtAcceleration` helper in a shared source set replaces the three copies of the `SessionOptions` block. It takes a graph role and a candidate device and returns configured options, so `OrtWhisperGraphs`, `OrtVisionRunner` and `OrtDecisionRunner` stop knowing about providers.

**Android.**
- Phase 1: XNNPACK and NNAPI from the artifact already shipped, applied to fixed-shape encoder graphs only. This costs no APK size and tells us, on a real phone, how much is gained.
- Phase 2: QNN on Snapdragon through `onnxruntime-android-qnn`. The GPU backend runs existing fp16 packages. The NPU (HTP) backend needs packages exported with fixed shapes and QDQ quantisation, which is a new package variant produced offline (desktop or CI, with calibration audio). The compiled context binary is cached per device so only the first load pays the compile cost. QNN libraries are Qualcomm's and their redistribution terms must be checked before they ship in the APK **[unverified]**.
- MediaTek and Samsung NPUs are reached only through NNAPI in this RFC.

**Desktop.**
- ONNX models on the desktop are small and already fast on a desktop CPU (RFC-0117 measured Whisper small as usable). Desktop ONNX acceleration is therefore after llama.cpp and after Android.
- When it is built: CUDA through `onnxruntime_gpu` for machines that have CUDA and cuDNN installed, and one vendor-neutral provider for everyone else. **[decision]** Measure WebGPU and DirectML on Windows before choosing; DirectML is the proven one but is in sustained engineering and has no official Java artifact, WebGPU is the direction of travel but newer.

**Version.** Raise ONNX Runtime from 1.22.0 to a current release as the first step of this work, in its own change, so provider behaviour is tested against the version that will ship.

### 6. Memory admission and thermal state

- `GlobalModelRuntime` admits models against a resident-bytes budget (RFC-0022). With a dedicated-memory GPU the estimate splits into host bytes and device bytes, and each is admitted against its own budget. With shared memory nothing changes: all bytes count against RAM.
- Free video memory is read at load time, not assumed from the total, because the desktop compositor, a browser or a game may hold most of it.
- If a load fails for lack of video memory, the Engine retries with fewer layers before falling back to the CPU.
- Android: `DeviceProfile.thermalHeadroom` is filled from `PowerManager` thermal status. Under severe thermal status or below the battery floor, `AUTO` prefers the device that draws less power for the task; the NPU is usually that device, the GPU usually is not **[known]**. Explicit choices are honoured.

### 7. Surfaces

- **Model detail card (both hosts).** "Run on: Auto, CPU, GPU, NPU", with unavailable kinds disabled and a reason. A badge shows what is in use ("GPU, 28 of 32 layers", "NPU encoder, CPU decoder", "CPU: GPU driver crashed on a previous attempt"). GPU layers stays under advanced settings.
- **Settings.** The inventory, the installed native packs, an Engine-wide default preference, and "forget acceleration verdicts".
- **CLI.** `engine devices` prints the inventory; `engine bench <model>` runs the micro-benchmark on every candidate and prints tokens per second or real-time factor. The bench command is also how a tester reports results from hardware the project does not own.
- **API v1 (additive).** The model objects returned by the Engine gain an optional `acceleration` object with the report. No request field is added: where a model runs is the Engine owner's choice, not the calling app's, and an app that could force a GPU could also drain a battery.

### 8. Locality and groups

Acceleration never changes where inference happens. A GPU or NPU in the same device is `local` under RFC-0104. Sharing a GPU with other devices stays the business of RFC-0105 and RFC-0126; a later item lets a helper advertise its accelerator so the split planner can weigh it.

## Data Model

No canonical DDL changes. New persistent state, all in the Engine's own preferences store:

| State | Key | Notes |
|---|---|---|
| Per-model preference | existing run-settings record, new optional field `acceleration` | absent = Engine default |
| Engine default preference | one setting | default `AUTO` |
| Verdict cache | (device id, driver version, runtime version, model digest, provider) to verdict, measured speed, timestamp | bounded; cleared from Settings |
| Attempt marker | one small file per attempt in progress | deleted after warm-up |
| Native pack registry | pack id, version, digest, install path | only Engine-owned directories |

## Security

- **Native code is the risk.** A native pack is executable code loaded into the Engine process. Packs are accepted only when their digest matches one pinned in the Engine build, are stored in a directory only the Engine writes, and are never loaded from a model directory or a user-supplied path. A model package can never bring its own provider library; `registerCustomOpLibrary` is not used.
- **No silent network.** Selecting GPU never downloads anything. Installing a native pack is an explicit user action through the download manager and is subject to the same egress rules as a model download (RFC-0042).
- **Driver faults.** GPU drivers are large, privileged and less robust than the CPU path. The crash guard bounds the damage to one failed start. Moving accelerated llama.cpp inference into the existing child-process supervisor would isolate driver crashes completely and is listed as future work.
- **Vendor libraries.** QNN and CUDA libraries are proprietary. They ship only if their licences allow redistribution, and their notices are added to the third-party licence list.
- **Residual memory.** Video memory is not guaranteed to be zeroed between processes on every driver **[known]**. The Engine frees accelerator memory on unload; it makes no stronger promise, and this RFC does not change the locality classification of any data.
- **No new authority.** Choosing an accelerator grants no capability, opens no port and changes no approval. The `acceleration` report exposes hardware names to apps that can already list models; it contains no identifiers beyond the device name.

## MVP

The smallest version that makes a real difference and proves the design on both hosts:

1. The vocabulary, inventory, selection ladder, verdict cache and crash guard as pure Kotlin with unit tests against fake inventories.
2. Desktop llama.cpp on Vulkan: CI builds the JNI library for Windows x64 and Linux x64 from the pinned commit; the desktop Engine loads it; `AUTO` offloads as many layers as fit; the report shows in the model card and in the API.
3. `engine devices` and `engine bench`.
4. Memory admission split into host and device bytes.
5. Android ONNX phase 1: XNNPACK and NNAPI on the Whisper encoder with fallback, measured on one physical phone, with the numbers recorded in `docs/stt.md`.
6. ONNX Runtime raised to a current release.

Done when: on a Windows desktop with a GPU, a GGUF chat model loads with `AUTO`, the card shows GPU layers, and `engine bench` shows more tokens per second than the CPU; on the same build with the GPU disabled the model loads on the CPU with a stated reason; on a phone, Whisper small transcribes the Finnish test clip with the encoder on an accelerator or reports why not, and the transcript matches the CPU transcript.

## Testing

- CI has no GPU. Selection, fallback, admission and the crash guard are tested with fake devices and fake backends. A software Vulkan driver (lavapipe) on the Linux runner can exercise the real Vulkan build for correctness, not speed **[unverified]**.
- Real-hardware tests are opt-in behind an environment variable, in the style of `AIDOS_STT_MODEL_DIR`, and run on the owner's Windows desktop and phone. A document in the style of `docs/android-fgs-device-test.md` lists the manual steps.
- Correctness on accelerators is checked by comparison: accelerated output must match CPU output within a tolerance (exact transcript for STT, top answers for decisions, a bounded logit difference for chat).

## Open Questions

1. Which GPU does the main desktop have, and which phone is the reference device? The SoC decides whether QNN or NNAPI is the path that matters, and whether the Adreno OpenCL backend applies at all.
2. Should the installer ship the Vulkan natives, or should even those be a download? This RFC proposes shipping them.
3. Build our own JNI library against the `de.kherud` binding, or move to a fork that already publishes CUDA and Vulkan natives? The `net.ladenthin:llama` fork is reported to publish such classifiers **[unverified]**. Our own build keeps one pinned llama.cpp for local and split inference and is what this RFC proposes.
4. Is a refusal ever right for an explicit `GPU` request, for example for an app that needs a latency guarantee? This RFC proposes always falling back.
5. Is an NPU Whisper encoder worth a second, quantised package variant per model, given that fine-tunes from `liturgos-auditor` would need the same export and calibration step?

## Sources

Read 2026-11-16:

- llama.cpp build documentation and OpenCL backend documentation (`docs/build.md`, `docs/backend/OPENCL.md` in `ggml-org/llama.cpp`).
- `kherud/java-llama.cpp` README, and the Maven Central directory for `de.kherud:llama:4.2.0`.
- ONNX Runtime documentation: Java getting started, Java API `OrtSession.SessionOptions`, and the NNAPI, QNN and DirectML execution provider pages.
- Maven Central metadata for `com.microsoft.onnxruntime` artifacts.
- Android NNAPI migration guide (deprecation in Android 15).
- llama.cpp issue 20603 and discussion 9464 (Vulkan on Android).

## Implementation Files

```yaml
files: []
```

## Post-MVP Work

Checklist for work beyond the MVP. Each item has a stable id; set `status` to `partial` or `done` and list the implementing `files` (checked by `scripts/rfc/postmvp.py`).

```yaml
post_mvp:
  - id: "0128.1"
    title: "CUDA native pack for llama.cpp on NVIDIA desktops, installed on request"
    status: todo
    phase: P1
    done_when: "an NVIDIA desktop with the pack installed prefers CUDA over Vulkan and the report says so"
  - id: "0128.2"
    title: "QNN execution provider on Snapdragon phones (Adreno GPU backend) for STT and vision encoders"
    status: todo
    phase: P1
    done_when: "Whisper encoder runs on QNN GPU on a Snapdragon phone and the transcript matches the CPU transcript"
  - id: "0128.3"
    title: "Quantised fixed-shape package variant and QNN HTP (NPU) execution with a cached context binary"
    status: todo
    phase: P2
    depends_on: ["0128.2"]
    done_when: "a QDQ Whisper encoder runs on the Hexagon NPU and a second load reuses the cached context binary"
  - id: "0128.4"
    title: "Adreno OpenCL build of llama.cpp for Android, used only on supported Adreno GPUs"
    status: todo
    phase: P2
    done_when: "a GGUF chat model offloads layers on a Snapdragon 8 Gen 3 or newer phone and other phones stay on the CPU"
  - id: "0128.5"
    title: "Desktop GPU for ONNX models: CUDA through onnxruntime_gpu and one vendor-neutral provider chosen by measurement"
    status: todo
    phase: P2
    done_when: "Whisper and SmolVLM run on a desktop GPU with output matching the CPU"
  - id: "0128.6"
    title: "Metal on macOS confirmed and reported"
    status: todo
    phase: P2
  - id: "0128.7"
    title: "Thermal and battery aware AUTO on Android"
    status: todo
    phase: P2
    done_when: "under severe thermal status AUTO picks the lower-power device and the report states why"
  - id: "0128.8"
    title: "Accelerated llama.cpp in the supervised child process for driver-crash isolation"
    status: todo
    phase: P3
  - id: "0128.9"
    title: "GPU-enabled llama-server and ggml-rpc-server for inference groups, with accelerators advertised in the helper heartbeat"
    status: todo
    phase: P3
  - id: "0128.10"
    title: "Windows on Arm (Snapdragon X): OpenCL for llama.cpp and QNN for ONNX"
    status: todo
    phase: P3
  - id: "0128.11"
    title: "Intel and Apple neural accelerators (OpenVINO, Core ML) for ONNX models"
    status: todo
    phase: P3
  - id: "0128.12"
    title: "MediaTek and Samsung NPUs beyond NNAPI"
    status: todo
    phase: P3
```
