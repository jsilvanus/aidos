# M35: End-to-End Scenario Testing — Tester Recruitment Guide

**Milestone**: M35 (Gate G4 - MVP Complete)

**Objective**: Validate that a real person (not the author, not a script) can successfully use Aidos to accomplish the MVP thesis scenario on a real Android phone.

---

## Thesis Statement (What We're Testing)

> "A person opens a real Git repository on a mid-range Android phone, in airplane mode, asks a question about the code, gets a useful answer, makes an edit, reviews the diff, and commits."

This is not a test of the author's ability to use their own app. It's a test of whether someone picking up Aidos for the first time can accomplish the core workflow intuitively.

---

## Ideal Tester Profile

We need **2-3 external testers** with these characteristics:

### Hard Requirements
- ✅ **Not the Aidos author** — A different person entirely
- ✅ **Not a script/automation** — A human making real decisions
- ✅ **Mid-range Android phone available** (Snapdragon 600-700 series preferred)
  - Examples: Samsung A52, A53, OnePlus Nord, Pixel 6a
  - Budget: ~$200-400 USD
  - Android 8.0+ (API 26+)
  - 2GB free storage minimum
- ✅ **Intermediate+ developer** (comfortable with Git, terminal, coding)
  - Has used Git before (understands branches, commits, diffs)
  - Can read code and understand variable names, function calls
  - Familiar with Android or mobile development (bonus, not required)
- ✅ **Willing to spend 45-60 minutes** on the test
- ✅ **Can join a short call** (30 min pre-test orientation + 30 min post-test interview)
- ✅ **Located in compatible timezone** for scheduling

### Nice-to-Have
- Experience with code review tools or diff viewers
- Interest in offline-first or privacy-preserving software
- Previous experience testing early-stage apps
- Feedback on UX/usability (not just "does it work")

### NOT a Good Fit
- The Aidos author or core contributors
- Automated test scripts or CI jobs
- People who have seen the codebase or app design in detail
- Mobile gaming testers or QA professionals (will judge by different standards)

---

## Recruitment Strategy

### Where to Find Testers

1. **Open-Source Community**
   - Post on r/androiddev, r/opensource, r/rust (Kotlin/JVM audience)
   - Ask on Mastodon #foss #opensource #android
   - Contact maintainers of similar projects (Git clients, code editors, etc.)

2. **Personal Network**
   - Developer friends/colleagues (but not from Aidos team)
   - Local developer meetups or online communities
   - University CS programs (grad students, senior undergrads)

3. **Structured Recruitment**
   - GitHub Discussions / Announcements
   - Hacker News Show HN (if available)
   - Beta testing communities (TestFlight alternative)

### Recruitment Message Template

```
Subject: Help test Aidos (Android app for offline Git coding)

Hi [name],

We're finishing the MVP for Aidos, an offline-first AI assistant for code on your phone.

We're looking for 2-3 external testers (not our team) to validate that the core workflow works well:
- Open a Git repo on your phone (offline)
- Ask AI questions about the code
- Make edits, review the diff, commit

**Time commitment**: ~90 minutes total
- 30 min pre-test call (orientation, setup)
- 30-60 min using the app on your phone
- 30 min post-test interview (your feedback)

**Requirements**:
- Android phone (Snapdragon 600-700, e.g., Samsung A52, OnePlus Nord)
- Intermediate+ developer (comfortable with Git)
- Never seen the Aidos codebase before

Interested? Reply with:
- Your timezone
- Phone model
- Available times next week/two weeks

We'll provide:
- Full orientation (you're learning fresh, like any user)
- Git repo to use for testing
- Clear task (no ambiguity)
- APK or F-Droid link

Your feedback is the final gate before MVP launch. No preparation needed — just your perspective as a real user.

Thanks,
[Aidos team]
```

---

## Selection Criteria (Filtering)

After recruitment, select testers based on:

1. **Tester Diversity**
   - Different backgrounds (different Git workflows, coding styles)
   - Different phone models (still mid-range, but different OEMs)
   - Different timezones (if recruiting globally)

2. **Availability**
   - Can complete test within 1-2 weeks of MVP
   - Time zone compatibility with coordination

3. **Minimal Dependencies**
   - Has phone ready (not "buying one")
   - Can commit to the full 90 minutes (not split across days)
   - Reliable internet for pre/post calls (good video/audio)

---

## Logistics

### Communication
- Use email or GitHub Discussions for initial contact
- Schedule call via Zoom, Google Meet, or similar
- Send calendar invite with time zone included
- Confirm 24 hours before test

### Device & Environment
- Pre-test call: walk through device setup (offline mode, storage)
- Provide Git repo (Aidos repo itself is good; medium size, clear code)
- Provide APK or F-Droid link (depends on M34 status)

### Data & Privacy
- Recording: Ask permission before recording calls (optional)
- Data: Explain that app runs locally; no data sent to cloud
- Feedback: Testers own their feedback; it's public data for improving Aidos

---

## Success Metrics

A test is **successful** if the tester:

1. **Completes the workflow** without unblocking from the Aidos team
   - Opens app → creates/opens project → sends message → gets answer → edits → reviews → commits
2. **Reports comfort level ≥ 3/5** ("I could use this regularly")
3. **Encounters no crashes** (minor UI glitches OK; process death is not)

A test **fails** if:
- Core workflow is blocked (e.g., can't open project, can't commit)
- Tester quits before completing core steps
- Tester's comfort rating < 2/5

---

## Coordination Timeline

- **Week 1**: Recruit 2-3 testers, confirm availability
- **Week 2**: Send orientation materials, confirm device prep
- **Week 3-4**: Run tests (1-2 per day, 30min pre-test + 30-60min app + 30min interview)
- **Week 4**: Synthesize feedback, create G4 report

---

## Tester Rights & Responsibilities

### Tester Rights
- Free APK / F-Droid access
- Compensation optional (Aidos swag, GitHub shoutout, coffee ☕)
- Right to privacy (optional anonymity in public report)
- Right to withdraw without explanation
- Right to publicly share their experience

### Tester Responsibilities
- Appear on time for pre/post-test calls
- Complete the full scenario (or document why you stopped)
- Provide honest feedback (both good and bad)
- Respect the app's early-stage status (expect rough edges)
- Keep the APK confidential until M34 public release

---

## Notes for Coordination

1. **Avoid Leading**: In the pre-test call, show the app for 2 minutes ("here's the inbox, here's a project") but don't teach how to use it. Let them explore.

2. **Neutral Ground**: Run tests on their own device, in their own environment (home, office, etc.), not yours.

3. **Record Interactions**: Screenshot times, messages sent, diffs reviewed — useful for synthesizing later. Video recording of calls optional but helpful.

4. **One-Handed Use**: Remind testers to try the core interactions (diff review, commit) with one hand, on a bus or while standing. This is the real-world constraint.

5. **Offline Requirement**: Emphasize airplane mode is on. If internet was available, results would not test the thesis.

---

## Follow-Up

After each test:
- Send thank-you email within 24 hours
- Include summary of their feedback
- Offer to share G4 report when complete
- Ask permission to quote them in public report (with name or anonymous)
