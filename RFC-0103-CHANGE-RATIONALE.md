# Rationale for Revisiting RFC-0103

**Purpose:** Record the architectural reasoning for revising [RFC-0103: Aidos Engine](docs/rfcs/0103-aidos-engine.md). This document is a proposal and rationale, not itself an amendment to the accepted RFC.

## Summary of the proposed change

Keep **Aidos Engine** and the **Aidos Agent runtime** as separate, independently designed modules with clear contracts, but reconsider whether they need to be hosted by two separate Android applications and two separate persistent foreground services.

The preferred mobile direction is:

- One always-on Android host application, initially Aidos Engine.
- One persistent host foreground service coordinating the Engine Core and Agent Runtime modules.
- Separate module boundaries, APIs, and authorization for inference versus agent execution.
- Other applications can continue to act as clients of the host through explicit, authenticated APIs.
- Desktop and headless deployments remain free to host the modules in separate processes or services where that is useful.

This changes the **Android hosting and lifecycle boundary**, not the conceptual separation between inference and agent execution.

## Why revisit the current decision?

RFC-0103 correctly identifies an important problem: local models should not be independently downloaded, loaded, and held in memory by every app that needs inference. A shared Engine service addresses that problem and should remain.

However, splitting Engine and Agent into separate Android apps also creates a second application and persistent-service lifecycle to operate. If the device is expected to keep agent work running after the initiating UI closes, both applications/services need lifecycle, startup, notification, recovery, and resource coordination decisions. That is extra operational complexity on a resource-constrained mobile device.

The question is therefore not whether Engine and Agent should be modular. They should. The question is whether their mobile hosting needs to be split across separate installed apps.

## Reasons to prefer one persistent host on Android

### 1. Avoid duplicate always-on infrastructure

A single host can own the persistent foreground-service lifecycle, notification, startup and shutdown coordination, health state, and recovery orchestration. We avoid maintaining two independent always-on application lifecycles for components that together provide the device's local AI runtime.

This does **not** mean combining the Engine and Agent code into one undifferentiated module. It means sharing the Android host infrastructure while keeping module responsibilities explicit.

### 2. Let work outlive the initiating UI

Agent execution should not depend on the UI that started it remaining open. A durable job can continue under the host service when the initiating screen or client disconnects, subject to Android's background-execution rules and the user's settings.

The host can coordinate inference requests and agent jobs within one lifecycle. Durable state and checkpoints are still required; process survival must never be treated as guaranteed.

### 3. Coordinate scarce resources in one place

Inference and agent execution are related workloads. A common host can coordinate model access, memory pressure, concurrency, cancellation, and recovery without relying on two independently managed foreground services to agree about device state.

Engine remains responsible for model loading and inference. Agent remains responsible for sessions, execution graphs, tool use, policies, and checkpoints. Shared hosting enables coordination; it does not transfer those responsibilities between modules.

### 4. Keep client access possible

The original multi-client motivation for RFC-0103 remains valid. Other applications should still be able to request permitted inference from the shared Engine, rather than bundling and loading their own copy of local models.

A client that is authorized to use inference must not automatically gain the ability to start arbitrary agent jobs, invoke tools, access agent state, or exercise the user's capabilities. These are distinct authorities and require distinct API surfaces and authorization decisions.

### 5. Reduce lifecycle and recovery complexity

A single host can make one component responsible for deciding what should resume after process death or device restart. The implementation still needs durable job state, idempotent recovery, explicit failure states, and a clear policy for interrupted tool effects. A shared host simplifies coordination but does not eliminate these engineering requirements.

## Boundaries that must remain separate

A single Android app must not become an excuse to collapse security or architectural boundaries.

- **Engine Core:** model acquisition/loading, inference backends, model memory, and inference-serving contracts.
- **Agent Runtime:** sessions, projects, agent loop, execution graph, tool orchestration, capability checks, durable jobs, and agent state.
- **Android host:** foreground-service lifecycle, notifications, process-level orchestration, startup/recovery coordination, and platform integration.
- **Client APIs:** authenticated inference access and agent/job access remain distinguishable. Internal in-process calls should use explicit module interfaces rather than bypassing policy.
- **Authorization:** permission to call a model is not permission to execute tools or act as an agent. External clients should receive only the authority explicitly granted to them.
- **Platform portability:** Engine Core and Agent Runtime should remain independently testable and should not acquire Android dependencies merely because they share an Android host.

