# AIDIN — AIdos Distributed Inference Network

AIDIN is the distributed inference layer of Aidos. It allows multiple Aidos Engine instances to cooperate as a single logical inference system across nearby Android devices, laptops, and other supported hosts.

## Core principle

**No node is the inference. The session is the inference. Nodes are replaceable execution resources.**

A caller should interact with a logical AIDIN session rather than a physical endpoint. The coordinator and execution nodes may change while the session remains the same.

## Architecture

AIDIN is an execution abstraction inside Aidos Engine, not an Android-specific networking feature and not a model-runtime-specific distributed wrapper.

Conceptually:

    Aidos
      |
      +-- Engine
      |     |
      |     +-- Local execution
      |     |
      |     +-- Distributed execution
      |              |
      |              +-- AIDIN
      |
      +-- Agent
      |
      +-- Dictator

The Engine decides how a model is executed; AIDIN provides the distributed execution resources and coordination. Agent and Dictator should not need to know which physical nodes execute an inference.

An AIDIN node is an Aidos Engine instance. Android phones and tablets, KMP desktop/laptop hosts, and future supported Aidos platforms are all first-class nodes.

AIDIN should remain independent of any particular model runtime or accelerator. Local execution may use llama.cpp, ONNX, ExecuTorch, or another backend; AIDIN operates above that backend boundary.

## Execution models

AIDIN should support several execution strategies rather than committing to one form of parallelism.

### Replicated inference

Different requests are assigned to different nodes.

    Node A -> request 1
    Node B -> request 2
    Node C -> request 3

This requires little inter-node traffic and is the simplest distributed mode.

### Pipeline / layer splitting

Different model layers execute on different nodes.

    Node A
      layers 0..7
         |
         v
    Node B
      layers 8..15
         |
         v
    Node C
      layers 16..31

Intermediate activations are transferred between nodes.

### Tensor parallelism

A single layer's tensor computation can be divided among multiple nodes. This is more communication-intensive and should be implemented after the basic AIDIN transport, session, planning, and recovery abstractions are proven.

### Hybrid execution

Pipeline and tensor parallelism may be combined. AIDIN should not assume identical devices or equal partitions.

### Multi-model / distributed capability execution

AIDIN may also coordinate different models or capabilities across nodes, for example:

    Node A -> speech
    Node B -> vision
    Laptop -> large language model
    Node C -> embeddings

This allows AIDIN to become a general local AI execution fabric rather than only a way to split one model.

## Node discovery and pairing

AIDIN should support local discovery of nearby Aidos Engines. Transport and discovery are separate concerns.

Potential local mechanisms include:

- ordinary LAN/Wi-Fi
- Wi-Fi Direct or similar peer-to-peer mechanisms
- Bluetooth LE for discovery and control where appropriate

High-volume inference data should normally use a suitable high-bandwidth IP-based transport rather than Bluetooth.

Discovery should expose enough information for a node to decide whether another node can participate, without making device identity unnecessarily dependent on personal information.

Pairing establishes trust and authenticated node identity before a node is allowed to participate in a cluster.

## Node capabilities and resource state

Nodes should advertise capabilities such as:

- node ID
- Aidos/Engine version
- AIDIN protocol version
- available and total memory
- compute backends and accelerators
- supported models
- supported execution operations
- network characteristics
- current availability
- battery state where relevant
- thermal state where relevant
- current resource pressure

Capabilities are dynamic. A node that was suitable five minutes ago may become unsuitable because of thermal throttling, memory pressure, battery state, or OS restrictions.

## Adaptive execution planning

AIDIN should have an execution planner that turns available node capabilities and network conditions into an execution plan.

For example:

    Model: 14B Q4

    Node A: layers 0..10
    Node B: layers 11..29
    Node C: layers 30..39

The planner should consider:

- available memory
- compute performance
- accelerator availability
- model placement
- network bandwidth
- network latency
- current load
- thermal state
- battery/resource constraints
- expected communication volume
- recovery requirements

The plan should therefore be heterogeneous rather than assuming identical phones.

AIDIN should be able to choose between local execution, replicated execution, pipeline splitting, tensor parallelism, or a hybrid plan according to the available resources.

## Control plane and data plane

AIDIN separates coordination from inference traffic.

### Control plane

The control plane handles:

