package fi.italeino.aidos.engine.ui

import dev.aidos.kernel.ModelKind
import fi.italeino.aidos.engine.EngineState
import fi.italeino.aidos.engine.suggestions.SuggestedModel
import dev.aidos.tcr.engine.ContextFitRow as TcrContextFitRow
import dev.aidos.tcr.engine.CookbookModel as TcrCookbookModel
import dev.aidos.tcr.engine.EngineState as TcrEngineState
import dev.aidos.tcr.engine.ModelDetail as TcrModelDetail
import dev.aidos.tcr.engine.ModelDetailState as TcrModelDetailState
import dev.aidos.tcr.engine.ModelFitVerdict as TcrModelFitVerdict
import dev.aidos.tcr.engine.ModelKind as TcrModelKind
import dev.aidos.tcr.engine.ModelLoadingState as TcrModelLoadingState
import dev.aidos.tcr.engine.ModelLoadingStatus as TcrModelLoadingStatus
import dev.aidos.tcr.engine.ModelSearchFilters as TcrModelSearchFilters
import dev.aidos.tcr.engine.ProviderSummary as TcrProviderSummary
import dev.aidos.tcr.engine.RunSettingsUi as TcrRunSettingsUi
import dev.aidos.tcr.engine.SelfTestOutcome as TcrSelfTestOutcome
import dev.aidos.tcr.engine.SuggestedModel as TcrSuggestedModel
import dev.aidos.tcr.engine.SuggestionUi as TcrSuggestionUi

// The TCR cannot depend on :enginehost or the kernel, so it keeps presentation copies of the Models
// types (D46 amended). The hosts keep their own types; these map at the UI boundary. Enums map by
// name, so a constant added on one side fails ModelsTcrMappingTest instead of failing at runtime.

internal fun EngineState.toTcr() = TcrEngineState.valueOf(name)

internal fun ModelKind.toTcr() = TcrModelKind.valueOf(name)

internal fun TcrModelKind.toHost() = ModelKind.valueOf(name)

internal fun ModelFitVerdict.toTcr() = TcrModelFitVerdict.valueOf(name)

internal fun CookbookModel.toTcr() = TcrCookbookModel(
    id = id, name = name, kind = kind, quantization = quantization, sizeBytes = sizeBytes,
    contextLength = contextLength, fitVerdict = fitVerdict.toTcr(), isRunnable = isRunnable,
    tokensPerSecond = tokensPerSecond, estimatedVramMB = estimatedVramMB,
)

internal fun SuggestedModel.toTcr() = TcrSuggestedModel(
    id = id, name = name, description = description, repoId = repoId, filename = filename,
    format = format, backend = backend, approxSizeBytes = approxSizeBytes, runnable = runnable,
)

internal fun SuggestionUi.toTcr() = TcrSuggestionUi(
    model = model.toTcr(), isInstalled = isInstalled, isInstalling = isInstalling,
    progressPercent = progressPercent, error = error,
)

internal fun ProviderSummary.toTcr() = TcrProviderSummary(id, name, statusText, isEnabled, isConfigured, modelCount)

internal fun TcrModelSearchFilters.toHost() = ModelSearchFilters(minSizeMb, maxSizeMb, fitsDeviceOnly, runnableOnly)

internal fun RunSettingsUi.toTcr() = TcrRunSettingsUi(
    contextLength = contextLength, threads = threads, batchSize = batchSize, gpuLayers = gpuLayers,
    temperature = temperature, topP = topP, topK = topK, useMmap = useMmap,
)

internal fun TcrRunSettingsUi.toHost() = RunSettingsUi(
    contextLength = contextLength, threads = threads, batchSize = batchSize, gpuLayers = gpuLayers,
    temperature = temperature, topP = topP, topK = topK, useMmap = useMmap,
)

internal fun ContextFitRow.toTcr() = TcrContextFitRow(
    contextLength = contextLength, verdict = verdict.toTcr(), estimatedMemoryMB = estimatedMemoryMB,
    baselineVerdict = baselineVerdict?.toTcr(),
)

internal fun ModelDetail.toTcr() = TcrModelDetail(
    id = id, name = name, description = description, providerName = providerName, kind = kind.toTcr(),
    licenseName = licenseName, modelUrl = modelUrl, sizeMB = sizeMB,
    contextFitTable = contextFitTable.map { it.toTcr() }, isRunnable = isRunnable, format = format,
    deviceTotalRamMB = deviceTotalRamMB, deviceFreeRamMB = deviceFreeRamMB, baselineFreeRamMB = baselineFreeRamMB,
)

internal fun SelfTestOutcome.toTcr() = TcrSelfTestOutcome(
    passed = passed, summary = title, lines = lines, durationMs = durationMs, generatedTokens = generatedTokens,
)

internal fun ModelLoadingState.toTcr() = TcrModelLoadingState(
    modelId = modelId, status = TcrModelLoadingStatus.valueOf(status.name), loadProgress = loadProgress,
    estimatedMemoryMB = estimatedMemoryMB, error = error, loadTimeMs = loadTimeMs,
)

internal fun ModelDetailState.toTcr() = TcrModelDetailState(
    model = model?.toTcr(), isInstalled = isInstalled, installedPath = installedPath,
    installedSizeBytes = installedSizeBytes, installedDigest = installedDigest, isDownloading = isDownloading,
    downloadProgress = downloadProgress, downloadError = downloadError, embeddingInput = embeddingInput,
    embeddingVector = embeddingVector, isEmbeddingTesting = isEmbeddingTesting, embeddingError = embeddingError,
    selfTestText = selfTestText, selfTestFile = selfTestFile, isSelfTesting = isSelfTesting,
    selfTestOutcome = selfTestOutcome?.toTcr(), runSettings = runSettings.toTcr(),
    loadedRunSettings = loadedRunSettings?.toTcr(), isLoading = isLoading, error = error,
)