The initial implementation can use one foreground service to coordinate both modules. A second Android service inside the same package remains an option if Android lifecycle or isolation requirements justify it; it should not be added merely to preserve a one-service-per-module analogy.

## Costs and risks

### Larger failure domain

If both modules share one process, a fatal host/process failure can interrupt inference and agent execution together. Mitigate this with durable state, checkpoints, health monitoring, bounded recovery, and tests for process death and interrupted work. If evidence later shows that process isolation is necessary, the module boundaries should make splitting the processes possible.

### Coupled deployment

Shipping both runtimes through one Android host means their mobile releases may be coupled. Keep module APIs and versioning explicit so that this deployment choice does not turn into tight source-level coupling. Independent release units can be reconsidered if there is a demonstrated need.

### Android does not promise indefinite background execution

A foreground service is user-visible and subject to Android's service-start restrictions, resource management, and platform-version-specific rules. A single service reduces duplicated lifecycle machinery; it does not guarantee that work will run forever or silently under all conditions. The implementation must follow current Android requirements and communicate meaningful ongoing work to the user.

### Trust boundaries can become easier to bypass accidentally

In-process calls do not automatically cross an OS-enforced application boundary. Therefore, the implementation must preserve authorization checks at the module/API boundary and must not treat shared process identity as user consent. Tests should verify that inference-only clients cannot invoke agent operations or tools.

## Alternatives considered

| Option | Advantages | Costs |
|---|---|---|
| Separate Engine and Agent apps, each with its own persistent service | Stronger OS-level app/process separation; independent app lifecycle and updates | Two always-on lifecycles to coordinate; more service and recovery complexity |
| One Android host app and one coordinating foreground service, with separate modules | Shared lifecycle and resource coordination; fewer persistent components; clear internal contracts remain possible | Larger failure domain; mobile deployment is more coupled; internal authorization must be enforced deliberately |
| One app but multiple services/processes | Shared package and installation with some lifecycle/process separation | More moving parts; should be justified by isolation or platform needs rather than added by default |

The second option is the preferred starting point for Android. The third remains available if implementation evidence shows that separate services or processes are needed.

## What should not change

This proposal does not reverse the core reasons for RFC-0103:

- Local inference should be shared across clients where appropriate.
- Aidos SDK should remain the canonical client implementation for the Engine's external inference handshake and transport.
- Engine's external inference endpoint remains authenticated and loopback-only unless a separate design explicitly authorizes another exposure.
- Model loading and inference remain separate from agent sessions and tool authority.
- Desktop and headless hosting are not required to mimic Android's application structure.
- Agent jobs need durable state and recovery semantics independent of whether modules share a process.

## RFC follow-up required

If this direction is accepted, update the relevant documents together rather than leaving contradictory descriptions:

1. **RFC-0103:** revise the “Two apps, one device” premise and distinguish the Engine's shared inference API from the host's internal Agent Runtime hosting.
2. **RFC-0050 and RFC-0055:** update mobile hosting/lifecycle statements that assume the Agent Runtime must be hosted by the separate Aidos Agent app, while preserving the runtime's responsibilities and platform-independent contracts.
3. **ARCHITECTURE.md:** replace the claim that mobile necessarily consists of two independent runtime-hosting apps with the selected host/module arrangement.
4. **Implementation and security tests:** cover process death, job recovery, UI/client disconnection, inference-only client authorization, agent/tool authorization, and Android foreground-service compliance.

Do not change these documents merely to imply that the proposal is already accepted. First record the decision, then update the accepted RFCs and architecture map consistently.

## Recommendation

**Use one persistent Android host application and start with one coordinating foreground service, while keeping Engine Core and Agent Runtime as independent modules with separate contracts and authorization boundaries.**

This preserves the most valuable part of RFC-0103—the shared local inference service—without assuming that separate installed apps and separate persistent services are necessary to achieve it. Revisit process or service separation only when there is a concrete isolation, reliability, or lifecycle requirement that justifies the additional complexity.