- cluster membership
- node discovery
- pairing and authentication
- capability negotiation
- coordinator selection
- leases
- execution plans
- plan versions
- health state
- recovery and migration
- session metadata

### Data plane

The data plane handles:

- tensors
- activations
- model data where required
- inference results
- execution messages

Large inference data must not become part of the replicated control state.

## Resilient coordination

AIDIN must not depend on a permanently fixed coordinator.

The coordinator is a temporary role held by one node and protected by a lease. If the coordinator disappears or its lease expires, another eligible node can take over.

The initial coordination design should use:

- **Node IDs** — stable identity for each AIDIN node.
- **Monotonic epochs** — every coordinator generation has a strictly increasing epoch. Messages from an older epoch are stale and must not mutate current cluster state.
- **Leases/timeouts** — coordinator authority expires unless renewed. Missing a single heartbeat should not immediately declare a node dead.
- **Deterministic election** — when a coordinator is lost, eligible nodes select the next coordinator deterministically from the current membership view. Election must produce a new epoch.
- **Authenticated membership messages** — membership and coordination messages must be authenticated so an untrusted node cannot impersonate another node or forge cluster state.

The implementation should distinguish:

    JOINING -> READY -> ACTIVE -> DRAINING -> SUSPECTED -> FAILED/LEFT

A transient network problem should first make a node SUSPECTED; it should not immediately cause topology destruction.

## Cluster identity

An AIDIN cluster has a cluster ID. Nodes have node IDs. Coordination generations have epochs.

Conceptually:

    cluster-id
       |
       +-- epoch 42
             |
             +-- coordinator: node-B
             +-- members: A, B, C

After coordinator failure:

    cluster-id
       |
       +-- epoch 43
             |
             +-- coordinator: node-C

Old epoch messages must be rejected.

## Sessions

An inference is represented by an AIDIN session rather than an endpoint connection.

A session should have at least:

- session ID
- cluster ID
- current epoch
- model identity
- execution plan
- plan version
- execution generation/checkpoint state
- recovery policy

Changing the coordinator or physical endpoint must not change the logical session ID.

The caller therefore sees:

    Agent
      |
      v
    AIDIN Session
      |
      +-- current coordinator
      +-- execution nodes

If the coordinator changes, the logical session continues.

## Execution plans

Execution topology is represented by a versioned execution plan.

Example:

    PLAN 7
      Node A: layers 0..7
      Node B: layers 8..15
      Node C: layers 16..31

If Node B fails:

    PLAN 8
      Node A: layers 0..10
      Node C: layers 11..31

Nodes must not mix state or assumptions from incompatible plan versions.

## Checkpointing and recovery

Distributed inference should support resumable execution where practical. A replacement topology can resume from the latest valid checkpoint instead of necessarily restarting the entire request.

Checkpoint frequency should be adaptive. Checkpointing every layer is not required and may be too expensive.

Recovery policies may include:

- NONE — failure aborts the session.
- RESTART — restart the inference.
- PIPELINE_CHECKPOINT — resume from a pipeline checkpoint.
- FULL_RESUME — resume using sufficient persisted execution state.

## Graceful node migration

A node may become unsuitable because of thermal throttling, low battery, memory pressure, OS restrictions, or degraded network conditions.

A node can enter DRAINING:

1. Stop assigning new work.
2. Finish or safely interrupt current work.
3. Transfer required state.
4. Re-plan execution.
5. Remove the node when safe.

This should be preferred over treating every resource change as a hard failure.

## Network partitions

AIDIN must explicitly account for network partitions.

The first implementation should prefer safety over continuing a split cluster with ambiguous ownership of session state:

- Two partitions must not independently mutate the same session under the same epoch.
- A new coordinator must use a higher epoch.
- Nodes must reject stale coordinator commands.
- A partitioned node should rejoin and reconcile state rather than assuming it remained authoritative.

## Control-state replication

Replicate relatively small control state where useful:

- epoch
- coordinator
- membership
- sessions
- execution plans
- plan versions
- checkpoint metadata

Do not automatically replicate:

- model weights
- every activation
- every tensor

Those belong to the execution/data plane and should be transferred only when required by the execution plan or recovery strategy.

## Endpoint and transport abstraction

Clients should never treat an IP address or socket as the identity of an AIDIN endpoint.

Use a logical node ID with current network addresses, supported transports, and capabilities as attributes.

This allows a node to:

