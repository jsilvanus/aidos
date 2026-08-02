# RFC-0044: Notifications, Timers, and Background Work

Status: Draft

## Abstract

This RFC defines scheduled triggers, background work classes, and user notification. Its
governing rule is that **timers are best-effort and carry no latency guarantee**, so nothing in
the architecture may depend on a wake happening at a precise moment. It also defines the
notification budget, because an agent that can wake itself and message you is one design mistake
away from being unbearable.

## Motivation

Recurring sessions, reminders, and life-management workflows are explicit goals (RFC-0000), and
they all rest on two capabilities the platform grants grudgingly: waking up, and interrupting
the user.

Android grants neither reliably. `WorkManager` periodic work has a 15-minute floor and no upper
bound on delay; exact alarms require a special-access permission that is discouraged and
reviewed; Doze and App Standby defer work indefinitely on a device that is idle. A design
assuming "wake at 09:00" produces a product that misses 09:00, silently, on the platform that
matters most.

The second half is a product risk rather than a technical one. Aidos can wake sessions on
events, and sessions can produce notifications. Without a budget, a project with a filesystem
watcher and a chatty session becomes a notification generator, and the user turns off
notifications — losing the ones that mattered.

## Goals

1. Define trigger types and their guarantees.
2. Define background work classes and how each maps to platform mechanisms.
3. Define notification semantics, budget, and grouping.
4. Define cancellation and failure handling.
5. Define what may not depend on timing.

## Non-goals

This RFC does not define the execution model (RFC-0009) or platform profiles (RFC-0049).
It does not define wake amplification limits (RFC-0028).
It does not define notification visual design.

## Design

### Triggers

```kotlin
sealed class Trigger {
    data class At(val instant: Instant) : Trigger()
    data class Every(val interval: Duration, val anchor: Instant?) : Trigger()
    data class Cron(val expression: String, val zone: TimeZone) : Trigger()
    data class OnEvent(val filter: EventFilter) : Trigger()
    data class OnCondition(val predicate: ConditionRef) : Trigger()
}
```

Every trigger carries a **guarantee class**, and the class is what callers must design against:

| Class | Meaning | Available on |
|---|---|---|
| `PROMPT` | fires within seconds | DESKTOP (foreground), MOBILE (app in foreground) |
| `EVENTUAL` | fires within the window, possibly much later | all profiles |
| `OPPORTUNISTIC` | fires when conditions allow; may not fire today | MOBILE background |

**On MOBILE, a background trigger is `EVENTUAL` at best.** A daily 09:00 review may run at
09:00, or at 11:20 when the user next unlocks their phone. This is stated in the API and shown
in the UI, because a user told "daily at 09:00" who gets 11:20 concludes the product is broken,
whereas a user told "each morning" does not.

Exact alarms are **not** requested. The special-access permission is user-hostile, subject to
store review, and revocable; building a feature on it means the feature disappears when the user
declines. If a genuinely time-critical use case appears later, it can be added as an opt-in for
users who choose to grant it — but nothing in the core may assume it.

### What may not depend on timing

A hard rule, because violations are subtle:

- **No session semantics may assume a wake occurred at a specific time.** A woken session reads
  the clock and decides what to do; it does not infer the current time from the fact that it
  woke.
- **No correctness may depend on ordering between two timers.** They may fire in either order,
  or one may not fire.
- **Missed occurrences do not accumulate.** A daily trigger that did not fire for three days
  fires **once** on the next opportunity, with `missedOccurrences = 3` available to the session.
  Firing three times would mean a phone switched on after a holiday running a week of catch-up
  work and spending real money doing it (RFC-0028).

That last rule is the mobile-first one. Coalescing rather than replaying is the same decision
made for boot events in RFC-0005, for the same reason.

### Background work classes

| Class | Mechanism (MOBILE) | Mechanism (DESKTOP) | Example |
|---|---|---|---|
| **Interactive** | foreground service, ongoing notification | inline | a Run the user just started |
| **Deferred** | `WorkManager`, constraints | background dispatcher | indexing, compaction |
| **Scheduled** | `WorkManager` periodic | timer | recurring session |
| **Opportunistic** | `WorkManager` + charging/idle/unmetered | idle detection | model download, embedding backfill |

Constraints are declared, not assumed: an embedding backfill declares *charging and unmetered*,
so a phone on cellular does not spend the user's data allowance and battery on background AI
work. Getting this wrong is the difference between a background feature and an uninstall.

