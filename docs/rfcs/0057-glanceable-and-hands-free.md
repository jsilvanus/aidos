# RFC-0057: Glanceable and Hands-Free Operation

Status: Accepted 2026-08-03

## Abstract

The use case is development on the move: dictate an intent while walking, glance at what
happened while waiting for a light, approve something while standing on a platform, and hear
what happened while cycling. This RFC defines the **Run Summary** — a projection of the
Execution Graph, computed from rows rather than written by a model — and its three densities:
a strip, a one-page glance, and a spoken form. It also defines which approvals may be given at a
glance or by voice, and which may not, because approving without reading is exactly the failure
the capability model exists to prevent.

## Motivation

The phone is where the spare time is, but the spare time comes with the user's attention already
spent on something else. Three modes, and the product is only real if all three work:

| Mode | Attention available | Example |
|---|---|---|
| **Focused** | full, both hands | sitting down, reviewing a diff hunk by hunk |
| **Glance** | two seconds, one hand | waiting for a light, standing on a platform |
| **Eyes-free** | none visual, audio only | cycling, driving, walking with the phone pocketed |

Everything specified so far assumes focused. The session timeline (RFC-0050) is a scrolling list
of steps — correct when reading, useless at a glance. The approval card shows a diff — right when
sitting, wrong at a crossing. And nothing addresses eyes-free at all.

The gap matters more than it looks, because the *input* side is already solved for these modes.
Voice capture means an intent can be dictated while walking. What comes back cannot be a
scrolling transcript, or the interaction is asymmetric: speak in one second, read for two
minutes.

## Goals

1. Define a Run Summary that fits one screen without scrolling and is computed, not generated.
2. Define the three densities it renders as, from a single projection.
3. Define what may be collapsed and what never may.
4. Define which approvals are safe to give at a glance or by voice — and which are not.
5. Keep all of it working offline, with no inference.

## Non-goals

This RFC does not specify visual design or Compose implementation (RFC-0050).

This RFC does not define voice *commands* as a control vocabulary. Dictation is the input model
(RFC-0050); the only spoken control surface here is answering an approval.

This RFC does not define wake words or always-on listening. Both are privacy-hostile and
neither is needed.

This RFC does not define model-authored summaries. See "Why this is not a model call" below.

## Design

### The Run Summary is a projection, not a generation

**The Execution Graph is structured data.** Everything a glanceable summary needs is already in
rows: Task kinds and states, tool names, the paths and line counts inside each `Preview.Diff`,
the capabilities exercised, error classes, budget consumed, and what the Run is parked on.

So the summary is a **query**, not an inference:

```sql
-- Run summary: one row of headline state, plus per-file change totals.
SELECT r.state, r.step_index, r.max_steps, r.taint_level,
       r.error_class, r.instruction_set_hash,
       COUNT(DISTINCT t.id)                     AS tasks,
       SUM(a.tokens_input + a.tokens_output)    AS tokens,
       SUM(a.cost_units)                        AS cost
FROM runs r
LEFT JOIN tasks t    ON t.run_id  = r.id
LEFT JOIN attempts a ON a.task_id = t.id
WHERE r.id = ?
GROUP BY r.id;
```

Per-file totals come from `tool_calls` joined to the previews recorded for each `Mutate`, and
the pending set from Tasks in `AWAITING_APPROVAL`, `AWAITING_INPUT`, or parked on
`ForegroundRequired`.

#### Why this is not a model call

Asking the model to summarize what it just did is the model reporting on its own work — **D6**,
the same objection that deferred model-summarized diffs in D25. It is worse here than there,
because a glance summary is consumed *instead of* the detail rather than alongside it: the whole
point is that the user does not open the steps.

Three further reasons, any one sufficient:

- **Cost at the wrong moment.** An inference between "picked up phone" and "saw state" is ten
  seconds of a two-second interaction.
- **It would not work offline in the case that matters.** On a bike with no signal and no
  foreground service, a model call parks (D24). A projection does not.
- **It is not auditable.** A generated sentence cannot be checked against anything. A projection
  is the same rows the audit trail reads, so "why did it say that" has an answer.

