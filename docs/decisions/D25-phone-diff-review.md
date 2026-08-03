# D25 — Reviewing a diff on a phone

Status: **RECOMMENDED** (2026-08-03) — long-form analysis. Not yet settled.

**Recommendation: review moves earlier (approve each mutation as it happens, which the
architecture already requires) and what remains is reviewed hunk-by-hunk as a card stack. The
Runtime API exposes structured hunks with stable identity, not a formatted diff string. Raw
unified diff stays available as a fallback view. Model-summarized diffs are deferred.**

## The question

M31 says: *read a diff, stage, write a message, commit — comfortably, on a phone screen, with
one hand, on a bus. This is the actual product.*

RFC-0050 says, in full:

```
Git Browser (Optional):
  - Commit log
  - Branch view
  - Diff viewer
  - Blame view (future)
```

The most important screen in the product is a bullet inside a section marked *optional*. That is
the finding, and it is the same failure mode the third architecture review kept turning up: the
hard part gets one line because one line is all that fits in a plan that has not tried it yet.

## Why this is not a Phase 4 question

The instinct is to defer it: it is UI, the Android app is Phase 4, decide it then.

That is wrong, and the reason is worth being precise about. **How the phone reviews a diff
determines what the Runtime API and the Git tool must return, and those are built in Phase 2.**

- If review is a scrollable unified diff, the API returns `diff(): String` and the phone renders
  text.
- If review is per-hunk with staging decisions, the API returns hunks with **stable identity**,
  supports staging a subset, and must say what happens when the working tree changes underneath
  a half-reviewed diff.
- If review is semantic summaries, the API needs a summarizer, a place to cache it, and an answer
  to who pays for the inference.

Ship `diff(): String` in Phase 2 and the Android app inherits a diff parser it should never have
contained — parsing the runtime's own output back into structure on the client, on the device
with the least CPU, in the language furthest from JGit. Every frontend then reimplements it.

So: this is an M9/M13 decision that happens to become visible at M31.

## What the architecture already answers

The most useful observation is that **the big-diff-at-the-end problem is partly self-inflicted.**

RFC-0030 and RFC-0018 already require a `Preview` for every `EffectKind.Mutate`, and RFC-0027
requires approval when a Run is tainted or the mutation leaves the project. The user is
therefore already shown each edit, one at a time, in the context of the step that proposed it,
*before* it happens — a `Preview.Diff` per mutation, which for a typical agent edit is a handful
of lines.

That is a far better review surface than a 300-line diff at commit time, and it exists for
security reasons rather than usability ones. Reviewing 12 small changes as they arrive, each with
the model's stated reason next to it, is both easier and more informative than reviewing their
sum an hour later with the reasons gone.

The consequence for the commit screen: it is mostly a **confirmation**, not an inspection.

```
Commit  ·  8 files, 213 lines
  ✓ 11 changes you approved as they happened
  ! 2 changes not individually reviewed
        ├─ pulled from origin/main
        └─ edited directly in the editor
```

The unreviewed set is the one that needs line-level attention, and it is usually small. The
reviewed set needs to be *visible* — the user must be able to open any of it — but it does not
need to be read again.

This reframes the problem from "how do we make a 300-line diff readable on a phone" to "how do we
make the residue readable", which is a much smaller problem and the one worth optimizing.

It does not eliminate the hard case. Three situations still produce a large unreviewed diff:

1. A worker fan-out ran unattended (RFC-0006) and the user reviews the merged result.
2. A `git pull` brought in someone else's work.
3. The user turned off per-mutation approval for a trusted project, which they will, because
   approving every edit is exhausting and people optimize for the common case.

(3) is the one to take seriously. It is not a misuse; it is what the feature is for, and the
design must not assume the incremental path is always taken.

## Options for the residue

### A — Unified diff, monospace, scrollable

What mobile Git clients do today.

**For:** free. The Git tool produces it anyway; the API returns a string; the view is a
`Text` in a scroll container. Familiar to anyone who uses `git diff`.

**Against:** a diff line is up to ~120 columns and a phone shows ~40 of them at a readable size.
Horizontal scrolling per line, or wrapping that destroys the alignment that makes a diff legible.
Two hands, constant scrolling, and no notion of progress. It fails the M31 sentence on every
clause.

**Verdict:** necessary as a fallback — a power user will want the raw thing, and it costs almost
nothing — but it cannot be the primary surface.

### B — Hunk card stack

The diff is decomposed into hunks. Each hunk is one card, sized to one screen. The user moves
through them: keep, skip, or revert this hunk. A counter says "4 of 11".

This is `git add -p` as a phone UI, and the fit is better than it first appears. `add -p` was
designed for exactly this task — review a change piece by piece and decide about each piece —
and it is inherently sequential and single-item, which is what a small screen is good at. The
interaction reduces to one thumb-reachable control per card, and progress is legible at a glance,
which is what makes an interruptible task tolerable on a bus.