All classes execute through the same checkpointed executor (RFC-0009). Deferral changes *when*
work runs, never *how* — so a deferred Run evicted mid-flight resumes exactly like an
interactive one.

### Notifications

A notification is an `Egress`-adjacent effect: it reaches the user, and it cannot be un-sent.
It is `Notify` in the effect taxonomy (RFC-0030), with recovery class `UNSAFE` — **never
retried automatically**, because a duplicate notification is worse than a missing one.

**The notification budget.** Per project, per rolling hour, with a low default (3). Beyond it,
notifications are **coalesced**, not dropped:

> Aidos — weather-app: 3 sessions completed, 1 needs your approval

Two categories bypass the budget, and only two:

- **Approval requests** — a Run parked awaiting a capability grant or a tainted escalation
  (RFC-0027) is blocked until the user answers. Suppressing it means the work never finishes.
- **User-initiated completions** — the user asked for this and is waiting.

Everything else — background completions, index finished, informational updates — is
budget-bound and coalescible. The asymmetry is deliberate: *the user is interrupted only for
things they are blocking, or things they asked for.*

**Quiet hours** are honoured for everything except approvals to Runs the user started in the
current session. An agent that wakes someone at 03:00 because a background index finished has
failed, regardless of how correct the index is.

**Grouping.** Notifications group per project, so a user with six projects does not get six
independent notification streams.

### Cancellation and failure

- A scheduled job is cancelled by ID; cancellation is durable across restarts.
- Cancelling a job with a Run in flight cancels the Run cooperatively (RFC-0006): it stops at
  the next checkpoint.
- A job whose Run fails records the failure and **does not** retry on its own schedule — the
  next scheduled occurrence is the retry. Immediate retry of a failing scheduled job is how a
  recurring session becomes a spend loop.
- Three consecutive failures disable the job and notify the user once (mirroring the session
  failure budget, RFC-0011).

## Data Model

```sql
CREATE TABLE scheduled_jobs (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    session_id TEXT,
    name TEXT NOT NULL,
    trigger_json TEXT NOT NULL,
    guarantee_class TEXT NOT NULL,       -- PROMPT | EVENTUAL | OPPORTUNISTIC
    work_class TEXT NOT NULL,            -- INTERACTIVE | DEFERRED | SCHEDULED | OPPORTUNISTIC
    constraints_json TEXT NOT NULL DEFAULT '{}',
    enabled INTEGER NOT NULL DEFAULT 1,
    next_run_at TEXT,
    last_run_at TEXT,
    last_outcome TEXT,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    missed_occurrences INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);

CREATE INDEX idx_jobs_due ON scheduled_jobs(next_run_at) WHERE enabled = 1;

CREATE TABLE notifications (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    category TEXT NOT NULL,              -- APPROVAL | COMPLETION | INFORMATIONAL
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    coalesced_count INTEGER NOT NULL DEFAULT 1,
    delivered_at TEXT,
    acted_on_at TEXT,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);
```

## Security

1. **Notification content passes through the redactor** (RFC-0035). A notification is visible on
   a lock screen, which is the most publicly readable surface the runtime writes to. Secrets and
   `SENSITIVE` content never appear; a notification names the session and the outcome, not the
   content.
2. **Notification is a capability.** A session without `notify` cannot reach the user. Otherwise
   an injected instruction can use notifications to phish the user for approval.
3. **Approval prompts state what is being approved and why**, including the tainting source when
   applicable (RFC-0027). An approval prompt the user cannot evaluate is worse than none, because
   it trains dismissal.
4. **Scheduled jobs run under the session's existing capabilities**, and a scheduled Run cannot
   request new authority while unattended — it parks and waits for the user.

## MVP

1. `At`, `Every`, and `OnEvent` triggers with guarantee classes.
2. Interactive and deferred work classes; foreground service on Android.
3. Coalescing of missed occurrences with `missedOccurrences` exposed.
4. Notification categories, per-project hourly budget, coalescing, grouping, quiet hours.
5. `Notify` as `UNSAFE` — never auto-retried.
6. Durable cancellation; three-failure disable.

Not in MVP: `Cron` triggers, `OnCondition`, opportunistic constraints beyond charging, snooze.

## Future Work

Opportunistic scheduling driven by observed usage — running the morning review when the user
actually picks up their phone, rather than at a nominal hour.

Notification actions: approve or deny a capability directly from the notification, which for the
mobile use case removes the most common reason to open the app at all.

Calendar integration as a trigger source, for the life-management surfaces.