- change Wi-Fi/LAN address
- reconnect
- switch supported local transport
- temporarily disappear and return

without changing its logical identity.

## AIDIN as a local AI fabric

AIDIN should ultimately be capable of turning a group of heterogeneous Aidos devices into one adaptive local AI compute fabric.

For example:

    Android phone A -> speech model
    Android phone B -> vision model
    Laptop          -> large language model
    Android phone C -> embeddings

An Agent can construct a distributed execution graph while AIDIN determines where each operation actually runs.

The same architecture must also support the simpler case where one model is split across several nodes.

AIDIN should not imply cloud infrastructure. The normal local mode is:

    device <-> device <-> device

with no required central cloud service.



## Data locality and privacy

AIDIN is one execution domain available to Aidos Engine; it is not the only one.

An Aidos Engine may choose among:

    Aidos Engine
        |
        +-- Local execution
        +-- AIDIN
        +-- Remote provider AI

AIDIN therefore must not assume that all inference is local or that remote provider AI is inherently forbidden. Instead, execution and data policies determine which execution domains are eligible.

### Data policy

A request may carry a data-locality policy such as:

- **LOCAL_ONLY** — request-derived data must remain on the endpoint node.
- **TRUSTED_NODES** — request-derived data may be sent only to explicitly authorized nodes.
- **TRUSTED_CLUSTER** — request-derived data may be sent to nodes in an authorized AIDIN cluster.

A configured remote provider may also be an explicitly authorized execution domain. Provider authorization and provider-specific data handling are outside the AIDIN node protocol, but Aidos Engine must include them when deciding whether a remote provider is eligible.

Data policy applies not only to the original prompt or input, but also to request-derived intermediate state such as activations, hidden states, KV cache, and generated results.

Transfer and persistence are separate concerns. A request may permit temporary transfer to an eligible node while prohibiting persistent storage there.

### Data classification

Applications or higher-level policy may classify data independently of AIDIN's transport and execution mechanisms. Possible generic classifications include:

    PUBLIC
    INTERNAL
    SENSITIVE
    RESTRICTED

AIDIN should not attempt to determine the legal or organizational meaning of these classifications. The classification is an input to policy evaluation.

### Planner constraint

The execution planner must apply security, privacy, execution, authorization, and resource policies before optimizing a topology.

A topology that violates a request's data policy is not an inferior plan; it is an invalid plan.

Policies therefore constrain the eligible execution domain and nodes from the beginning of planning.

## Model identity, availability, and distribution

AIDIN distributes computation; it does not necessarily need to distribute model files.

A model should have a logical identity independent of where its weights are currently available.

A model description may include:

- model family and identity
- version
- architecture
- variant
- format
- quantization
- supported operations/capabilities
- context or other relevant execution requirements

### Model availability

Different nodes may have different model availability:

    Phone A  -> model X available
    Phone B  -> model X unavailable
    Laptop   -> model X available

AIDIN should be able to construct a plan from the nodes that already have compatible model material.

Model availability is therefore a node capability/resource, not part of the model's logical identity.

### Model variants and compatibility

Nodes may have different compatible representations of a model:

    Model X
      +-- Q4
      +-- Q8
      +-- FP16

AIDIN should represent enough information to determine whether a particular model variant is compatible with a requested execution plan.

Having the same model family name is not sufficient to establish compatibility.

### Optional model distribution

If required model material is missing, AIDIN may eventually support:

- downloading or obtaining model material
- caching
- transferring model material between trusted nodes
- distributing only required partitions
- reusing existing model caches

Model distribution is optional. An implementation may initially require models to be preinstalled on participating nodes.

Model transfer should be treated as a planning cost. A node with excellent compute may still be a poor choice if model transfer is prohibitively expensive.

### Model policy

Model use may have generic policy constraints independent of enterprise ownership.

Possible permissions include:

- **may_execute**
- **may_cache**
- **may_transfer**
- **may_persist**

Open models may permit all of these, while a user or application may impose stricter restrictions. Proprietary or otherwise restricted models are a possible use case, but do not define the AIDIN architecture.

Possessing model material on a node does not by itself establish permission to use, transfer, or redistribute it.

### Model placement

AIDIN should eventually support both replicated and partitioned model placement.

Replicated:

    Node A -> full model
    Node B -> full model
    Node C -> full model

Partitioned:

    Node A -> layers 0..10
    Node B -> layers 11..21
    Node C -> layers 22..31

