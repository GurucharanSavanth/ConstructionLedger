# Android lifecycle acceptance ? 2026-11-11

## Final result

**Clean ordinary gate: 269 tests passed, zero failures/errors/skips**, API 36 Pixel_10_Pro_XL, isolated verification application. Receipt: `.tools/device-runs/20261111-230646-ordinary-120ca75f/summary.json`.

Separate real-process campaigns passed **4/4 phases**. Host JVM/debug/lint/androidTest compilation passed. JVM: 40 passed, one existing Windows symlink skip, zero failures/errors.

The previous rendering timeout is no longer an outstanding broad-gate failure: focused document2/2, accessibility8/8 and the complete 269-case run passed on SwiftShader. This proves the current acceptance run; it does not prove every host graphics-driver failure has been eliminated.

## Acceptance scope

This follow-up closes the named lifecycle gaps with a finite test matrix: retained UI state and rejection, actual Activity recreation, persisted records after a genuinely different process, reminder state after process death, and the ordinary full suite. It does not claim every theoretically possible interleaving or external device configuration.

## Changes

- UpdateRetryTest: actual ActivityScenario recreation, distinct Activity instances, Retry retained, exactly one manual check before recreation, no automatic check caused by recreation, exactly one retry afterward. Existing Compose saved-state test retained. A first asynchronous fixture assertion observed an extra check; explicit idle/checkpoint assertions now synchronize each lifecycle action without adding sleeps or changing product code.
- DocumentNavigationTest: wait for retained operation completion before querying Compose; retain original timeout bounds. Assert exact original bytes/hash/size, attachment ID and count through navigation and real Activity recreation. Cleanup can identify a committed test attachment after interrupted assertion.
- WorkLogLifecycleTest: real form review/save after delete-source or restore-source removal, visible repository rejection in review dialog, retained input and complete draft, unchanged record/audit snapshot, zero recreated notes/links. Restore case reloads ViewModel solely from isolated DataStore.
- WorkflowRestartTest: guarded two-phase verification-package/emulator test. Persistent private sandbox records invoice/line/classification allocations, supplier/project isolation, commitment/planned classifications/schedule, payment/allocation/advance/refund/reversal, revised work log, linked original and draft settings. Second phase requires a different PID and verifies the complete exact snapshot, original bytes/hash, and literal totals.
- ReminderOverflowProcessTest: guarded additive fixture, 51 schedules and 21 app-owned notifications. Different-PID verification requires unchanged notification post times/history, 31-item overflow coverage and exact schedule paise. Deletes only its own fixture project and restores previous preferences.

No production source change was justified in this follow-up. Assertions were synchronized and scoped to the correct UI; timeout limits, recovery guards and accounting safeguards remain unchanged.

## Lifecycle matrix

| Boundary | Operation/records | Check | Evidence/status |
|---|---|---|---|
| Saved-state restoration | Updater failure | Retry visible and exactly one additional check | UpdateRetryTest 2/2, `.tools/device-runs/20261111-192313-ordinary-ca162a18` |
| Actual Activity recreation | Updater failure | Different Activity instance; no automatic extra check; Retry works | Same class/receipt |
| Retained draft after source deletion | Work-log UI edit | Error visible; inputs retained; no record/link/audit mutation | WorkLogLifecycleTest 2/2, `20261111-193209-ordinary-fc84baea` |
| Restore removes source, then ViewModel/DataStore reload | Work-log UI edit | Persisted draft retained; repository rejection unchanged snapshot | Same class/receipt |
| Operation return, navigate away/back, actual Activity recreation | Camera-photo import and document viewer | Exact ID/bytes/hash/count and retained edit fields | DocumentNavigationTest 2/2, `20261111-193008-ordinary-a51ff81a` |
| Genuine process death/restart | Connected posted record families and draft | Different PIDs; complete snapshot unchanged; recorded10000, paid7000, outstanding5000, advance2000, upcoming5000, commitment20000 paise; original 8 bytes | WorkflowRestartTest 2/2 phases, `.tools/android-lifecycle-20261111/process-summary.json` |
| Genuine process death/restart | 51 reminders | Same 21 post times and delivery history; digest31; no duplicate overflow individual posts | ReminderOverflowProcessTest 2/2 phases, same summary |
| Existing Activity/recovery/backup/draft/accessibility/concurrency cases | All ordinary classes | Full gate required, not inferred from focused runs | Passed in final269-case ordinary receipt |