## Cross-application tools: invoke a client-owned service without opening its UI

A further consequence of this hosting decision is that **the Agent Runtime does not need to live in the same app as every tool it can use**. For example, Agentic Messenger should retain ownership of message data and its message-reading tools. Aidos can orchestrate the agent loop while asking Messenger to execute an explicitly authorized tool through an Android inter-process communication (IPC) contract.

### Can Android start an app whose UI is closed?

Generally, an Android app can communicate with a service in another installed app without launching that app's Activity or displaying its UI. If the provider app declares an appropriate service and the caller is allowed to bind to it, Android can create the provider process to handle the binding even when the provider process was not already running. The provider's UI does not have to be opened just to service that IPC request.

However, this is **not an unconditional guarantee** that Aidos can silently start any app or run arbitrary work whenever it wants:

- The provider must be installed, enabled, and expose a suitable service. An app's ordinary, non-exported internal service is not callable by unrelated apps.
- Android background-execution and foreground-service restrictions still apply, and the exact behaviour depends on the Android version and circumstances. Long-running work may need its own compliant foreground-service design and user-visible notification.
- If the user force-stops the provider app, Android can prevent its components from running until the app is explicitly launched again. A disabled, uninstalled, crashed, or otherwise unavailable provider must be treated as unavailable.
- Package visibility and component-discovery rules can affect how the caller finds the provider. Prefer an explicit, documented component/contract rather than broad package scanning.
- A successful bind or IPC call does not itself constitute user consent to perform every possible operation. Authorization must be checked at the tool boundary.

Therefore, the correct promise is: **Aidos can request an available, authorized client-owned tool without opening the client's UI, when Android permits the service interaction. It cannot guarantee that every client app can always be started or that every tool can run silently in the background.**

### Proposed tool-provider pattern

1. **The provider owns the tool and its data.** Messenger defines and implements tools that read or act on Messenger data. Aidos does not copy the implementation or gain direct access to Messenger's private database.
2. **Aidos owns orchestration.** The Agent Runtime validates the model's proposed tool call against the current job, user grants, and policy, then routes the request to the registered provider.
3. **Use a narrow Android IPC contract.** A provider may expose a bound service with a versioned Binder interface (or another deliberately selected Android IPC mechanism). Define tool discovery/registration, request and response schemas, cancellation, deadlines, error codes, and API-version negotiation. Aidos SDK or a dedicated provider SDK can supply shared contract types and client helpers.
4. **Secure the provider boundary.** Use explicit component addressing, verify caller identity where available, and require a deliberate authorization/grant model. Export only the intended service surface; do not expose a general-purpose command or arbitrary-code interface. Do not rely on package names alone as proof of identity.
5. **Treat availability as temporary.** If the provider is not installed, cannot be bound, is force-stopped, disconnects, or times out, mark the tool unavailable or the job waiting/failed according to policy. Persist the execution state and resume only when safe and appropriate.
6. **Make retries safe.** Tool calls that change state need request identifiers or idempotency semantics so a lost response does not cause an action to execute twice. Do not automatically replay side-effecting calls unless the provider can establish that doing so is safe.
7. **Keep user interaction explicit where needed.** A tool may be able to return data without showing UI, but operations that require confirmation, authentication, or a foreground interaction must request that interaction rather than trying to bypass it.

This arrangement supports a hybrid tool system: built-in tools can run in-process where appropriate; app-owned tools can be invoked over IPC; remote tools can use their own authorized transport. The model sees a logical tool contract, while the runtime decides which authorized provider can fulfil it. Tool routing must not become a way to bypass the owning app's security model.

### Implications for the RFC revision

When revising RFC-0103 and the agent/tool RFCs, specify the difference between **inference clients** and **tool providers**. A client permitted to use the shared model service is not automatically an agent host or a tool provider. Conversely, a provider that exposes a tool does not need to host the agent loop or duplicate the local model runtime.

Add tests for a provider whose process is not running, provider bind failure/disconnection, denied grants, incompatible contract versions, force-stop/unavailability, cancellation, deadlines, and safe retry of side-effecting tools. Validate the exact service-start and foreground-service behaviour against the minimum and target Android versions used by the project before promising background execution guarantees.
