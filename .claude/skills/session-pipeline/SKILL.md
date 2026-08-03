---
name: session-pipeline
description: Keep a long-running task alive across Claude Code session limits by chaining self-scheduled wakeups. Use when the user gives a start time and a long task (implement a plan, work toward a goal, simplify, code-review, create or update a PR) that will outlast a single session. Trigger on "session-pipeline", "wake at", "keep working across sessions", or any task expected to span more than one session window.
---

# Session pipeline

Long tasks outlive session limits. This skill keeps one alive by chaining wakeups: every time
you wake, you immediately schedule the next wakeup, *then* work. The chain survives even if a
session ends mid-task, because the next link was scheduled before the work started.

## The rule

**Schedule the next wakeup before doing anything else. No exceptions.**

Not after a quick status check. Not after "just finishing this one edit." First action of the
turn, before reading files, before `git status`, before thinking about the task.

The reason is the whole point of the skill: if the session hits its limit while you are working,
everything you did is preserved by the commit, but the *chain is broken* and nothing resumes.
Scheduling first costs one tool call and makes the chain unbreakable.

## Procedure

### On wake

```
1. Schedule the next wakeup            ← FIRST. always.
2. Re-orient
3. Work
4. Commit and push
5. End the turn
```

**Step 1 — schedule.**

```
mcp__Claude_Code_Remote__send_later(
    delay_minutes = 305,
    message = "<the same pipeline instruction, verbatim>"
)
```

305 minutes, not 300 — the margin absorbs clock drift and a slow start without letting two links
overlap.

The message must be **self-contained**. Assume the next session starts with no memory of this
one: state the repo, the branch, the task, where the plan lives, and the pipeline rule itself.
Carry the same text forward each time so the chain does not degrade.

**Step 2 — re-orient.** You may be resuming cold.

```bash
git status --short && git log --oneline -5
```

Then read the task's plan or tracking document. Do not re-derive what earlier links established
— read what they wrote down.

**Step 3 — work.** Make real progress on one coherent piece. Do not try to finish everything
before the next wakeup; the chain exists so you do not have to.

**Step 4 — commit and push.** Every link ends with pushed work. Uncommitted work is lost when the
container is reclaimed, and the container is reclaimed between links.

If a PR is in play, update it to reflect everything done so far — not just this link's changes.
The PR is the deliverable, and it should be accurate at the end of every link, not only at the
end of the chain.

**Step 5 — end the turn.** Do not poll, do not sleep, do not spin waiting for the next wakeup.
The scheduled message will arrive.

### On the first invocation (a start time was given)

If the user gave a start time rather than "now":

1. Schedule the **first** wakeup for that time with `send_later`.
2. Confirm the scheduled time back to the user.
3. Stop. Do not begin the work.

The pipeline starts when the first wakeup fires, not when it is set up.

## Keeping state between links

Each link is a fresh session. Anything not written down is gone.

Maintain a tracking document in the repo — `PIPELINE.md`, or whatever the task names — and update
it in the same commit as the work:

```markdown
## Goal
<one paragraph, unchanged across links>

## Status
Link 4 · <date>

## Done
- [x] ...
- [x] ...

## Next
- [ ] <the single most useful next thing>

## Notes for the next link
<decisions made, dead ends found, anything that would otherwise be re-derived>
```

"Notes for the next link" is the highest-value section. It is where a link tells the next one
what it learned the hard way, and it is what stops the chain from rediscovering the same dead end
four times.

## Stopping

Stop the chain — do not schedule another wakeup — when:

- the goal is met and the work is pushed;
- the PR is merged;
- the user says stop;
- you are blocked on something only the user can resolve.

When stopping because of a block, **say so explicitly in the final message and in the tracking
document.** A silent stop is indistinguishable from a broken chain.

If a wakeup fires and the goal is already met, do not invent work. Confirm completion and stop.

## Failure modes

**Working before scheduling.** The one that defeats the skill. If the session ends mid-work,
nothing resumes and the user finds out hours later.

**Scheduling twice.** One wakeup per link. Two overlapping chains produce concurrent sessions on
the same branch, conflicting pushes, and duplicated work.

**A non-self-contained message.** The next session has no context. "Continue the work" resumes
nothing.

**Ending a link without pushing.** The container is reclaimed between links. Unpushed work is
gone.

**Trying to finish everything in one link** to avoid needing the chain. Produces rushed work and
often runs out of session anyway, mid-edit.

## Message template

```
SESSION PIPELINE — link N.

FIRST ACTION: schedule the next wakeup.
  send_later(delay_minutes = 305, message = <this message, with N incremented>)
Do this before reading files, before git status, before anything.

Repo:    <path>
Branch:  <branch>
Plan:    <path to plan or tracking doc>
PR:      <url, if one exists>

Goal: <one paragraph>

Then: re-orient (git status, git log, read the tracking doc), make progress on the
next item, commit, push, update the PR to reflect all work done so far, update the
tracking doc including "Notes for the next link", and end the turn.

Stop the chain — schedule nothing further — if the goal is met, the PR is merged,
or you are blocked on the user. Say which, explicitly.
```
