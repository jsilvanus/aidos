## RFC-0037 Implementation: Observability - Crash Handler and Metrics Collection

### Summary
This commit implements RFC-0037 (Observability) Section "Production Wiring" by:
1. Creating CrashHandler to capture uncaught exceptions
2. Creating MetricsCollector to record runtime metrics
3. Wiring CrashHandler into the daemon's Thread.setDefaultUncaughtExceptionHandler
4. Adding comprehensive unit tests

### Changes Made

#### New Files Created

**Core Implementation (enginehost - shared library):**
- `engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/observability/CrashHandler.kt`
  - Implements Thread.UncaughtExceptionHandler
  - Writes crash records to crash_records table
  - Creates detail files with full stack traces
  - Deduplicates crashes by stack hash (removes line numbers)
  - Stores in-flight Run IDs for debugging context
  - RFC-0037 compliant: no transmission, local storage only

- `engine/enginehost/src/jvmAndAndroidMain/kotlin/fi/italeino/aidos/engine/observability/MetricsCollector.kt`
  - Records metrics to metric_samples table
  - Bucketed by 1-minute windows for aggregation
  - Helper methods for common metric types:
    - recordRunCompletion: run state, duration, attempts
    - recordInference: latency, tokens, memory, local/remote ratio
    - recordModelLoad: load time and size
    - recordStorageUsage: per retention class
    - recordCapabilityDenial: denied operations (RFC-0037 Security §5)
    - recordToolInvocation: tool execution outcome

**Tests (enginehost):**
- `engine/enginehost/src/jvmTest/kotlin/fi/italeino/aidos/engine/observability/CrashHandlerTest.kt`
  - testCrashRecordingWritesDetailFile: verifies stack traces are written
  - testCrashRecordingToDatabase: verifies records are persisted
  - testStackHashDeduplication: verifies repeated crashes have same hash

- `engine/enginehost/src/jvmTest/kotlin/fi/italeino/aidos/engine/observability/MetricsCollectorTest.kt`
  - testMetricSampleWriting: basic metric recording
  - testRunCompletionMetrics: run lifecycle metrics
  - testInferenceMetrics: model inference telemetry
  - testModelLoadMetrics: model lifecycle metrics
  - testCapabilityDenialMetrics: security compliance (RFC-0037 §5)

#### Modified Files

**Daemon Initialization:**
- `engine/desktopapp/src/main/kotlin/fi/italeino/aidos/engine/desktop/Main.kt`
  - Added CrashHandler wiring in main()
  - Thread.setDefaultUncaughtExceptionHandler() called before DesktopEngineHost creation
  - Initializes user database driver for crash_records table
  - Creates crashes/ directory in .aidos/

- `engine/androidapp/src/androidMain/kotlin/fi/italeino/aidos/engine/EngineService.kt`
  - Added crash directory creation in onCreate()
  - Note: Full Android wiring deferred (no user.db access in service context for Phase 1)

**Dependencies:**
- `engine/enginehost/build.gradle.kts`
  - Added `app.cash.sqldelight:runtime:2.0.1` for database access

### RFC-0037 Compliance

✅ **Crash Reporting (RFC-0037 Design - Crash Reporting):**
- Writes crash records locally with:
  - Stack trace hash (for deduplication)
  - Runtime version and platform profile
  - In-flight Run IDs for context
- Creates detail files under .aidos/crashes/
- No transmission (local storage only)
- Previous exception handler is forwarded to (platform crash reporting support)

✅ **Metrics (RFC-0037 Design - Metrics):**
- Run outcomes by state and error class
- Step duration distribution
- Model load time and inference latency
- Storage per project by retention class
- Local versus remote call ratio (captured in isLocalModel parameter)

✅ **Security (RFC-0037 Security):**
- Capability denials logged with reason (not injected content)
- No automatic telemetry
- Redaction point ready (CrashHandler and MetricsCollector write raw data; redaction happens at bundle export)

✅ **Data Model:**
- Matches schema/user.sql: crash_records table
- Matches schema/project.sql: metric_samples table with labels_json and 1-minute bucketing

### Architecture

```
┌─ Main.kt (daemon entry point)
│  └─ Initialize CrashHandler
│     └─ Thread.setDefaultUncaughtExceptionHandler()
│        └─ Uncaught exception → CrashHandler.uncaughtException()
│           ├─ Write to crash_records table (user.db)
│           └─ Write full stack trace to crashes/UUID.txt
│
└─ InferenceRequestManager / EngineHttpServer
   └─ Record metrics via MetricsCollector
      └─ Write to metric_samples table (project.db)
```

### Known Limitations for Phase 1

**Android EngineService:** Crash recording is not yet wired (deferred to Phase 2).
- Reason: EngineService doesn't directly access the user database (it's system-wide).
- Solution: Would require custom exception handler factory or shared crash collector service.

### Testing

All tests use local SQLite databases (JdbcSqliteDriver) to avoid external dependencies.
Tests verify:
1. Database writes with correct schema
2. Detail file creation (CrashHandler)
3. Stack hash deduplication (same exception → same hash)
4. Metric aggregation (multiple metrics per event)

### Future Work (RFC-0037 Future Work)

- Wire MetricsCollector into executor lifecycle (start/completion/model load events)
- Implement TRACE mode (disabled by default, 30-minute auto-disable, separate file)
- Add structured logging with correlation IDs
- Implement diagnostic bundle export with redaction report
- Add OpenTelemetry adapter for desktop (read path over Execution Graph)

### Commits

- Implement RFC-0037: Observability - Crash Handler and Metrics Collection
  - Create CrashHandler for uncaught exception capture
  - Create MetricsCollector for runtime metrics
  - Wire CrashHandler into daemon initialization
  - Add unit tests for both components
  - RFC-0037 compliant: local storage, no telemetry, crash/metric schemas matched
