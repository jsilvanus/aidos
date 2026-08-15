# Implementation Plan - Hugging Face Cookbook

Implement a filterable, verdict-oriented browsing tool for Hugging Face models. The tool will allow users to search for models, filter them based on context window, VRAM requirements, and model type, and see a "verdict" on how well each model fits their specific device.

## User Review Required

> [!IMPORTANT]
> The VRAM filter will be based on the device's available RAM, as most Android devices share memory between CPU and GPU.
> Currently, only GGUF models are supported as per user request.

## Proposed Changes

### [Component] Hugging Face Integration

#### [MODIFY] [HuggingFaceClient.kt](file:///D:/aidos/engine/huggingface/src/commonMain/kotlin/dev/aidos/huggingface/HuggingFaceClient.kt)
- Enhance `HuggingFaceModel` to include metadata for context length.
- Update `search` to fetch more details if possible, or provide a way to batch fetch metadata.
- Ensure tags are correctly parsed for filtering.

### [Component] Cookbook Engine

#### [MODIFY] [Cookbook.kt](file:///D:/aidos/engine/cookbook/src/commonMain/kotlin/dev/aidos/cookbook/Cookbook.kt)
- Add `HardwareProfile` helper to accurately estimate available memory on Android.
- Refine `CookbookVerdict` logic to handle varying context windows more dynamically.

### [Component] Model Browser Logic

#### [MODIFY] [ModelBrowser.kt](file:///D:/aidos/engine/models/src/commonMain/kotlin/dev/aidos/models/ModelBrowser.kt)
- Integrate `HuggingFaceClient` into `ModelBrowser`.
- Implement `searchRemote` with support for `CookbookFilter`.
- Add logic to compute verdicts for remote models before displaying them.

#### [MODIFY] [build.gradle.kts](file:///D:/aidos/engine/models/build.gradle.kts)
- Add `:huggingface` dependency.

### [Component] Android UI

#### [MODIFY] [ModelsViewModel.kt](file:///D:/aidos/engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ModelsViewModel.kt)
- Add `searchRemote` and `updateFilters` methods.
- Integrate `ModelBrowser` with remote capabilities.
- Fetch `DeviceProfile` from the Android system.

#### [MODIFY] [ModelsScreen.kt](file:///D:/aidos/engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ModelsScreen.kt)
- Update `CookbookPane` to include a filter bar for Context, VRAM, and Model Kind.
- Improve `CookbookModelCard` to display the verdict prominently (e.g., color-coded badge).
- Add "Hardware Verdict" summary at the top of the cookbook.

#### [NEW] [DeviceProfileProvider.kt](file:///D:/aidos/engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/DeviceProfileProvider.kt)
- Implement a provider that uses `ActivityManager.MemoryInfo` to populate `DeviceProfile`.

## Verification Plan

### Automated Tests
- Unit tests for `CookbookEngine` with various device profiles and model sizes.
- Integration tests for `ModelBrowser.searchRemote` with mocked `HuggingFaceClient`.

### Manual Verification
1. Open the Cookbook tab in the app.
2. Search for a popular model (e.g., "Llama 3").
3. Apply filters (e.g., "Runs Well", "LLM", "8k context").
4. Verify that the models shown match the filters and show the correct verdict badge.
5. Tap on a model to see the detail view and verify the context fit table.