A model-authored *gloss* — "added retry logic" rather than "`HttpClient.kt +31 −4`" — is real
added value and is deliberately Future Work, on the condition that it is visibly marked as
model-authored and never the thing an approval is given against.

### Three densities, one projection

**Strip** — notification, lock screen, wearable. Eight words, no detail:

```
aidos · paused · 3 files · needs approval
```

**Page** — one screen, no scrolling, thumb-reachable actions:

```
  aidos                                    4m ago
  ────────────────────────────────────────────────
   ⏸  paused · 6 of 24 steps · trusted
  ────────────────────────────────────────────────
   3 files                            +47   −12
     src/http/Client.kt               +31    −4
     src/http/Retry.kt                +16     0
     build.gradle.kts                   0    −8
  ────────────────────────────────────────────────
   !  approve — write outside src/
   ○  2 model calls · 18k tokens · local
  ────────────────────────────────────────────────
        [ approve ]            [ open ]
```

**Spoken** — templated from the same fields, no model:

> *"Aidos is paused on the aidos project, six steps in. It changed three files in the HTTP
> client — forty-seven lines added, twelve removed. One thing needs you: writing to
> `build.gradle.kts`, which is outside the source tree. Say approve, say skip, or say details."*

The template is deterministic and its slots are structured values. Numbers are spoken as
quantities, paths as paths. Nothing in the spoken form is free text from a model or from a file.

### What collapses and what never collapses

A Run of forty steps must still fit one page, so the projection groups aggressively — but
grouping is where a summary becomes a lie, so the rules are explicit.

**Collapses into counts:** repeated tool calls of the same kind, `Read` effects, retried attempts
that eventually succeeded, model calls, token and cost totals.

**Never collapses:**

| Always shown | Why |
|---|---|
| Anything pending the user | It is the reason the user is looking |
| Every error, by class | A Run that failed twice and succeeded is not the same as one that succeeded |
| Every `Egress` | Data left the device. That is never a footnote |
| Every out-of-project mutation | The blast radius exceeded the project |
| Any `INDETERMINATE` outcome | An `UNSAFE` effect whose result is unknown must not render as done |
| Taint, when not `TRUSTED` | It changes what the Run is allowed to do next |

**The summary states its own incompleteness.** A `RUNNING` Run reads *"so far"*. A `FAILED` Run
shows what happened plus the error, never a completion claim. This is D6 again at the
presentation layer: the graph records what was attempted and what was observed, and the summary
may not round that up.

### Glanceable approval: the benign class

Approving without reading is what the capability model exists to prevent, so most approvals must
not be glanceable. But treating them all alike means either no glance approvals at all, or
training the user to tap through everything — and the second is worse than the first.

The architecture already carries the classifier. An approval is **benign** when *all* hold:

```
effect      is Read, or Mutate(IN_PROJECT)
recovery    is not UNSAFE
run.taint   is TRUSTED
capability  is already granted; this is an exercise, not a new grant
```

Benign approvals render as a one-line strip with two buttons and may be given at a glance.
Everything else — egress, out-of-project mutation, `UNSAFE` effects, a tainted Run, a new
capability grant — renders the full card with its preview and its reason, and says plainly that
it needs the user's eyes. It does not become approvable by walking closer to it.

This is not a new policy. It is the same signal set RFC-0027 uses to decide what needs approval
at all, applied one level further to decide what needs *reading*.

### Voice, and what voice may not do

**In:** microphone → local STT → editable transcript → send (RFC-0050). Voice is for dictating
intent, which is the case where a phone genuinely beats a laptop.

**Out:** the spoken Run Summary, on demand or on a terminal event, through a local TTS model
(`ModelKind.TTS`). Availability follows RFC-0049 — where no TTS model is installed, the feature
is absent rather than degraded, and the user is told which model kind is missing.

**Voice may answer a benign approval. Voice may not do anything else.** Not a new capability
grant, not an egress, not an out-of-project write, not a destructive operation. The reason is
not that speech recognition is unreliable, though it is: it is that the eyes-free context is
precisely the one where the user cannot check what they are agreeing to, and an approval given
without the preview is not an approval.

Where a spoken request falls outside the benign class, the response is the same as with no
foreground service: **park and say so.** *"That one needs your eyes — it writes outside the
project. It will be waiting."*