The first is simpler and may be appropriate when model material fits on each node. The second can reduce memory requirements but requires explicit model partitioning and compatible execution semantics.

AIDIN should not require either placement strategy universally.

### Model and execution domains

Model availability and model placement interact with the broader Aidos Engine execution model:

    Aidos Engine
        |
        +-- local model
        +-- AIDIN-distributed model
        +-- remote provider model

A request may use whichever execution domain satisfies its model, data, resource, and execution policies.

## Endpoint delegation

An AIDIN node may expose a stable local endpoint to applications while delegating execution to an AIDIN cluster.

The endpoint is the stable interface; it does not imply that inference must execute locally.

Conceptually:

    Application
        |
        v
    node endpoint
        |
        +-- local Engine
        |
        +-- AIDIN cluster

The local endpoint and cluster endpoint may be hosted by the same node, or the local endpoint may delegate to another coordinator.

This allows an application such as an IDE to keep a stable endpoint such as a user's phone while execution changes between local inference and a workplace or home cluster.

Endpoint identity must remain independent of the physical network address.

## Execution policy

Execution location should be controllable through explicit policy.

Initial policy modes:

- **LOCAL_ONLY** — inference must execute on this node. No inference delegation is permitted.
- **PREFER_LOCAL** — execute locally when practical; delegation is permitted when local execution is unsuitable.
- **CLUSTER_ALLOWED** — the planner may choose local or trusted-cluster execution.
- **CLUSTER_REQUIRED** — execution must be delegated to an appropriate AIDIN cluster.

Policy may eventually exist at several scopes:

- Aidos/global policy
- application policy
- session policy
- request policy

More restrictive policy should be able to override broader delegation permissions.

A security-sensitive application can therefore force LOCAL_ONLY even when the device is participating in an AIDIN cluster.

Execution policy is also a data-boundary policy: delegated inference may require prompts, model inputs, intermediate state, or other request data to cross the node boundary.

## Trust, authorization, and revocation

Cluster membership must be based on explicit trust rather than network proximity alone.

A discovered node is not automatically an authorized execution node.

AIDIN should distinguish:

- discovery
- authentication
- authorization
- capability advertisement
- active participation

Trust should be revocable. Removing a node from a cluster must prevent it from receiving new work and, where practical, invalidate its ability to participate in future sessions.

Model access should be separable from access to files, tools, credentials, or other device capabilities. A node authorized to contribute inference compute should not thereby gain access to unrelated local resources.

## Resource contribution policy

A node should be able to limit what resources it contributes to AIDIN.

Possible constraints include:

- maximum CPU/GPU/NPU utilization
- maximum memory
- minimum battery level
- charging-only participation
- thermal limits
- time or location/context restrictions
- whether background participation is allowed

The scheduler must treat these as hard policy constraints rather than merely optimization hints.

This is especially relevant for workplace clusters: a company-issued phone may contribute compute during work while preserving explicit limits on battery, thermal load, and user experience.

## Personal and workplace clusters

A node may leave one trusted cluster and join another as its context changes.

For example:

    WORK
      office cluster
        +-- phone
        +-- colleague phones
        +-- office laptop

    HOME
      personal cluster
        +-- same phone
        +-- personal laptop
        +-- home desktop

The node's stable identity does not change merely because cluster membership changes.

Work and personal clusters must remain separate trust domains. Participation in a workplace cluster must not implicitly grant the workplace access to the node's personal cluster, personal sessions, or unrelated local resources.

A workplace cluster may therefore be intentionally ephemeral: available compute grows as workers arrive and shrinks as they leave.

## Audit and observability

Distributed inference should provide enough metadata to understand where work was executed without unnecessarily logging sensitive inference content.

Useful audit information may include:

- session and plan identifiers
- participating node IDs
- plan changes
- node joins/leaves
- authorization and revocation events
- resource-policy decisions
- execution failures and recovery events

Prompt contents, generated content, and intermediate tensors should not be logged merely for observability.


## Further design considerations

The following areas should be considered during implementation. They are deliberately expressed as design constraints and interfaces rather than as a complete protocol specification.

### Capability negotiation

Nodes should advertise capabilities in a structured, versioned form.

Relevant capabilities may include:

