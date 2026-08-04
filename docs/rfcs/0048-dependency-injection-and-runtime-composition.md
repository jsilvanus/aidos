# RFC-0048: Dependency Injection and Runtime Composition

Status: Accepted 2026-08-03

## Abstract

This RFC defines how the runtime is assembled: manual constructor injection through a single
composition root, no DI framework, no service locator. It states the dependency rule that keeps
the graph acyclic — **services depend on kernel contracts, never on each other** — and forbids
composition from becoming a way around capability checks.

## Motivation

Composition is where an architecture either holds or quietly dissolves. Three specific risks:

1. **Testability.** The crash-recovery suite (RFC-0038) must construct a runtime with a fake
   model, a virtual clock, seeded IDs, and an injectable filesystem, then kill and rebuild it
   repeatedly. That is impossible if components construct their own dependencies.
2. **Platform variation.** Android and desktop differ in transport, storage paths, background
   execution, and available tools (RFC-0049, RFC-0055). Those differences belong in
   composition, not in `if (isAndroid)` branches scattered through business logic.
3. **The dependency rule.** RFC-0100 identified that engines depending on each other creates
   cycles. A composition root makes the dependency graph explicit and reviewable — you can read
   it in one file — rather than emergent and undiscoverable.

There is a fourth risk that is security-relevant. A DI container that can resolve any service
from anywhere is an ambient-authority mechanism: any code that can reach the container can reach
the Tool Broker or the Capability Manager, and the careful work of RFC-0018 is undone by a
convenience API.

## Goals

1. Define the composition root and the injection style.
2. Define the dependency rule and how it is enforced.
3. Define service lifecycles and scoping.
4. Define platform variation.
5. Define test composition.

## Non-goals

This RFC does not select a DI framework — it declines to use one.
It does not define the plugin host (RFC-0043).

## Design

### Manual constructor injection, one composition root

No framework. No annotations. No reflection. No service locator.

```kotlin
class RuntimeComposition(
    private val platform: PlatformAdapter,
    private val config: RuntimeConfig,
    private val clock: Clock = SystemClock,
    private val idGenerator: IdGenerator = UuidV7Generator,
) {
    // Kernel — constructed first, depends on nothing above it
    private val dispatchers = RuntimeDispatchers(platform.profile)
    private val userStore   = UserStore(platform.userDataDir, dispatchers)
    private val auditLog    = AuditLog(dispatchers, clock, idGenerator)
    private val capabilities = CapabilityManager(userStore, auditLog, clock)
    private val budgets     = BudgetLedger(userStore, clock)
    private val effects     = EffectBroker(capabilities, auditLog, budgets)

    // Services — depend on kernel contracts only
    private val modelRuntime = ModelRuntime(userStore, platform.modelDir, dispatchers)
    private val egress       = EgressClient(capabilities, auditLog, platform.network)
    private val router       = InferenceRouter(modelRuntime, egress, config.routing)

    fun openProject(id: ProjectId): ProjectRuntime = ProjectRuntime(
        store      = ProjectStore(id, dispatchers),
        executor   = Executor(effects, router, capabilities, budgets, clock),
        // ...
    )
}
```

Why no framework: the runtime has on the order of thirty components and one composition site.
A DI container would add a dependency, reflection (which complicates KMP and Android
minification), and — worse — the ability to resolve services implicitly. Manual wiring is
verbose exactly once, in a file whose verbosity is the point: **the dependency graph is
readable**.

The composition root is also where a dependency cycle becomes a compile error rather than a
runtime surprise. You cannot construct `A` before `B` if `A` needs `B` and `B` needs `A`.

### The dependency rule

> **Services depend on kernel contracts. Services never depend on other services.**

Kernel: scopes and identity, state store, audit log, capability manager, effect broker,
execution graph, durable executor, event bus, budget ledger.

Services: projects, sessions, content graph, knowledge, instructions, model runtime, Git,
import/export.

When a service appears to need another — prompt construction needs knowledge results, the
executor needs a model — the dependency is expressed as an **interface owned by the consumer**,
satisfied at the composition root:

```kotlin
// Owned by prompt construction; implemented by the knowledge service.
interface KnowledgeContextProvider {                    // RFC-0025
    suspend fun query(...): List<ContextItem>
}
```