### Injection through the speaker

A hazard specific to this RFC and worth naming, because it is easy to build wrong.

If spoken output can include project content or model output, then a hostile repository can craft
text that, read aloud to a user who cannot see the screen, sounds like a request they would
approve — prompt injection aimed at the human rather than the model. The user says "approve" to
a sentence the attacker wrote.

**Mitigation, structural rather than advisory:** the spoken form of an approval is composed
**only** from structured fields the runtime owns — tool name, effect kind, path, mutation scope,
taint level. Model output and file content are never read aloud inside an approval prompt. A
user may ask for details and hear content, but that is a *reading* action and it never carries an
approve affordance in the same breath.

This is the audio analogue of RFC-0025's rule that untrusted content never enters the system
turn, and it holds for the same reason: the boundary must be structural, because the party being
asked to enforce it cannot be relied on to notice.

### Everything here works offline

No part of this requires network. The projection is a local query. The templates are local
strings. TTS and STT are local models at user scope (RFC-0054). This is deliberate: the eyes-free
mode is the one most likely to happen with no signal, and a hands-free interface that stops
working on a train is not one anybody will rely on.

## Data Model

**No new tables.** The Run Summary is a projection over `runs`, `tasks`, `attempts`,
`tool_calls`, and the recorded previews. It is computed on read and never stored — a stored
summary is a cache of derived state that goes stale exactly when a Run changes, which is
constantly.

One setting per user (RFC-0036):

```
speech.tts_model_id      -- which local TTS voice, or null for none
speech.summary_on_finish -- speak a terminal summary automatically
speech.voice_approvals   -- allow answering benign approvals by voice (default off)
```

`speech.voice_approvals` defaults **off**. A capability answered by voice is a meaningful
extension of how authority can be exercised, and it should be something the user turned on.

## Security

| Threat | Mitigation |
|---|---|
| User approves without reading, at a glance | Only the benign class is glanceable; everything else requires the full card |
| User approves by voice what they cannot see | Same classifier, plus `speech.voice_approvals` off by default |
| Hostile repository content read aloud inside an approval prompt | Spoken approvals are composed from runtime-owned structured fields only |
| Summary overstates what happened | Never-collapse list; `INDETERMINATE` renders as indeterminate; a `RUNNING` Run reads "so far" |
| Speech recognition mishears "approve" | Benign class only, and a benign approval is by construction in-project, reversible, and untainted |
| Spoken output leaks secrets in a public place | Secrets never appear in events or previews (RFC-0035), so they are not in the projection either |

## MVP

1. The Run Summary projection, with the collapse rules.
2. Strip and page densities.
3. The benign-approval classifier, used by both the glance strip and the full card.
4. Spoken summaries via local TTS, where a TTS model is installed.
5. `speech.*` settings, with voice approvals off by default.

Spoken summaries ship with Phase 4 alongside voice capture (M33) and are cut with it if the
phase slips. **The projection and the benign classifier are not cuttable** — the page density is
the primary way a user sees what happened, and the classifier is a security boundary that the
approval card needs whether or not anything is ever spoken.

## Future Work

- **Model-authored gloss** over the projection — "added retry logic" rather than a file list —
  marked as model-authored, never the thing an approval is given against, and cached by the same
  content hash the diff uses.
- **Wearable inbox.** Approve or reject a benign request from the wrist. The classifier is what
  makes this expressible without it being reckless.
- **Spoken diff review.** Reading a hunk aloud is plausible for small hunks and bad for large
  ones; needs a size threshold nobody has measured yet.
- **Earcons** — distinct short sounds for finished, needs-you, and failed, so the common case
  needs no speech at all.
- **Summary of a session**, not just a Run: "three Runs since yesterday, two committed."

## Open Questions

- Should the page density be per-Run or per-session? A session with four Runs has no single
  headline state, and picking one would be the kind of rounding this RFC otherwise forbids.
- Is "so far" enough for a `RUNNING` Run, or should an in-flight summary refuse to show change
  totals that a subsequent step may revert?
- Does a benign approval given by voice need a different audit marker than one given by tap?
  Leaning yes — the exercise is the same but the assurance is not, and the audit log is where
  that difference would matter later.