- supported execution backends
- supported model architectures and variants
- accelerator types and usable memory
- maximum practical context or tensor sizes
- supported distributed-execution modes
- supported transport protocols
- streaming capabilities
- optional operations such as speech recognition, embeddings, vision, or reranking
- protocol and Engine compatibility

Capability advertisement should distinguish **what a node can theoretically support** from **what it can currently contribute**.

For example, a phone may support a model in principle but currently have insufficient memory or be thermally constrained.

Capability negotiation should therefore combine relatively stable capabilities with dynamic resource state.

### Multi-session scheduling

An AIDIN cluster may serve multiple sessions at the same time.

Scheduling should consider:

- active sessions
- node availability
- memory and accelerator capacity
- latency requirements
- communication cost
- session priority where explicitly configured
- resource contribution policies
- model placement
- fairness and starvation avoidance

A session should not assume exclusive ownership of a node unless its execution policy explicitly requires it.

Scheduling must remain subordinate to execution, data, authorization, and resource policies.

### Streaming and interactive inference

AIDIN should support streaming results where the underlying backend supports them.

Streaming is important for:

- chat generation
- speech recognition
- interactive agents
- token-by-token generation
- low-latency UI applications

The protocol should distinguish:

- request accepted
- execution started
- partial result
- final result
- failure
- cancellation

Transport-level streaming should not expose backend-specific details unnecessarily.

### Cancellation and interruption

Cancellation must be a first-class operation.

A request may be cancelled by:

- the endpoint
- the originating application
- a session owner
- an execution policy
- a node becoming unavailable

Cancellation should propagate through the execution plan so that downstream nodes stop unnecessary work.

Nodes should support cooperative cancellation and cleanup of temporary request state.

AIDIN should also distinguish cancellation from failure. A cancelled request is not necessarily an execution error.

### Energy and resource-aware planning

AIDIN nodes may be battery-powered, thermally constrained, or otherwise resource-limited.

Dynamic resource state may include:

- battery level
- charging state
- thermal state
- CPU/GPU/NPU availability
- memory pressure
- current load
- user-configured resource limits

Nodes should be able to express contribution preferences such as:

- do not contribute while on battery
- contribute only above a battery threshold
- prefer charging nodes
- limit sustained compute
- allow only low-power workloads

These are resource policies, not assumptions about a particular device class.

AIDIN should avoid treating a temporarily available resource as permanently reliable.

### Secrets and tool execution

Inference data and execution authority should be kept separate.

A node participating in inference should not automatically gain access to:

- API keys
- credentials
- filesystem resources
- application secrets
- external tools
- privileged operations

Tool execution should occur only where explicitly authorized.

AIDIN should support the possibility that inference is distributed while secrets and privileged tool execution remain local to the endpoint or another explicitly trusted execution node.

This is particularly important for agentic workloads, where model execution and action execution have different security boundaries.

### Version compatibility

AIDIN has multiple potentially incompatible version dimensions:

- AIDIN protocol
- Aidos Engine
- execution-plan format
- model/runtime interface
- transport protocol
- backend/runtime version

Compatibility should be negotiated rather than inferred solely from node identity.

Nodes should advertise supported protocol versions and relevant feature capabilities.

A plan should record the compatibility assumptions under which it was created. If those assumptions cease to hold, the session should be re-planned or terminated safely.

Backward compatibility should be preferred where practical, but an incompatible node must not be admitted merely because it can communicate at the transport layer.

### Observability without surveillance

AIDIN needs enough observability to diagnose distributed execution without turning the cluster into a mechanism for unnecessary monitoring of users or devices.

Useful operational information includes:

- session and plan identifiers
- execution timings
- node availability
- resource usage relevant to scheduling
- transport failures
- plan changes
- recovery events
- cancellation and completion state

Telemetry should be minimized to what is needed for operation and debugging.

Inference content, prompts, generated text, and sensitive intermediate state should not be logged by default.

When persistent telemetry is enabled, retention and access should be explicit policy decisions.

### Security model

AIDIN should treat participating nodes as authenticated principals rather than trusting network location.

Security should cover:

- node identity
- pairing and authentication
- authorization to join a cluster
- authorization to execute work
- authorization to receive model material
- authorization to receive request-derived data
- secure transport
- revocation
- session isolation
- protection against stale or replayed control messages

Network proximity must not itself imply trust.

A node leaving a cluster should lose its active authorization according to the cluster's revocation policy.

### Execution isolation

