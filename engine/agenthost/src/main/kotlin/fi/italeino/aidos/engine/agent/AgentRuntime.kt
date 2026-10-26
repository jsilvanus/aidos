package fi.italeino.aidos.engine.agent

import dev.aidos.amp.AmpAgentRunner
import dev.aidos.amp.AmpToolDefinition
import dev.aidos.amp.AmpToolExecutionResult
import dev.aidos.kernel.ModelAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A client-side mistake (unknown run, bad request) as opposed to a failed run. */
class AgentRequestException(val code: String, message: String, val httpStatus: Int = 400) : Exception(message)

/**
 * Stateful wrapper around [AmpAgentRunner] for HTTP callers. Every run is a task: it has an id, a
 * [AgentTurnResponse.status] and a monotonically increasing [AgentTurnResponse.seq], and it belongs
 * to the app (token subject) that started it. A run id is a reference, not authorization: every
 * operation checks the owner, and another app's run is reported as not found.
 *
 * The loop is synchronous and calls tool handlers inline, but the tools belong to the calling app.
 * So a run executes in its own coroutine; when the model asks for an app tool the handler parks in
 * `tool_calls` (input required) until the results arrive. Callers either wait for the next stop
 * point (the default) or start `async` and observe by [get] (long poll) or [events].
 *
 * Bounds: [maxConcurrentRuns] live runs per runtime, each waiting at most [toolWait] for a tool
 * result (then the model sees an error and the run goes on). Finished async runs are kept for
 * [retention] so a client that was away can still read the result; at most [maxRetained] of them.
 */
