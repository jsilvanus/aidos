package dev.aidos.api.socket

import dev.aidos.api.CapabilityResult
import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.CreateSessionRequest
import dev.aidos.api.EventFilter
import dev.aidos.api.GrantCapabilityRequest
import dev.aidos.api.PendingCapabilityRequest
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import dev.aidos.api.ProjectSummary
import dev.aidos.api.RunResult
import dev.aidos.api.RuntimeEvent
import dev.aidos.api.RuntimeEventType
import dev.aidos.api.RuntimeVersion
import dev.aidos.api.SessionResult
import dev.aidos.api.SessionRole
import dev.aidos.api.SessionState
import dev.aidos.api.SessionSummary
import dev.aidos.api.UserMessage
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round-trip tests for the M10 socket wire codec (RFC-0052). Every encode/decode pair here is a
 * function the socket transport actually calls at runtime -- these are cheaper and faster to run
 * than the full subprocess integration test, and pin the wire format precisely.
 */
class WireCodecTest {

    @Test
    fun `CreateProjectRequest round-trips through RuntimeManaged location`() {
        val request = CreateProjectRequest(
            name = "proj", description = "desc",
            location = ProjectLocation.RuntimeManaged("proj-slug"),
            initGit = false, templateId = "tmpl-1",
        )
        val decoded = Wire.decodeCreateProjectRequest(Wire.encodeCreateProjectRequest(request))
        assertEquals(request, decoded)
    }

    @Test
    fun `ProjectResult Success and Error round-trip`() {
        val now = Clock.System.now()
        val success = ProjectResult.Success(ProjectSummary("p1", "name", "desc", "/path", now, now, 2))
        assertEquals(success, Wire.decodeProjectResult(Wire.encodeProjectResult(success)))

        val error = ProjectResult.Error("project.not_found", "no such project")
        assertEquals(error, Wire.decodeProjectResult(Wire.encodeProjectResult(error)))
    }

    @Test
    fun `CreateSessionRequest round-trips`() {
        val request = CreateSessionRequest("p1", "sess", SessionRole.WORKER, "instr-1")
        assertEquals(request, Wire.decodeCreateSessionRequest(Wire.encodeCreateSessionRequest(request)))
    }

    @Test
    fun `SessionResult and SessionSummary round-trip`() {
        val now = Clock.System.now()
        val summary = SessionSummary("s1", "p1", "sess", SessionRole.DRIVER, SessionState.SLEEPING, now, now, 3)
        val success = SessionResult.Success(summary)
        assertEquals(success, Wire.decodeSessionResult(Wire.encodeSessionResult(success)))
    }

    @Test
    fun `UserMessage round-trips including run options`() {
        val message = UserMessage("hello world", listOf("att-1"), dev.aidos.api.RunOptions(1000, true, 60))
        assertEquals(message, Wire.decodeUserMessage(Wire.encodeUserMessage(message)))
    }

    @Test
    fun `RunResult Accepted and Error round-trip`() {
        assertEquals(RunResult.Accepted("run-1"), Wire.decodeRunResult(Wire.encodeRunResult(RunResult.Accepted("run-1"))))
        val error = RunResult.Error("run.denied", "no capability")
        assertEquals(error, Wire.decodeRunResult(Wire.encodeRunResult(error)))
    }

    @Test
    fun `GrantCapabilityRequest round-trips with constraints and expiry`() {
        val now = Clock.System.now()
        val request = GrantCapabilityRequest("s1", "FS_WRITE", "scope-1", mapOf("path" to "/tmp"), now)
        assertEquals(request, Wire.decodeGrantCapabilityRequest(Wire.encodeGrantCapabilityRequest(request)))
    }

    @Test
    fun `CapabilityResult round-trips`() {
        assertEquals(
            CapabilityResult.Success("cap-1"),
            Wire.decodeCapabilityResult(Wire.encodeCapabilityResult(CapabilityResult.Success("cap-1"))),
        )
    }

    @Test
    fun `PendingCapabilityRequest list round-trips`() {
        val list = listOf(
            PendingCapabilityRequest("req-1", "s1", "FS_WRITE", "need write"),
            PendingCapabilityRequest("req-2", "s2", "NETWORK_EGRESS", "need network"),
        )
        assertEquals(list, Wire.decodePendingCapabilityList(Wire.encodePendingCapabilityList(list)))
    }

    @Test
    fun `EventFilter round-trips including sinceSequence`() {
        val filter = EventFilter(listOf("p1"), listOf("s1"), listOf(RuntimeEventType.RUN_STARTED), sinceSequence = 42L)
        assertEquals(filter, Wire.decodeEventFilter(Wire.encodeEventFilter(filter)))
    }

    @Test
    fun `every RuntimeEvent variant round-trips`() {
        val now = Clock.System.now()
        val events = listOf(
            RuntimeEvent.SessionCreated("e1", now, "p1", "s1", "name", SessionRole.DRIVER),
            RuntimeEvent.SessionStateChanged("e2", now, "p1", "s1", SessionState.CREATED, SessionState.RUNNING),
            RuntimeEvent.RunStarted("e3", now, "p1", "s1", "r1"),
            RuntimeEvent.RunCompleted("e4", now, "p1", "s1", "r1", listOf("art-1", "art-2")),
            RuntimeEvent.RunFailed("e5", now, "p1", "s1", "r1", "err.code", "err message"),
            RuntimeEvent.RunStepCompleted("e6", now, "p1", "s1", "r1", "t1", 3, "COMPLETED"),
            RuntimeEvent.AiResponseDelta("e7", now, "p1", "s1", "r1", "partial text", false),
            RuntimeEvent.ToolApprovalRequired("e8", now, "p1", "s1", "r1", "t1", "fs.write", "write file.txt"),
        )
        events.forEach { event ->
            assertEquals(event, Wire.decodeRuntimeEvent(Wire.encodeRuntimeEvent(event)), "round-trip failed for $event")
        }
    }

    @Test
    fun `RuntimeVersion round-trips`() {
        val version = RuntimeVersion("0.1.0-alpha", 1, "DESKTOP")
        assertEquals(version, Wire.decodeRuntimeVersion(Wire.encodeRuntimeVersion(version)))
    }
}