The process campaigns cover persisted repository and auxiliary state; they do not pretend to enter every operation through UI. Ordinary UI tests exercise submission/read-back; existing Activity ownership and restore tests cover their specific lifecycle paths. The matrix names those distinct proofs rather than treating them as identical.

## Receipts

- Baseline status/diff: `.tools/android-lifecycle-20261111/baseline-status.txt`, `baseline.diff`.
- Host root JVM/debug/lint/androidTest compilation passed: `.tools/android-lifecycle-20261111/host.log`. JVM remains 41 cases, zero failures/errors, one existing Windows symlink skip.
- Process-phase logs and device/source identity: `.tools/android-lifecycle-20261111/process-identity.json`, `process-summary.json`, `WorkflowRestartTest-*.log`, `ReminderOverflowProcessTest-*.log`. Workflow PID/snapshot/draft/hash evidence copied into `workflow-restart-evidence/` before disposable teardown.
- Earlier broad timeout receipt remains `.tools/device-runs/20261111-183129-ordinary-61d8a3c1`: 265/266 passed. Cross-process HWUI buffer timeouts support emulator graphics stalls, but do not prove a single root cause. No assertion or timeout was relaxed to hide it.
- New broad gate: **269/269 passed**, `.tools/device-runs/20261111-230646-ordinary-120ca75f` (software-rendered AVD).

## Safety

Only the disposable verification application was installed/restarted. Workflow data uses its own sandbox; reminder fixture guards against unrelated eligible reminders and active notifications. Existing project records, owner edits, web repository, release counter, signing and docs deletion were preserved. No commits or production releases.

## Environment diagnosis and repair

The first follow-up broad run ended after 187 cases (185 passed,2 failures,0 errors/skips) when the emulator exited and package service became unavailable: `.tools/device-runs/20261111-193451-ordinary-3d41363a/`. It did not execute the rest of the suite. No application assertion was relaxed. The previous app/UI timeout logs showed cross-process HWUI failures; exact original host-driver cause remains unproven.

Restarted the existing Pixel_10_Pro_XL AVD with process-local flags `-no-snapshot-load -no-snapshot-save -gpu swiftshader -no-window`; no wipe, AVD replacement or data reset. Emulator stdout/stderr: `.tools/android-lifecycle-20261111/emulator-software*.log`. Accessibility class then passed 8/8 at `20261111-230257-ordinary-29842951`; full ordinary run passed 269/269 afterward. No concurrent screenshot/UIAutomator capture ran during this gate.

## Reproduce separate process checks

Build isolated debug app and tests with `:app:assembleDebug :app:assembleDebugAndroidTest -PverificationApplication=true`. Verify both output metadata application IDs end in `.verification` / `.verification.test`; install only those APKs with `adb -s SERIAL install -r`. Ordinary Gradle connected-test teardown removes disposable packages, so direct phase execution requires these explicit installs.

Run `adb -s SERIAL shell am instrument -w -e class com.constructioncost.nativeapp.WorkflowRestartTest#prepare com.constructioncost.nativeapp.verification.test/androidx.test.runner.AndroidJUnitRunner`, then `adb -s SERIAL shell am force-stop com.constructioncost.nativeapp.verification`, then same instrumentation with `#verifyAfterRestart`. It refuses to overwrite an unverified campaign. Copy its synthetic snapshot/PID/draft/hash evidence before disposable teardown.

For reminders, selectors are `com.constructioncost.nativeapp.ReminderOverflowProcessTest#prepareOverflowForProcessRestart` and `#verifyOverflowAfterProcessRestartAndRemoveFixture`, both with `-e disposableReminder true`. Use `am kill com.constructioncost.nativeapp.verification` between these phases: force-stop removes notifications and would invalidate the continuity test. Both tests require a different PID; successful exit alone is insufficient. Check each output contains `OK (1 test)` and preserve logs. Exact tested host orchestration and receipts remain under `.tools/android-lifecycle-20261111/`.

Final preservation check: all tracked production source diffs match the start-of-follow-up baseline, and final source hashes match the clean receipt. A concurrent release-version.txt change from6 to7 was observed at final comparison; none of this task's commands ran a production release task. The concurrent counter edit was preserved. Evidence: `.tools/android-lifecycle-20261111/final-preservation.json`. `git diff --check` passed.