class AgentRuntime(
    private val scope: CoroutineScope,
    /** Builds the model adapter for a request; the desktop host points it at its own loopback API. */
    private val adapterFor: (AgentTurnRequest) -> ModelAdapter,
    val maxConcurrentRuns: Int = 8,
    val defaultMaxSteps: Int = 8,
    val toolWait: Duration = 120.seconds,
    /** How long a synchronous call waits for the run's next stop point before reporting a failure. */
    internal val stepWait: Duration = 600.seconds,
    val retention: Duration = 10.minutes,
    val maxRetained: Int = 64,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Run(val id: String, val owner: String, val async: Boolean) {
        val state = MutableStateFlow(AgentTurnResponse(AgentTurnResponse.RUNNING, id))
        val waiting = ConcurrentHashMap<String, CompletableDeferred<AmpToolExecutionResult>>()
        @Volatile var finishedAt: Long = 0
        lateinit var job: Job
    }

    private val runs = ConcurrentHashMap<String, Run>()

    private val _events = MutableSharedFlow<Pair<String, AgentEvent>>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Owner-tagged state changes; [eventsFor] is the per-app view. Lossy by design: reconcile with [get]. */
    val events: SharedFlow<Pair<String, AgentEvent>> = _events.asSharedFlow()

    fun info() = AgentInfo(maxConcurrentRuns = maxConcurrentRuns, maxSteps = defaultMaxSteps, toolWaitSeconds = toolWait.inWholeSeconds)

    suspend fun start(owner: String, request: AgentTurnRequest): AgentTurnResponse {
        if (request.message.isBlank()) throw AgentRequestException("invalid_request", "message must not be empty")
        val names = request.tools.map { it.name }
        if (names.any { it.isBlank() } || names.toSet().size != names.size) {
            throw AgentRequestException("invalid_request", "tool names must be non-empty and unique")
        }
        prune()
        if (runs.values.count { !it.state.value.isTerminal } >= maxConcurrentRuns) {
            throw AgentRequestException("too_many_runs", "agent runtime is busy", 429)
        }
        val adapter = try {
            adapterFor(request)
        } catch (e: Exception) {
            throw AgentRequestException("invalid_request", e.message ?: "cannot build model adapter")
        }

        val run = Run(UUID.randomUUID().toString(), owner, request.async)
        runs[run.id] = run
        emit(run, run.state.value)
        val runner = AmpAgentRunner(adapter, request.maxSteps?.coerceIn(1, 32) ?: defaultMaxSteps)
        var callCounter = 0
        val tools = request.tools.map { spec ->
            AmpToolDefinition(spec.name, spec.description, spec.inputSchema) { args -> awaitClient(run, spec.name, args, "call_${++callCounter}") }
        }
        run.job = scope.launch {
            val outcome = runner.executeTurn(
                systemPrompt = request.systemPrompt ?: DEFAULT_SYSTEM_PROMPT,
                conversationHistory = request.history,
                incomingMessage = request.message,
                tools = tools,
            )
            publish(
                run,
                outcome.fold(
                    onSuccess = {
                        AgentTurnResponse(
                            AgentTurnResponse.COMPLETED, run.id, it.replyText, steps = it.totalSteps,
                            executedToolCalls = it.executedToolCalls.map { c -> ExecutedToolCallDto(c.toolName, c.argumentsJson, c.resultText) },
                        )
                    },
                    onFailure = { AgentTurnResponse(AgentTurnResponse.FAILED, run.id, error = it.message ?: it::class.simpleName) },
                ),
            )
        }
        return if (request.async) run.state.value else awaitStop(run, since = 0)
    }

    suspend fun submit(owner: String, runId: String, results: List<ClientToolResult>, async: Boolean = false): AgentTurnResponse {
        val run = owned(owner, runId)
        val before = run.state.value
        if (before.isTerminal) throw AgentRequestException("run_finished", "run already ${before.status}", 409)
        for (r in results) {
            if (run.waiting[r.callId] == null) throw AgentRequestException("unknown_call", "no pending tool call ${r.callId}")
        }
        for (r in results) run.waiting.remove(r.callId)?.complete(AmpToolExecutionResult(r.output, r.isError))
        if (results.isNotEmpty()) publish(run, before.copy(status = AgentTurnResponse.RUNNING, toolCalls = emptyList()))
        return if (async) run.state.value else awaitStop(run, since = run.state.value.seq)
    }

    /**
     * The run's snapshot. With [waitFor] set, blocks until its seq passes [since] (or it is
     * terminal, or the wait elapses), then returns the current snapshot either way.
     */
    suspend fun get(owner: String, runId: String, since: Int? = null, waitFor: Duration = Duration.ZERO): AgentTurnResponse {
        val run = owned(owner, runId)
        if (since != null && waitFor > Duration.ZERO) {
            withTimeoutOrNull(waitFor) { run.state.first { it.seq > since || it.isTerminal } }
        }
        return run.state.value
    }

    fun list(owner: String): List<AgentTurnResponse> =
        runs.values.filter { it.owner == owner }.map { it.state.value }.sortedBy { it.runId }

    /** Cooperative: the status becomes `cancelled` when the request is made; a model call in flight may still finish. */
    fun cancel(owner: String, runId: String): Boolean {
        val run = runs[runId]?.takeIf { it.owner == owner } ?: return false
        if (!run.state.value.isTerminal) {
            run.job.cancel()
            publish(run, AgentTurnResponse(AgentTurnResponse.CANCELLED, run.id, steps = run.state.value.steps))
        }
        if (!run.async) runs.remove(run.id)
        return true
    }

    private fun owned(owner: String, runId: String): Run =
        runs[runId]?.takeIf { it.owner == owner } ?: throw AgentRequestException("run_not_found", "no such run: $runId", 404)

    private fun publish(run: Run, next: AgentTurnResponse) {
        // Terminal states are final: a late result from a cancelled run must not resurrect it.
        if (run.state.value.isTerminal) return
        val stamped = next.copy(seq = run.state.value.seq + 1)
        if (stamped.isTerminal) run.finishedAt = now()
        run.state.value = stamped
        emit(run, stamped)
    }

    private fun emit(run: Run, snapshot: AgentTurnResponse) {
        _events.tryEmit(run.owner to AgentEvent("run.updated", run.id, snapshot.status, snapshot.seq))
    }

    /** Waits for the next stop point after [since]; synchronous callers get the run forgotten once it is over. */
    private suspend fun awaitStop(run: Run, since: Int): AgentTurnResponse {
        val stop = withTimeoutOrNull(stepWait) {
            run.state.first { it.seq > since && it.status != AgentTurnResponse.RUNNING }
        }
        if (stop == null) {
            cancel(run.owner, run.id)
            return AgentTurnResponse(AgentTurnResponse.FAILED, run.id, error = "run produced no result within $stepWait", seq = run.state.value.seq)
        }
        if (stop.isTerminal && !run.async) runs.remove(run.id)
        return stop
    }

    private suspend fun awaitClient(run: Run, name: String, args: JsonObject, callId: String): AmpToolExecutionResult {
        val waiter = CompletableDeferred<AmpToolExecutionResult>()
        run.waiting[callId] = waiter
        publish(run, run.state.value.copy(status = AgentTurnResponse.TOOL_CALLS, toolCalls = listOf(PendingToolCall(callId, name, args))))
        val result = withTimeoutOrNull(toolWait) { waiter.await() }
        if (result != null) return result
        // Nobody answered: the model sees an error and the run carries on.
        run.waiting.remove(callId)
        publish(run, run.state.value.copy(status = AgentTurnResponse.RUNNING, toolCalls = emptyList()))
        return AmpToolExecutionResult("Tool '$name' did not answer within $toolWait", isError = true)
    }

    /** Drops finished runs past [retention], then the oldest finished ones beyond [maxRetained]. */
    private fun prune() {
        val t = now()
        val finished = runs.values.filter { it.state.value.isTerminal }
        finished.filter { t - it.finishedAt > retention.inWholeMilliseconds }.forEach { runs.remove(it.id) }
        finished.filter { runs.containsKey(it.id) }.sortedBy { it.finishedAt }.dropLast(maxRetained).forEach { runs.remove(it.id) }
    }

    private companion object {
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant running locally on the user's device. Use the provided tools when they help."
    }
}
