package fi.italeino.aidos.engine.agent

import dev.aidos.amp.AmpAgentRunner
import dev.aidos.amp.AmpToolDefinition
import dev.aidos.amp.AmpToolExecutionResult
import dev.aidos.kernel.ModelAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A client-side mistake (unknown run, bad request) as opposed to a failed run. */
class AgentRequestException(val code: String, message: String, val httpStatus: Int = 400) : Exception(message)

/**
 * Stateful wrapper around [AmpAgentRunner] for HTTP callers.
 *
 * The loop is synchronous and calls tool handlers inline, but the tools belong to the calling app.
 * So a run executes in its own coroutine; when the model asks for an app tool the handler parks,
 * the pending call is returned to the caller, and the run resumes when the results arrive. One
 * request therefore returns at the next point where the runtime needs the caller, or at the end.
 *
 * Bounds: [maxConcurrentRuns] live runs, each waiting at most [toolWait] for a tool result. A run
 * that is not answered in time fails its tool call (the model sees an error) rather than leaking.
 */
class AgentRuntime(
    private val scope: CoroutineScope,
    /** Builds the model adapter for a request; the desktop host points it at its own loopback API. */
    private val adapterFor: (AgentTurnRequest) -> ModelAdapter,
    val maxConcurrentRuns: Int = 8,
    val defaultMaxSteps: Int = 8,
    val toolWait: Duration = 120.seconds,
    /** How long a caller's request waits for the run's next event before reporting a failure. */
    internal val stepWait: Duration = 600.seconds,
) {
    private sealed interface Event {
        data class NeedTool(val call: PendingToolCall) : Event
        data class Done(val response: AgentTurnResponse) : Event
    }

    private class Run(val id: String) {
        val events = Channel<Event>(Channel.UNLIMITED)
        val waiting = ConcurrentHashMap<String, CompletableDeferred<AmpToolExecutionResult>>()
        lateinit var job: Job
    }

    private val runs = ConcurrentHashMap<String, Run>()

    fun info() = AgentInfo(maxConcurrentRuns = maxConcurrentRuns, maxSteps = defaultMaxSteps, toolWaitSeconds = toolWait.inWholeSeconds)

    suspend fun start(request: AgentTurnRequest): AgentTurnResponse {
        if (request.message.isBlank()) throw AgentRequestException("invalid_request", "message must not be empty")
        val names = request.tools.map { it.name }
        if (names.any { it.isBlank() } || names.toSet().size != names.size) {
            throw AgentRequestException("invalid_request", "tool names must be non-empty and unique")
        }
        if (runs.size >= maxConcurrentRuns) throw AgentRequestException("too_many_runs", "agent runtime is busy", 429)

        val run = Run(UUID.randomUUID().toString())
        runs[run.id] = run
        val adapter = try {
            adapterFor(request)
        } catch (e: Exception) {
            runs.remove(run.id)
            throw AgentRequestException("invalid_request", e.message ?: "cannot build model adapter")
        }
        val runner = AmpAgentRunner(adapter, request.maxSteps?.coerceIn(1, 32) ?: defaultMaxSteps)
        var callCounter = 0
        val tools = request.tools.map { spec ->
            AmpToolDefinition(spec.name, spec.description, spec.inputSchema) { args -> awaitClient(run, spec.name, args, "call_${++callCounter}") }
        }
        run.job = scope.launch {
            val outcome = try {
                runner.executeTurn(
                    systemPrompt = request.systemPrompt ?: DEFAULT_SYSTEM_PROMPT,
                    conversationHistory = request.history,
                    incomingMessage = request.message,
                    tools = tools,
                )
            } catch (e: CancellationException) {
                throw e
            }
            val response = outcome.fold(
                onSuccess = {
                    AgentTurnResponse(
                        AgentTurnResponse.COMPLETED, run.id, it.replyText, steps = it.totalSteps,
                        executedToolCalls = it.executedToolCalls.map { c -> ExecutedToolCallDto(c.toolName, c.argumentsJson, c.resultText) },
                    )
                },
                onFailure = { AgentTurnResponse(AgentTurnResponse.FAILED, run.id, error = it.message ?: it::class.simpleName) },
            )
            run.events.send(Event.Done(response))
        }
        return nextStep(run)
    }

    suspend fun submit(runId: String, results: List<ClientToolResult>): AgentTurnResponse {
        val run = runs[runId] ?: throw AgentRequestException("run_not_found", "no such run: $runId", 404)
        for (r in results) {
            val waiter = run.waiting.remove(r.callId)
                ?: throw AgentRequestException("unknown_call", "no pending tool call ${r.callId}")
            waiter.complete(AmpToolExecutionResult(r.output, r.isError))
        }
        return nextStep(run)
    }

    fun cancel(runId: String): Boolean {
        val run = runs.remove(runId) ?: return false
        run.job.cancel()
        run.events.close()
        return true
    }

    private suspend fun nextStep(run: Run): AgentTurnResponse {
        val event = withTimeoutOrNull(stepWait) { run.events.receive() }
        if (event == null) {
            cancel(run.id)
            return AgentTurnResponse(AgentTurnResponse.FAILED, run.id, error = "run produced no result within $stepWait")
        }
        return when (event) {
            is Event.NeedTool -> AgentTurnResponse(AgentTurnResponse.TOOL_CALLS, run.id, toolCalls = listOf(event.call))
            is Event.Done -> {
                runs.remove(run.id)
                event.response
            }
        }
    }

    private suspend fun awaitClient(run: Run, name: String, args: JsonObject, callId: String): AmpToolExecutionResult {
        val waiter = CompletableDeferred<AmpToolExecutionResult>()
        run.waiting[callId] = waiter
        run.events.send(Event.NeedTool(PendingToolCall(callId, name, args)))
        val result = withTimeoutOrNull(toolWait) { waiter.await() }
        run.waiting.remove(callId)
        return result ?: AmpToolExecutionResult("Tool '$name' did not answer within $toolWait", isError = true)
    }

    private companion object {
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant running locally on the user's device. Use the provided tools when they help."
    }
}
