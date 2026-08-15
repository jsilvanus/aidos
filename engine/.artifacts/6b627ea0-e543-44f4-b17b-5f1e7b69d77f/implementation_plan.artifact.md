# Fix Crashes: Foreground Service Permissions and Compose Layout Constraints

This plan fixes two fatal crashes: a `SecurityException` when starting the Engine service and an `IllegalStateException` due to infinite height constraints in the Models UI.

## User Review Required

> [!IMPORTANT]
> This change updates how the foreground service is started to comply with Android 14 (API 34) requirements. It also refactors the UI layout to ensure all scrollable components have finite height constraints.

## Proposed Changes

### Android Core & Service

#### [MODIFY] [EngineService.kt](file:///D:/aidos/engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/EngineService.kt)
- Update `onStartCommand` to call `startForeground` with `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` on API 34+ to fix the `SecurityException`.
- Add the necessary imports for `ServiceInfo`.

### UI & Layout

#### [MODIFY] [MainActivity.kt](file:///D:/aidos/engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/MainActivity.kt)
- Ensure the root `Scaffold` and `Surface` provide strict constraints to the `NavHost`.

#### [MODIFY] [HomeScreen.kt](file:///D:/aidos/engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/HomeScreen.kt)
- Remove redundant `Scaffold` and `Column` nesting. Apply `Modifier.fillMaxSize()` directly to the `LazyColumn` inside `StatusPane` to ensure it respects the parent constraints.

#### [MODIFY] [ModelsScreen.kt](file:///D:/aidos/engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/ui/ModelsScreen.kt)
- Add `Modifier.fillMaxSize()` to the `Scaffold` in `ModelsScreen`.
- In `CookbookPane`, update the `LazyColumn` to use `Modifier.weight(1f)` instead of `fillMaxSize()` when inside a `Column` with other elements (SearchBar, LazyRow). This ensures the `LazyColumn` only takes the remaining space and receives finite constraints.
- Update the bottom button text from "Add Custom Repo" to "Add from Hugging Face" to match the intended UI design.

## Verification Plan

### Automated Tests
- Build the project using `./gradlew :androidapp:assembleDebug` to ensure no regression in compilation.

### Manual Verification
1. Deploy the app to a device running Android 14+ (API 34+).
2. Tap the **ON** button on the Home screen to start the Engine. Verify it no longer crashes with `SecurityException`.
3. Navigate to the **Models** tab and select **Cookbook**. Verify the list renders correctly without crashing the UI thread.