**For:** meets the M31 sentence. Gives per-hunk staging for free, which is genuinely useful when
an agent got three things right and one wrong. Bounded work per screen. Degrades gracefully — a
hunk longer than a screen scrolls vertically only, because a hunk is narrow by nature.

**Against, and this is a real cost:** JGit has no hunk-level staging. `DiffFormatter` yields an
`EditList`, but applying a *subset* of edits means constructing the resulting blob by hand and
writing it — building the file content from base plus selected edits, then adding that blob to
the index. That is genuine work at M13, not a wrapper call, and it needs its own tests around
the cases that break it: overlapping edits, CRLF, no-newline-at-EOF, binary files, renames,
and mode changes.

Hunk **identity** also has to be defined. A hunk is not a stable object — it is derived from a
diff of two states, and if the working tree changes mid-review the hunks renumber. Proposal:
identify a hunk by `(path, base blob hash, hunk index)` and invalidate the whole review when the
base moves, with a visible "the file changed, restarting review" rather than a silent renumber.
Silent renumbering during a partial staging operation is how a user stages the wrong lines.

### C — Semantic summaries

"3 files changed: added retry to `HttpClient`, updated its test, bumped a timeout constant."
Each summary expands to its hunks.

**For:** by far the largest reduction in reading load, and the closest to how a person actually
reviews a colleague's change.

**Against:** two objections, one soft and one hard.

The soft one is cost: it is a model call, offline, on a phone, at the exact moment the user is
waiting to commit. Ten seconds of local inference before you can press the button is worse than
scrolling.

The hard one is D6 — *the model may propose, run, and report, but never confirm its own
success.* A model summarizing the diff it just produced is the model reporting on its own work,
and the summary is the thing the user would act on. It is not disqualifying, because the real
diff stays one tap away and the commit is made against the diff rather than the summary. But it
is exactly the position where a wrong summary is most costly and least likely to be checked, and
"the user could have looked" is the argument that makes every such feature sound safe.

**Verdict:** defer. Revisit after G4, when there is a real device, a real model, and a measurable
answer to whether the summary is trustworthy enough to change behaviour.

### D — Do not review at commit time at all

Rely entirely on per-mutation approval; the commit screen is a list of approved changes and a
message box.

**For:** simplest possible commit screen. Correct for the incremental path.

**Against:** (1), (2), and (3) above. A user who disabled per-mutation approval would have no
review surface at all, which is a security regression dressed as a simplification.

**Verdict:** correct as the *framing* — review should move earlier — and wrong as the *whole*
answer.

## Recommendation

**Adopt D as the framing and B as the mechanism, keep A as a fallback view, defer C.**

Concretely:

1. **Per-mutation `Preview` is the primary review surface.** It already exists for security
   reasons; the commit screen should exploit it rather than duplicate it.
2. **The commit screen distinguishes reviewed from unreviewed changes** and directs attention at
   the unreviewed set. This requires the Run to record which previews were approved — a column,
   not a subsystem.
3. **Line-level review is a hunk card stack.** One hunk per screen, keep/skip/revert, visible
   progress.
4. **Raw unified diff is available in one tap** for anyone who wants it.
5. **The Runtime API returns structured hunks, not a diff string.** Decided at M9, implemented in
   the Git tool at M13, consumed at M31.

## What this costs

| Item | Where | Cost |
|---|---|---|
| Structured hunk model in the Runtime API | M9 | small — a data class and a method shape, if decided now |
| Hunk extraction from JGit `EditList` | M13 | small — JGit provides the edits |
| Applying a subset of hunks to the index | M13 | **real** — manual blob construction plus edge-case tests |
| Recording which previews were approved | M11 | small — one column, written where approval is already recorded |
| Commit screen with reviewed/unreviewed split | M31 | small, given the above |
| Hunk card stack UI | M31 | moderate — it is the screen that has to be right |

The one line to flag: hunk-level staging is the only item here that is not cheap, and it is the
one that makes the difference between a diff you can read and a diff you can act on. If it has
to be cut, cut *staging* and keep the card stack for reading — reviewing hunk-by-hunk and then
committing all of it is still far better than a wall of text.

## What would change this decision

- Per-mutation approval turns out to be so tedious that everyone disables it. Then the commit
  screen carries the full review load and C becomes worth its cost.
- JGit subset-staging proves harder than estimated at M13. Then ship the card stack read-only and
  revisit.
- G3 shows that phone sessions produce much larger diffs than expected, because the model edits
  broadly rather than surgically. Then the problem is the *agent's* granularity, not the review
  UI, and the fix is upstream.

## Open

- Should reverting a hunk be an *edit* (a mutation requiring its own approval and audit record)
  or a UI-level undo of a change never committed? Leaning edit — it changes the working tree, and
  RFC-0030 does not have a category for changes that do not count.
- Does the reviewed/unreviewed distinction survive a rebase or an amend? Probably not, and the
  honest behaviour is to mark everything unreviewed again rather than track it through a history
  rewrite.
