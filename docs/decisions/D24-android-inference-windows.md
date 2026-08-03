# D24 — Local inference under Android execution windows

Status: **RESOLVED** (2026-08-02) — long-form analysis. The decision is recorded in
`docs/decisions.md` D24.

**Decision: (a) foreground service as the primary path, (d) preparation-only as the fallback
when an FGS is unavailable or declined. (b) is rejected and should not be revisited without new
evidence.**

The analysis below is retained because the reasoning matters more than the outcome — anyone
proposing to reopen this should start by disagreeing with something specific in it.

## The question

**Can a Run that is not in the foreground make a local model call?**

## Why it is not obvious

The naive framing — "a model call exceeds any available window" — is wrong, and it is worth
correcting because it makes the answer look more constrained than it is.

A **foreground service holding a wake lock can run for minutes.** On-device inference of ~500
tokens from a 3B model at ~10 tok/s is roughly 50 seconds, plus up to 8 seconds of cold model
load. That fits comfortably inside an FGS. It does *not* fit reliably inside `WorkManager`'s
10-minute ceiling once several steps are chained, and it fits nowhere at all during deep Doze.

So the real question is not "does it fit" but **"what are we willing to require in order to make
it fit?"**

Three constraints bound the answer:

- An FGS needs a **user-visible ongoing notification** and, since Android 14, a declared type
  with justification. Some types carry daily caps.
- `WorkManager` gives ~10 minutes, no timing guarantee, and a 15-minute floor for periodic work.
- Doze defers everything until a maintenance window; App Standby buckets tighten this further
  the less the user opens the app.

## What is at stake

RFC-0044 promises recurring sessions, scheduled work, and life-management workflows. RFC-0000
promises offline-first. This decision determines whether those two promises hold together in the
background, or only in the foreground.

It also determines what "your morning review ran overnight" means — whether it ran, or whether
it is merely ready to run.

## Options

### (a) Local inference requires a foreground service

Any Run making a local model call promotes to an FGS with a visible ongoing notification.
Scheduled Runs needing inference either promote — notification appears — or wait.

- **Good:** works today, no exotic engineering, honest about resource use, the user sees what is
  happening and can stop it.
- **Bad:** a notification for work the user did not initiate. FGS type declaration, possible
  daily caps, and Play Store scrutiny. A phone in a pocket showing "Aidos: working" at 03:00 is
  the failure mode.

### (b) Sub-step checkpointing of inference

Checkpoint mid-generation — persist KV cache and partial output — and resume in the next window.

- **Good:** true background autonomy with no notification.
- **Bad:** KV cache is hundreds of megabytes; serializing it per window is expensive in both I/O
  and battery, plausibly more than the inference it preserves. `llama.cpp` state save/restore
  exists but is heavy. **This is the option that sounds best and is probably worst.**

### (c) Background Runs use remote models; local inference is foreground-only

- **Good:** simple, no notification, background autonomy preserved.
- **Bad:** contradicts offline-first exactly where it was promised. A scheduled session on a
  train does nothing.

### (d) Background Runs are preparation-only

A background Run may do deterministic work — index, fetch, reconcile Git, assemble context — but
any model call parks the Run and notifies *"ready to continue"*. Inference happens when the user
opens the app.

- **Good:** no surprise notifications, no heavy engineering, offline promise intact, and the
  expensive work is done by the time the user looks.
- **Bad:** weakens RFC-0044 substantially. "Recurring sessions" become "recurring preparation",
  which for some workflows is indistinguishable from a reminder.

## Recommendation

**(a) as the primary path, (d) as the fallback when an FGS is unavailable or the user has
declined it.**

Reasoning:

1. The stated core use case is foreground — making progress on Git projects, offline, on a
   phone. Background autonomy is secondary, and it should not dictate an expensive mechanism.
2. (a) is honest. An agent consuming the battery should say so. A visible notification is the
   correct user experience for a phone doing sustained work, not a cost to engineer around.
3. (d) degrades gracefully rather than failing, and it makes the foreground session faster
   because context is already assembled.
4. (b) should be rejected explicitly, so nobody attempts it later on the assumption that it was
   merely unexamined.

Under this combination, RFC-0044's recurring sessions are real when the user has granted an FGS,
and become "prepared and waiting" when they have not. Both states are explainable to a user in
one sentence, which is the test that matters.

## What this decides downstream

- Whether RFC-0009's deadline budget needs an escape hatch for steps that cannot fit any window.
  Under (a) it does not — the FGS window is long enough. Under (d) a model call is simply not
  attempted in the background.
- Whether RFC-0044 should soften its recurring-session language.
- What the Android app requests at install time, and how that is explained.

## What would change the answer

- Small models getting materially faster on-device (making (d)'s fallback rarer).
- Android tightening FGS further (pushing toward (d) as primary).
- Measured battery cost of sustained inference proving unacceptable at G3 (pushing toward (c)
  for background, keeping local for foreground).

## Resolution

Adopted as recommended: **(a) primary, (d) fallback, (b) rejected.**

`ForegroundRequired` is added as a suspension reason (RFC-0006), so a background Run that
reaches a local model call without an FGS parks explicitly rather than failing or silently
degrading. RFC-0044 softens its recurring-session language accordingly: recurring sessions
complete autonomously when the user has granted a foreground service, and otherwise prepare and
wait.

(b) is rejected rather than deferred. Anyone reaching for mid-generation checkpointing later
should first measure the serialization cost of a KV cache against the inference it would
preserve; the expectation is that it loses.
