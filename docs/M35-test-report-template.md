# G4 Scenario Test Report Template

**Milestone**: M35 (Gate G4 - MVP Complete)

Use this template to document each tester's experience. One report per tester.

---

## Test Session Metadata

| Field | Value |
|-------|-------|
| **Tester Name** | [Full name or "Anonymous"] |
| **Tester ID** | [e.g., "Tester-1", "Tester-2"] |
| **Test Date** | [Date of test session] |
| **Test Time** | [HH:MM - HH:MM UTC] |
| **Device Model** | [e.g., "Samsung Galaxy A52", "OnePlus Nord"] |
| **Android Version** | [e.g., "13.0", "14.0"] |
| **Battery Level** | [Start: _%, End: __%] |
| **Ambient Temp** | [~20°C] (optional) |
| **Test Location** | [Home / Office / Other] |
| **Airplane Mode** | ✅ ON (entire test) |
| **WiFi** | ✅ OFF |
| **Cellular** | ✅ OFF (via airplane mode) |
| **Test Repository** | [e.g., "Aidos main branch"] |

---

## Timeline & Metrics

### Phase Timing

| Phase | Time (MM:SS) | Notes |
|-------|--------------|-------|
| **Pre-test orientation call** | | Duration of call with coordinator |
| **App open to homescreen** | | Seconds to launch |
| **Navigate to / open project** | | Seconds to find existing project or create new one |
| **Send first message** | | Seconds to type and send |
| **First answer received** | | Seconds from send to first response token |
| **Read/digest answer** | | Seconds to read response |
| **Send follow-up (if any)** | | Seconds if tester asked 2nd question |
| **Make file edit** | | Seconds to navigate, edit, save |
| **Review diff** | | Seconds to open and understand diff |
| **Write commit message** | | Seconds to compose and enter message |
| **Confirm commit** | | Seconds to complete commit action |
| **Post-test interview** | | Duration of call |
| **TOTAL ACTIVE TEST** | | Excludes calls; core workflow only |

---

## Observations & Issues

### Successes (What Worked)
- Narrative (2-3 sentences per item):
  1. [Positive observation]
  2. [Positive observation]
  3. [Positive observation]

### Issues Encountered

| Severity | Category | Description | Workaround? | Impact |
|----------|----------|-------------|-------------|--------|
| Critical | [E.g., "App crash"] | [Describe] | [Yes/No] | [Blocked workflow / Minor inconvenience] |
| Major | | | | |
| Minor | | | | |

**Notes**:
- Did tester need external help? (Yes/No) — if Yes, what step?
- Did device get hot? (Yes/No) — note temperature if available
- Did battery drain significantly? (Yes/No) — estimate % drop
- Any permission prompts? (Yes/No) — tester confused by any?

---

## Subjective Comfort & Usability

### Comfort Rating (1-5)

**Question**: "On a scale of 1-5, how comfortable was this workflow?"

- **1** = "I wouldn't use this; too broken or confusing"
- **2** = "Possible, but frustrating; not daily-driver material"
- **3** = "Workable; I could use it if needed"
- **4** = "Good; I'd use this regularly"
- **5** = "Excellent; prefer this to other tools"

**Rating**: ___ / 5

**Confidence**: [High / Medium / Low] (how well did this represent real-world use)

---

### Workflow Intuitiveness

**Question**: Was the workflow (open app → project → ask → answer → edit → diff → commit) intuitive?

- [ ] Yes, figured it out without help
- [ ] Mostly, with minor confusion at one step
- [ ] Required some guidance
- [ ] Very confusing; needed help for multiple steps
- [ ] Couldn't complete workflow

**If not intuitive**: Which step(s) were unclear?
- [ ] Opening app
- [ ] Finding/creating project
- [ ] Asking a question
- [ ] Understanding the answer
- [ ] Making an edit
- [ ] Reviewing the diff
- [ ] Committing the change

---

### One-Handed Usability

**Question**: Could you reasonably do this workflow one-handed on a phone (e.g., standing on a bus)?

- [ ] Yes, easily
- [ ] Yes, with minor awkwardness (e.g., reaching top of screen)
- [ ] Possible but uncomfortable (would need two hands for some parts)
- [ ] Difficult; designed for two-handed tablet use
- [ ] Impossible one-handed

**Notes**: Specific gestures that are awkward, screen areas hard to reach, etc.

---

### Offline Requirement Validation

**Question**: Did it feel like a true offline experience?

- [ ] Yes, no internet needed; responsive locally
- [ ] Mostly; one or two moments that felt like it was waiting for network
- [ ] Unclear if offline; hard to tell if using internet
- [ ] Felt cloud-dependent

**If any network dependency perceived**: Describe the moment

---

## Qualitative Feedback

### Open-Ended Questions

**Q1: What was the hardest part of this workflow?**

[Tester's response in their own words]

**Q2: What surprised you (good or bad) about the app?**

[Tester's response]

**Q3: If you had to use this every day, what one thing would you change?**

[Tester's response]

**Q4: Would you recommend this to another developer?**

- [ ] Yes, enthusiastically ("I'd tell friends to try it")
- [ ] Yes, with caveats ("It works, but not for everyone")
- [ ] Maybe ("Depends on their use case")
- [ ] No ("Not ready for others yet")
- [ ] No ("Fundamental issues")

**Q5: Did this feel like using a phone app, or like using desktop software on a tiny screen?**

[Tester's response]

**Q6: Any other feedback?**

[Open ended]

---

## Thesis Validation

### Core Question

**Thesis Statement**: "A person opens a real Git repository on a mid-range Android phone, in airplane mode, asks a question about the code, gets a useful answer, makes an edit, reviews the diff, and commits."

**Question**: Does this describe your experience?

- [ ] Yes, exactly. The thesis is proven.
- [ ] Mostly. Minor steps didn't work, but overall thesis is sound.
- [ ] Partially. Some steps worked, others didn't.
- [ ] No. Major gaps between thesis and reality.

**Evidence** (1-2 sentences):

---

## Coordinator Notes

- [ ] Tester was external (not Aidos author/contributor)
- [ ] Tester had no prior knowledge of app design
- [ ] Airplane mode verified ON before and after test
- [ ] Tester completed all 8 core scenarios
- [ ] No external navigation/coaching beyond pre-test orientation
- [ ] Post-test interview completed
- [ ] Permission to quote feedback publicly: [ ] Yes [ ] No [ ] Anonymous only

**Coordinator Name**: _______________
**Coordinator Signature**: _______________
**Date Reviewed**: _______________

---

## Attachments

- [ ] Screenshots (phone state, diff view, commit screen)
- [ ] Video recording of app usage (if available)
- [ ] Call recording (pre/post call) (if available)
- [ ] Tester's written comments (if provided)

---

## Internal Use: Synthesis Tags

Tag feedback for aggregation across testers:

**UX Issues**:
- [ ] Diff viewer unclear
- [ ] Commit UI confusing
- [ ] Question phrasing confusing
- [ ] Navigation unclear
- [ ] [Other]

**Performance**:
- [ ] Slow model inference
- [ ] Slow app startup
- [ ] Slow diff computation
- [ ] Battery drain noticeable
- [ ] [Other]

**Offline Concerns**:
- [ ] App tried to use internet
- [ ] Unclear if data went to cloud
- [ ] [Other]

**Thesis Alignment**:
- ✅ Thesis validated
- ⚠️ Thesis mostly valid, minor issues
- ❌ Thesis not validated

