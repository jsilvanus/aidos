package dev.aidos.kernel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These are not unit tests of behaviour — there is no behaviour yet. They assert the properties
 * the *types* are supposed to guarantee, so that a later refactor cannot quietly remove one.
 *
 * Each corresponds to a decision in `docs/decisions.md`.
 */
class ContractTest {

    // --- RelPath: escape is prevented by construction (RFC-0018) -------------------------

    @Test
    fun `RelPath rejects the escape corpus`() {
        val hostile = listOf(
            "/etc/passwd",              // absolute
            "\\windows\\system32",      // absolute, backslash
            "../../../etc/passwd",      // traversal
            "src/../../outside",        // traversal mid-path
            "src\\..\\..\\outside",     // traversal, backslash
            "C:/Windows",               // drive-qualified
            "",                         // empty
            "src/\u0000hidden",         // NUL byte
        )
        for (path in hostile) {
            assertTrue(RelPath.of(path).isFailure, "should have rejected: $path")
        }
    }

    @Test
    fun `RelPath accepts ordinary relative paths`() {
        for (path in listOf("src/main.kt", "a/b/c.txt", "README.md", "src/..foo/x")) {
            assertTrue(RelPath.of(path).isSuccess, "should have accepted: $path")
        }
    }

    // --- Taint is monotonic (RFC-0027) ---------------------------------------------------

    @Test
    fun `taint only ever rises`() {
        assertEquals(TrustLevel.UNTRUSTED, TrustLevel.TRUSTED raisedBy TrustLevel.UNTRUSTED)
        assertEquals(TrustLevel.UNTRUSTED, TrustLevel.UNTRUSTED raisedBy TrustLevel.TRUSTED)
        assertEquals(TrustLevel.PROJECT, TrustLevel.TRUSTED raisedBy TrustLevel.PROJECT)
        assertEquals(TrustLevel.UNTRUSTED, TrustLevel.UNTRUSTED raisedBy TrustLevel.PROJECT)
    }

    // --- Budget divides on delegation (RFC-0028, decision D8) ----------------------------

    @Test
    fun `delegating a budget divides it rather than multiplying it`() {
        val driver = Budget(modelCalls = 9, costUnits = 9000, steps = 24)
        val perWorker = driver.split(3)

        assertEquals(3, perWorker.modelCalls)
        assertEquals(3000L, perWorker.costUnits)
        assertEquals(8, perWorker.steps)

        // Three workers must not exceed what the driver held.
        assertTrue((perWorker.costUnits!! * 3) <= driver.costUnits!!)
    }

    // --- UNSAFE effects are never retried (RFC-0009) -------------------------------------

    @Test
    fun `retry policy cannot override an UNSAFE recovery class`() {
        val permissive = RetryPolicy(
            maxAttempts = 5,
            retryOn = ErrorClass.entries.toSet(),   // deliberately allows everything
            backoff = BackoffStrategy.None,
        )
        val transient = AidosError("git.push_failed", ErrorClass.TRANSIENT, "network blip")

        assertFalse(
            permissive.permits(transient, RecoveryClass.UNSAFE, attemptNumber = 1),
            "an interrupted push must never be retried, whatever the policy says",
        )
        assertTrue(permissive.permits(transient, RecoveryClass.IDEMPOTENT, attemptNumber = 1))
    }

    @Test
    fun `INDETERMINATE is not retryable`() {
        assertFalse(ErrorClass.INDETERMINATE.isRetryable)
        assertTrue(ErrorClass.TRANSIENT.isRetryable)
        assertTrue(ErrorClass.RATE_LIMITED.isRetryable)
    }

    // --- Invalid model output goes to the model, not the user (RFC-0029) -----------------

    @Test
    fun `schema violations are routed to the model so it can correct itself`() {
        assertTrue(ErrorClass.INVALID_INPUT.isModelAudience)
        assertTrue(ErrorClass.DENIED.isModelAudience)
        assertFalse(ErrorClass.INTERNAL.isModelAudience)
        assertFalse(ErrorClass.EXHAUSTED.isModelAudience)
    }

