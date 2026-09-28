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
