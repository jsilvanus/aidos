# M35: Test Environment & Device Preparation Checklist

**Milestone**: M35 (Gate G4 - MVP Complete)

**Purpose**: Standardize the test environment so results are comparable and reliable.

---

## Pre-Test Device Checklist (For Testers)

### 1. Device Setup
- [ ] Android 8.0+ (API 26+) installed
- [ ] Device is a mid-range phone (Snapdragon 600-700 series, if available)
- [ ] At least 2GB free storage
- [ ] At least 50% battery (or plugged in for test)
- [ ] Device is fully charged before test (optimal scenario)

### 2. Network & Connectivity
- [ ] Device supports airplane mode
- [ ] **Airplane mode will be ENABLED for the entire test**
- [ ] WiFi will be OFF
- [ ] Bluetooth can remain ON (not critical for Aidos)
- [ ] All background apps closed or minimized
- [ ] No automatic app updates or sync services running

### 3. App Installation
- [ ] Aidos APK installed (from link provided)
  - Via: F-Droid (if M34 published) OR sideload APK link
- [ ] App launches without error
- [ ] App requests permissions (show to tester: storage, internet) — approve them
- [ ] No error messages on first launch

### 4. Test Repository
- [ ] Git repository cloned to device or accessible via app
- [ ] Recommended repo: Linux kernel, Kubernetes, or Aidos itself
  - Medium size (5k-20k files)
  - Clear naming (easy to ask questions)
  - No confidential/proprietary content
- [ ] Tester has read access to repo (already checked out or via Git URL)

### 5. Call Setup (Pre-Test)
- [ ] Video call connection working (Zoom, Google Meet, etc.)
- [ ] Audio clear on both ends
- [ ] Screen share capability tested (optional; for documentation)
- [ ] Tester can hold phone and chat at same time (important!)

---

## Test Environment (Phone State During Test)

### Display & Input
- [ ] Screen stays on (prevent screen timeout)
  - Developer options → Stay awake while plugged in (ideal)
  - OR: Tester holds phone the whole time
- [ ] Phone is in portrait orientation (as usual)
- [ ] Screen brightness set to comfortable level

### Connectivity (CRITICAL)
- [ ] **Airplane mode is ON** ✅
- [ ] WiFi is OFF ✅
- [ ] Cellular is OFF (side effect of airplane mode) ✅
- [ ] No VPN connections active
- [ ] Verify: Go to Settings → Airplane mode → toggle to ON

### Performance Monitoring (Optional)
- [ ] Android Developer options enabled (optional)
- [ ] Performance monitoring tools (battery, thermal state) available
- [ ] Tester notes any thermal throttling or battery drain during test

### Testing Scenarios
- [ ] **Scenario 1**: Open app → navigate to projects
- [ ] **Scenario 2**: Create project or open existing Git repo
- [ ] **Scenario 3**: Ask AI question about the code
  - Example: "What does the main() function in main.rs do?"
  - Example: "Find all error handling in the HTTP client"
- [ ] **Scenario 4**: Receive answer (may take 5-30 seconds; local inference)
- [ ] **Scenario 5**: Ask follow-up question (optional)
- [ ] **Scenario 6**: Make an edit to a source file
  - Example: Add a comment, fix a typo, refactor one line
- [ ] **Scenario 7**: Review the diff before committing
  - Inspect additions/deletions with diff viewer
  - Verify change is what user intended
- [ ] **Scenario 8**: Write commit message and commit change

---

## Measurement During Test

Tester should collect:

1. **Timing** (rough estimates OK):
   - Time to open app: ___ seconds
   - Time to navigate to project: ___ seconds
   - Time from message sent to first answer received: ___ seconds
   - Time to make edit: ___ seconds
   - Time to review diff: ___ seconds
   - Time to commit: ___ seconds
   - **Total scenario time**: ___ minutes

2. **Observations**:
   - Did any crashes occur? ___ (yes/no)
   - Did app ever show an error message? ___ (yes/no, describe)
   - Did app freeze or become unresponsive? ___ (yes/no, duration)
   - Did offline mode work as expected? ___ (yes/no)

3. **Comfort & UX**:
   - Was the workflow intuitive? ___ (yes/no)
   - Would you do this on a bus one-handed? ___ (yes/no)
   - Did you need help/documentation? ___ (yes/no)

4. **Performance**:
   - Did the phone get hot? ___ (not/slightly/very)
   - Did battery drain noticeably? ___ (yes/no)
   - Was response time acceptable? ___ (yes/no)

---

## Post-Test Interview Questions

1. **Overall Impression**
   - On a scale of 1-5, how comfortable was this workflow? ___
   - What was the hardest part? (open-ended)
   - What surprised you? (open-ended)

2. **Thesis Validation**
   - Do you agree with the thesis: "This lets me work on Git projects from my phone, offline"? (yes/no)
   - Would you use this regularly? (yes/no/maybe)

3. **Specific Feedback**
   - Which step felt most natural? ___ (open)
   - Which step felt most awkward? ___ (open)
   - What one thing would you change? ___ (open)

4. **Design Feedback**
   - Was the diff reviewer clear? (yes/no)
   - Was the commit flow understandable? (yes/no)
   - Any UX suggestions? (open-ended)

---

## Tester Compensation (Optional)

- [ ] GitHub shoutout in MVP announcement
- [ ] Aidos swag or merchandise (if available)
- [ ] "Test Pilot" badge in early releases
- [ ] Coffee/meal allowance if in-person test

---

## Documentation During Test

Coordinator should capture:
- [ ] Start time, end time
- [ ] Tester initials (or anonymous)
- [ ] Device model
- [ ] Any crashes/errors (screenshot if possible)
- [ ] Tester's comfort rating
- [ ] Key quotes from interview
- [ ] Permission to use feedback in public report (yes/no/anonymous)

---

## Post-Test Cleanup

- [ ] Confirm tester's feedback form submitted
- [ ] Thank you email sent
- [ ] Tester compensated (if applicable)
- [ ] All data backed up (screenshots, notes, recordings)
- [ ] Confidential data deleted from tester's device (optional; their choice)

---

## Troubleshooting During Test

If tester encounters issues:

| Issue | Solution |
|-------|----------|
| App won't open | Uninstall and reinstall APK. If persistent, document error and move to pre-test call. |
| Can't find project/repo | Help tester import repo (Git URL or local file). Shouldn't take > 5 min. |
| Model inference too slow | Document exact wait time. Expected: < 10s cold-start, < 5s thereafter. If excessive, note for M21 review. |
| Crashes mid-scenario | Restart app, retry step. Document crash details. May continue or abandon depending on severity. |
| Diff viewer confusing | Spend time explaining once. Then move on. Feedback for UX designers. |
| Airplane mode won't stay on | Notify coordinator; may retry or record as environment blocker. |

---

## Success Criteria (For Test Coordinator)

A test is **valid** if:
- ✅ Tester completed all 8 scenarios (or documented specific blocker)
- ✅ Tester completed pre-test call and post-test interview
- ✅ Device was in airplane mode for the entire scenario
- ✅ No external help during the core workflow (minor Q&As OK)
- ✅ Timing and comfort data recorded

A test is **unusable** if:
- ❌ Tester left early without reason
- ❌ Device's internet was on during core scenario
- ❌ Tester was guided through each step by coordinator
- ❌ Multiple crashes blocked workflow
- ❌ No post-test interview conducted
