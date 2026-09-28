# AIDIN — AIdos Distributed Inference Network

AIDIN is the distributed inference layer of Aidos. It allows multiple Aidos Engine instances to cooperate as a single logical inference system across nearby Android devices, laptops, and other supported hosts.

## Core principle

**No node is the inference. The session is the inference. Nodes are replaceable execution resources.**

A caller should interact with a logical AIDIN session rather than a physical endpoint. The coordinator and execution nodes may change while the session remains the same.

## Resilient coordination

AIDIN must not depend on a permanently fixed coordinator.

The coordinator is a temporary role held by one node and protected by a lease. If the coordinator disappears or its lease expires, another eligible node can take over.

The initial coordination design should use:

- **Node IDs** — stable identity for each AIDIN node.
- **Monotonic epochs** — every coordinator generation has a strictly increasing epoch. Messages from an older epoch are stale and must not mutate current cluster state.
- **Leases/timeouts** — coordinator authority expires unless renewed. Missing a single heartbeat should not immediately declare a node dead.
- **Deterministic election** — when a coordinator is lost, eligible nodes select the next coordinator deterministically from the current membership view. Election must produce a new epoch.
- **Signed membership messages** — membership and coordination messages should be authenticated so an untrusted node cannot impersonate another node or forge cluster state.

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

## Execution plans

Execution topology is represented by a versioned execution plan. If a node fails, a new plan can redistribute work. Nodes must not mix state or assumptions from incompatible plan versions.

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

## Network partitions

AIDIN must explicitly account for network partitions.

The first implementation should prefer safety over continuing a split cluster with ambiguous ownership of session state:

- Two partitions must not independently mutate the same session under the same epoch.
- A new coordinator must use a higher epoch.
- Nodes must reject stale coordinator commands.
- A partitioned node should rejoin and reconcile state rather than assuming it remained authoritative.

## Control-state replication

Replicate relatively small control state where useful: epoch, coordinator, membership, sessions, execution plans, plan versions, and checkpoint metadata.

Do not automatically replicate model weights, every activation, or every tensor. Those belong to the execution/data plane and should be transferred only when required.

## Endpoint and transport abstraction

Clients should never treat an IP address or socket as the identity of an AIDIN endpoint.

Use a logical node ID with current network addresses, supported transports, and capabilities as attributes. This allows a node to change network address, reconnect, or use another local transport without changing its identity.

## Broader execution model

AIDIN should support replicated inference, pipeline/layer splitting, tensor parallelism, hybrid execution, and potentially multi-model execution. The protocol must not be tied to a specific model runtime or hardware platform.

An AIDIN node is an Aidos Engine instance. Android phones/tablets and KMP desktop/laptop hosts are first-class nodes.

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

## Initial implementation direction

1. Node discovery and stable node IDs.
2. Authenticated pairing and secure node-to-node transport.
3. Capability exchange and health state.
4. Coordinator lease and deterministic election.
5. Monotonic epochs and stale-message rejection.
6. Logical AIDIN sessions.
7. Versioned execution plans.
8. Pipeline checkpoints and recovery.
9. Graceful draining and migration.
10. More advanced tensor/hybrid recovery.