    // --- Availability filtering (RFC-0049) ----------------------------------------------

    @Test
    fun `shell is not offered on MOBILE and networked tools are not offered offline`() {
        val shell = ToolAvailability(
            profiles = setOf(PlatformProfile.DESKTOP, PlatformProfile.HEADLESS_SERVER),
            tier = AvailabilityTier.PLATFORM,
        )
        assertFalse(shell.availableOn(PlatformProfile.MOBILE, networkAvailable = true))
        assertTrue(shell.availableOn(PlatformProfile.DESKTOP, networkAvailable = false))

        val remoteModel = ToolAvailability(
            profiles = PlatformProfile.entries.toSet(),
            tier = AvailabilityTier.NETWORKED,
            requiresNetwork = true,
        )
        assertFalse(remoteModel.availableOn(PlatformProfile.MOBILE, networkAvailable = false))
        assertTrue(remoteModel.availableOn(PlatformProfile.MOBILE, networkAvailable = true))
    }

    // --- Run/Task terminality (RFC-0006) ------------------------------------------------

    @Test
    fun `INTERRUPTED is not terminal - recovery resolves it`() {
        assertFalse(RunState.INTERRUPTED.isTerminal)
        assertFalse(RunState.YIELDED.isTerminal)
        assertTrue(RunState.COMPLETED.isTerminal)
        assertTrue(RunState.FAILED.isTerminal)
    }

    @Test
    fun `parked task states are the ones that permit fan-out`() {
        assertTrue(TaskState.AWAITING_INPUT.isParked)
        assertTrue(TaskState.AWAITING_APPROVAL.isParked)
        assertFalse(TaskState.RUNNING.isParked)
    }

    // --- Approval tiers (RFC-0057, D26) -------------------------------------------------

    private fun tier(
        effect: EffectKind,
        recovery: RecoveryClass = RecoveryClass.IDEMPOTENT,
        taint: TrustLevel = TrustLevel.TRUSTED,
        newGrant: Boolean = false,
    ) = approvalTier(effect, recovery, taint, newGrant)

    @Test
    fun `an ordinary in-project edit is benign`() {
        assertEquals(ApprovalTier.BENIGN, tier(EffectKind.Mutate(MutationScope.IN_PROJECT)))
        assertEquals(ApprovalTier.BENIGN, tier(EffectKind.Read))
    }

    @Test
    fun `a destructive branch switch is never benign`() {
        // In-project, untainted, and re-runnable after a crash — it satisfies every clause of
        // the classifier except the one that matters. Discarding a user's uncommitted work must
        // not be approvable by saying "approve" while cycling (RFC-0053).
        assertEquals(
            ApprovalTier.READBACK,
            tier(EffectKind.Mutate(MutationScope.IN_PROJECT, reversible = false)),
        )
    }

    @Test
    fun `reversibility is not the same question as recovery class`() {
        val destructive = EffectKind.Mutate(MutationScope.IN_PROJECT, reversible = false)
        // Re-runnable, yet still not benign: the two axes are independent.
        assertTrue(RecoveryClass.IDEMPOTENT.isRetryable)
        assertEquals(ApprovalTier.READBACK, tier(destructive, recovery = RecoveryClass.IDEMPOTENT))
    }

    @Test
    fun `egress, taint, and new grants need the user's eyes`() {
        assertEquals(ApprovalTier.EYES_ONLY, tier(EffectKind.Egress("api.example.com")))
        assertEquals(
            ApprovalTier.EYES_ONLY,
            tier(EffectKind.Mutate(MutationScope.IN_PROJECT), taint = TrustLevel.UNTRUSTED),
        )
        assertEquals(
            ApprovalTier.EYES_ONLY,
            tier(EffectKind.Mutate(MutationScope.IN_PROJECT), newGrant = true),
        )
    }
}