Distributed execution should not implicitly grant one node access to another node's unrelated local state.

AIDIN should define request/session boundaries around:

- request data
- temporary execution state
- model material
- generated results
- credentials or tools, where explicitly authorized

Nodes should expose only the state required for their assigned execution role.

### Discovery and trust separation

Discovery and trust should remain separate concepts.

A node may be discoverable without being trusted.

For example:

    discovered -> authenticated -> authorized -> eligible

AIDIN should not interpret discovery advertisements as authorization to send inference data or model material.

### Plan lifecycle

Execution plans should have an explicit lifecycle:

    proposed
       |
       v
    validated
       |
       v
    activated
       |
       +----> revised
       |
       v
    completed / cancelled / failed

A plan revision should produce a new plan version or execution generation.

Nodes should reject commands referring to stale plan generations where the protocol requires strict ordering.

### Failure domains and recovery

Planning should consider correlated failures, not only individual node failures.

Examples include:

- several devices sharing the same Wi-Fi access point
- nodes depending on the same physical host
- a common power source
- a common network path

Where resilience matters, the planner should avoid placing all critical execution state in the same failure domain when practical.

Recovery policy should be explicit. Possible strategies include:

- restart
- restart from a checkpoint
- re-plan remaining work
- fail the session

AIDIN should not promise transparent recovery where the underlying execution model cannot preserve the required state.

### Checkpointing and state movement

Checkpointing should be adaptive rather than mandatory at every execution boundary.

The planner may consider:

- checkpoint size
- recomputation cost
- network bandwidth
- expected node stability
- latency sensitivity
- recovery requirements

Small control state can be replicated more readily than large model weights, KV caches, or activations.

State should move only when required by the current execution plan and recovery policy.

### Resource contribution and ownership

AIDIN should make resource contribution explicit.

A node owner may define whether the node can contribute:

- always
- only while charging
- only while idle
- only to selected clusters
- only to selected applications
- only for selected resource types

Contribution policy is separate from cluster membership.

Being a member of a cluster does not automatically mean that all resources are available for inference.

### Personal and workplace clusters

AIDIN should support both personal and shared/workplace clusters without making either the architectural default.

A phone may participate in a personal cluster at home and a different authorized cluster elsewhere.

Trust domains should remain separate:

    Personal cluster
        |
        +-- personal devices

    Other cluster
        |
        +-- separately authorized devices

A device changing clusters must not accidentally carry authorization or session state from one trust domain into another.

A workplace or shared cluster is therefore one possible deployment of the general trust, resource, and policy mechanisms rather than a special architecture.

### Testing and simulation

AIDIN should eventually have a deterministic simulation/test layer for distributed behavior.

Tests should cover:

- node join/leave
- coordinator changes
- delayed and reordered messages
- network partitions
- node failure
- plan revision
- cancellation
- resource changes
- incompatible capabilities
- stale commands
- unauthorized nodes
- recovery from checkpoints

Distributed behavior should be testable without requiring a collection of physical devices for every scenario.

### Keep the protocol smaller than the implementation

AIDIN should avoid encoding every possible backend optimization into the core protocol.

The core protocol should primarily define:

- identity
- discovery/pairing
- capabilities
- membership
- sessions
- plans
- execution lifecycle
- data/policy constraints
- state transfer
- health and recovery

Backend-specific optimizations should remain behind the Engine/runtime abstraction where possible.

## Design goals

1. Transparent endpoint switching.
2. Coordinator failover.
3. Execution-node replacement.
4. Versioned topology.
5. Resumability.
6. Heterogeneity.
7. Backend independence.
8. Local-first operation.
9. Authenticated membership.
10. Graceful degradation.
11. Adaptive topology selection.
12. Platform-independent Aidos nodes.
13. Separation of logical identity from physical transport.
14. A single Engine abstraction for local and distributed execution.

## Initial implementation direction

1. Node discovery and stable node IDs.
2. Authenticated pairing and secure node-to-node transport.
3. Capability exchange and health/resource state.
4. Coordinator lease and deterministic election.
5. Monotonic epochs and stale-message rejection.
6. Logical AIDIN sessions.
7. Versioned execution plans.
8. Adaptive planning for replicated and pipeline execution.
9. Pipeline checkpoints and recovery.
10. Graceful draining and migration.
11. More advanced tensor/hybrid execution and recovery.
12. Multi-model distributed execution.