The consumer declares what it needs; the composition root decides who supplies it. Knowledge can
then be replaced, faked, or removed entirely without touching prompt construction — and prompt
construction cannot reach into knowledge internals, because it has never seen them.

Enforcement is a module-structure test (RFC-0038): no service module may import another service
module. Stating the rule without checking it means it survives about three months.

### Lifecycles

Three, and no more:

| Lifecycle | Created | Examples |
|---|---|---|
| **Runtime** | once per process | dispatchers, user store, capability manager, model runtime |
| **Project** | on project open, disposed on close | project store, executor, knowledge index, worktree lock |
| **Run** | per Run, disposed at terminal state | run scope, budget reservation, transcript |

Everything else is a plain object constructed where it is used. There is no singleton registry
and no lazy global.

Project-scope components are disposed deterministically on close: SQLite connections closed,
the lock released (RFC-0055), coroutine scope cancelled. On Android this matters more than on
desktop, because process eviction is routine and a leaked connection means a project that will
not reopen.

### Platform variation

One interface, implemented per target, injected at the root:

```kotlin
interface PlatformAdapter {
    val profile: PlatformProfile             // RFC-0049
    val userDataDir: Path
    val modelDir: Path
    val network: NetworkStack
    val secureStorage: SecureStorage         // Keystore / Keychain / libsecret
    fun availableTools(): List<ToolDescriptor>
    fun executionWindow(): ExecutionWindowPolicy   // RFC-0009 deadline budget
}
```

`availableTools()` is where the shell exists on desktop and does not exist on Android — one
place, expressed as data. `executionWindow()` is where Android's short bursts differ from
desktop's unbounded window, and it is the *only* place: the executor takes a deadline budget and
does not know what platform it is on.

No `expect`/`actual` for business logic. KMP's mechanism is used for genuinely
platform-specific primitives — filesystem, crypto, network — and everything above them is
common code taking an adapter.

### Test composition

```kotlin
fun testRuntime(configure: TestComposition.() -> Unit = {}): RuntimeComposition
```

A test builds the same composition with substituted leaves: `FakeModelAdapter`, `TestClock`,
seeded `IdGenerator`, temp-directory `PlatformAdapter`, injectable-failure filesystem
(RFC-0038).

The kernel is **never** faked. Tests run the real capability manager, the real executor, the
real audit log against a temp database. Faking the kernel would mean testing a system nobody
ships — and the kernel is precisely what the crash-recovery suite exists to verify.

Because composition is manual, `simulateProcessDeath()` is simply: drop the composition, build a
new one over the same directory. There is no container state to reset and no framework to
convince.

## Data Model

None. Composition is code, and deliberately so: a composition described in configuration is a
composition that cannot be type-checked.

## Security

1. **No service locator, no ambient resolution.** A component receives exactly what it was
   constructed with. There is no API by which arbitrary code obtains the Capability Manager or
   the Effect Broker — which would be ambient authority reintroduced through the back door
   (RFC-0018).
2. **The kernel is constructed before services** and passed down. A service cannot substitute a
   kernel component.
3. **No reflection-based instantiation**, so no class-name-driven construction path (RFC-0039).
4. **Plugin and MCP hosts receive a restricted host interface**, never the composition root
   (RFC-0043, RFC-0031). They are capability subjects, not composition participants.
5. **Test hooks are absent from release builds.** `testRuntime` lives in test source sets; there
   is no production flag that swaps in fakes, because a flag that can disable the capability
   manager will eventually be set.

## MVP

1. `RuntimeComposition` with manual constructor injection; kernel then services.
2. The three lifecycles, with deterministic project-scope disposal.
3. `PlatformAdapter` for Android and JVM desktop.
4. Consumer-owned interfaces for every cross-service dependency.
5. `testRuntime` with fake leaves and a real kernel.
6. Module-structure test enforcing the dependency rule.

Not in MVP: runtime feature flags, hot-swapping providers, plugin service injection.

## Future Work

Plugin-provided services, once the plugin host exists — registered at the composition root
behind the same consumer-owned interfaces, never granted access to the root itself.

A generated dependency diagram from the composition root, as living architecture documentation
that cannot drift from the code.
