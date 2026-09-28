package fi.italeino.aidos.engine

/**
 * Lifecycle state of an Engine host (RFC-0103): Android's EngineService or the desktop debug host.
 *
 * STARTING is also the state of a stopped host; callers that need to tell the two apart check the
 * host's own running flag, as the Android status screen does.
 */
enum class EngineState {
    STARTING,
    READY,
    FAILED,
}
