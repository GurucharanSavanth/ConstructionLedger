# Android integration verification ? 2026-11-11

## Current verification status

Follow-up closed the named lifecycle gaps and obtained a **clean269/269 ordinary gate**, zero failures/errors/skips, plus4/4 real-process phases. See [lifecycle matrix and receipts](android-lifecycle-2026-11-11.md). Actual updater Activity recreation and stale work-log UI retention now pass. Historical failed receipts below remain preserved and are superseded by the later clean run; they are not rewritten as passes.

## Original audit status (preserved)

Final ordinary run: **266 tests, 265 passed, one failure, zero errors/skips**. All six original finding regressions passed in this run. The broad gate remains failed: DocumentNavigationTest kept-photo recreation hit ComposeNotIdleException. Focused DocumentNavigationTest rerun passed 2/2 without code changes (`20261111-191058-ordinary-c730c760`). Per-test logcat shows launcher/SystemUI `dequeueBuffer failed, error = -110` and 3,814 skipped app frames. Classified as likely emulator graphics failure; exact cause remains unproven. Previous broad run remains preserved: 260/266 passed, six failures. Do not replace either broad result with focused passes.

Host JVM/debug/lint/androidTest compilation passed (`.tools/android-audit-20261111/host-integrated.log`): 41 JVM cases, zero failures/errors, one existing Windows symlink skip. Final per-class counts: [test results](android-integration-test-results-2026-11-11.md).

Findings 1?5: fixed and verified within the automated cases described below. Finding 6: fixed and saved-state restoration verified; actual Activity recreation not exercised by its new test. No pre-fix device run was obtained for the six original defects; their original failure paths were confirmed in source and regression assertions.

## Scope and baseline

Android only. Baseline status and patch: `.tools/android-audit-20261111/baseline-status.txt`, `baseline.diff`. Prior reports copied before checks. Existing MEMORY, updater, settings/components/design and release-counter edits belong to the owner and are preserved. No web commands, production release tasks, signing changes, commits or installs of production updates.

Active module: root `:app` maps to `android-native/app`. Production ID `com.constructioncost.nativeapp`; isolated ID `.verification`. SDK 26 minimum, compile/target 37; JVM 17; AGP 9.4.1, Kotlin 2.4.21, Room 2.8.5. Room schema 6, native snapshot 5. No schema/dependency changes.

## System map

Compose entry/picker submits LedgerViewModel commands. Repository validates references, money, revisions and ownership within Room transactions; audit records share transactions. Room observers feed project summaries, activity and detail read models. Posted records live in private ledger.db, originals in files/attachments. DataStore holds drafts/settings only. ConstructionApplication owns repository; Activity recreation must reuse it. LedgerOperations owns retained backup/restore/file work and lifecycle mutex; attachment operations also use AttachmentStore.operationMutex. Reminder jobs use application recovery checks and lifecycle mutex. Updater work uses composition coroutine lifetime; saved UI state excludes Jobs and Activity references.

## Acceptance matrix

The receipt table below records executed scope. Pending rows are coverage targets, not proof of execution.

| User operation | Layers/files | Writes | Reads/display | Expected/failure behavior | Test/evidence | Status |
|---|---|---|---|---|---|---|
| Create classification and post | EntryClassification, LedgerViewModel, repository | classification, draft, allocations | selected ID and posted classification | select only saved ID in same draft; retain failed input | EntryFlowTest, classification regressions | Fixed; picker/draft/posting verified |
| Create/edit work log | LedgerViewModel, saveNote | activities, audits, links | detail/activity | missing edit or stale revision rejected atomically; retain draft | WorkLogRevisionTest | Fixed; repository and stale UI/draft retention verified in lifecycle follow-up |
| Unclassified WORK/PLACE chart | WebReplicaScreens, activity read | none | scoped rows/totals | only unclassified recorded cost, 5000 paise | Unclassified regression | Fixed and verified |
| Export original workbook links | ProjectWorkbookExport, AttachmentStore | ZIP/XLSX/manifests | external links/original bytes | resolve inside package; preserve exact bytes/hash | ProjectWorkbookExportTest | Fixed and verified; desktop click untested |
| Deliver >50 reminders | ReminderScheduler, repository, DataStore | notifications/history | individuals and overflow digest | bounded count; all eligible represented; reconcile cancellations | ReminderDeliveryTest | Fixed; focused 2/2 verified |
| Retry failed update after recreation | AppUpdates | saved UI state | error and Retry | deterministic second check; preserve safeguards | UpdateRetryTest | Fixed; saved-state and actual Activity recreation verified in lifecycle follow-up |
| Open/recreate/recover database | ConstructionApplication, recovery store | recovery markers | shared observers/loading/errors | one owner, preserve failed-open files | ActivityRecreationTest, ApplicationContextViewModelTest, recovery tests | Named automated coverage exercised in final broad receipt; see class counts and limits |
| Upgrade database/integrity | LedgerDatabase/entities/DAO | schema upgrades | IDs, amounts, references | non-destructive migration and integrity checks | LedgerMigrationTest | All 15 upgrade paths/integrity verified |
| Projects create/switch/edit/archive/delete | project UI/repository/file journal | projects, budgets, deletion journal | selected project rows | isolated ownership, restart-safe file deletion | VendorNavigationTest, ProjectDeletionTest | Named automated coverage exercised in final broad receipt; see class counts and limits |
| Expense/invoice/edit/delete | EntryScreen, repository | expense, lines, allocations, audit | exact paise/detail/summary | atomic writes, stale/duplicate rejection | EntryFlowTest, RecordBoundariesTest | Named automated coverage exercised in final broad receipt; see class counts and limits |
| Payment/allocation/advance/refund | payment UI, repository | payments, allocations, audit | cost/cash/advance separately | no duplicate cost; exact bounds | PaymentEditTest, LedgerRepositoryTest, RestoreRefundRegressionTest | Named automated coverage exercised in final broad receipt; see class counts and limits |
| Commitments/schedules | entry UI, repository | commitments/instalments/planned classes | future schedule/commitment | no recorded cost; reconcile reminders | PlanningReferenceTest, CommitmentCancellationTest | Named automated coverage exercised in final broad receipt; see class counts and limits |
| Document import/link/revision/view/export/delete | platform actions, AttachmentStore, repository | immutable originals, links, metadata | Files/viewer/share | cancellation and failure retain evidence | DocumentNavigationTest, LedgerExportsTest, RecordBoundariesTest | Named automated coverage exercised in final broad receipt; see class counts and limits |
| Draft resume/discard/project switching | LedgerViewModel/DataStore | auxiliary draft | form values | locks preserved, no cross-project mutation | LedgerDraftTest, ProjectDraftRecoveryTest | Named automated coverage exercised in final broad receipt; see class counts and limits |
| Backup/restore/post-restore refresh | LedgerOperations, BackupService, repository | candidate DB/originals then install | refreshed observers/totals | validate before replacement; committed outcome retained | BackupServiceTest, LedgerRestoreRecreationTest, storage/recovery tests | Named automated coverage exercised in final broad receipt; see class counts and limits |
| Concurrent/failing writes | repository transaction boundary | records/audits | success/error | rollback; no phantom successful save | ConcurrentLedgerV7Test, StorageFailureTest | Named automated coverage exercised in final broad receipt; see class counts and limits |

## Limits

No performance change is justified without paired measurements. Spreadsheet desktop clicking, physical-device vendor notification behavior, camera/installer and accessibility/font-scale exploration require separate evidence. A source review or compilation is not an end-to-end pass.

## Verification receipts (in progress)

Host `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin -PverificationApplication=true` passed in `host-fixed.log`. JVM: 41 cases, zero failures/errors, one existing Windows symlink skip. Initial host run failed on test compilation, corrected without weakening assertions. Final host rerun passed in host-integrated.log.

API-36 Pixel_10_Pro_XL isolated verification package:

| Class | Passed/total | Receipt under .tools/device-runs |
|---|---|---|
| WorkLogRevisionTest | 2/2 | 20261111-174724-ordinary-de1d42a0 |
| ProjectWorkbookExportTest | 5/5 | 20261111-174741-ordinary-71a2f06c |
| ClassificationSelectionTest | 2/2 | 20261111-174843-ordinary-6cfe5c10 |
| EntryFlowTest | 2/2 | 20261111-174926-ordinary-c50c433b |
| NativeReplicaTest | 12/12 | 20261111-175007-ordinary-b45617e5 |
| LedgerMigrationTest | 2/2 | 20261111-175153-ordinary-41e7c672 |
| UpdateRetryTest | 1/1 | 20261111-175514-ordinary-52acfd8a |

All listed passing runs have zero skips/errors/failures. Updater test restores Compose saved state, not an actual Activity instance. Initial updater fixture failed three times because controls lacked a Column and overlapped; tapping launched Share. Corrected fixture passed. Reminder initial fixture failure shared the application recovery directory despite isolated DB path; corrected contexts isolate filesDir. Next overflow regression timed out; diagnosis recorded below. These failed runs remain preserved and do not count as passing acceptance.

## Changed files and reasons

- EntryClassification/LedgerViewModel: apply successful picker selection after unlocking, guard draft/project identity, pass explicit work-log edit revision.
- LedgerRepository: reject missing edited work logs inside the same transaction; preserve creation and revision-zero edits.
- WebReplicaScreens: route WORK/PLACE unclassified charts with existing dimension-specific filters.
- ProjectWorkbookExport: workbook-directory-relative external targets; internal package relationship paths unchanged.
- ReminderScheduler: bounded individuals and digest, overflow identity and reconciliation.
- AppUpdates: save retry flag with message; injectable check function for deterministic tests, production default unchanged.
- WorkLogRevisionTest, ClassificationSelectionTest, EntryFlowTest, NativeReplicaTest, ProjectWorkbookExportTest, ReminderDeliveryTest, UpdateRetryTest: behavioral regressions.
- LedgerMigrationTest: all 15 supported upgrade paths and integrity assertions.
- MEMORY and this report: verification scope and receipts. Pre-existing updater/settings/version edits are separate baseline work.

Independent repository review found overflow-to-individual promotion could disappear during posted-only reconciliation; fixed and regression added. No additional confirmed database product defect emerged from reviewed ownership/transaction/migration/backup code. This does not establish exhaustive database or end-to-end acceptance.

ReminderDeliveryTest final focused run passed 2/2, zero failures/errors/skips: `.tools/device-runs/20261111-180102-ordinary-d0090f02/summary.json`. Platform adds automatic group summary; app posts at most 21, total channel bounded at 22 in regression. Reconciliation preserves system group summary. Previous exact-count failures exposed an asynchronous test race; owned-notification assertions now wait for the actual digest. The promotion gap and automatic-summary cancellation were additional confirmed issues within the reminder repair.

Host-integrated.log passed after reminder changes. Final ordinary result is recorded above.

## Broad ordinary gate

`.tools/device-runs/20261111-180145-ordinary-a062e26f/summary.json`: 266 tests, 260 passed, 6 failures, 0 errors/skips. This is a failed broad gate. Failures: overflow reminder, reminder permission UI, four VendorNavigationTest cases. Subsequent fixes and results are recorded below; focused passes do not replace this result. All other cases, including repository, backup, recovery, migration, documents, drafts and financial workflows, are enumerated in the receipt.

## Broad-failure repairs and focused reruns

- Reminder overflow promotion produced a stale digest during rapid notification updates. Scheduler now spaces submissions by at least 300 ms under the existing mutex; no test sleeps or retry loops were added. Platform throttling is the leading explanation, not a confirmed service rejection log. Pacing preserves cancellation and submission history. Final focused ReminderDeliveryTest passed 2/2 at `20261111-182048-ordinary-60e2a957`.
- ReleaseScreenTest used the removed Android settings section from pre-existing owner UI changes. It now scrolls directly to reminder toggle; permission-result assertions remain. Final focused ReleaseScreenTest passed (receipt referenced by repaired log).
- VendorNavigationTest used old No/Android settings labels and selected an underlying Home scroll container. It now uses Cancel and scopes Settings scrolling to dialog descendants. Final class passed 7/7 at `20261111-182858-ordinary-9eac98e8`.
- Integrated host checks passed in `host-integrated.log`: JVM tests, debug build, lint and Android-test compilation. No production release task ran.
- Final ordinary rerun completed: 265/266 passed. Document class then passed 2/2 without code changes; see Final status.

## Coverage limits

The matrix records automated cases actually exercised, not every permutation of every workflow. Physical vendor devices, desktop spreadsheet clicking, real camera/installer return paths and a complete restart/font-scale matrix for every screen were not exercised. Process/lifecycle behavior is covered by existing named tests and repository reopen checks; the new updater test uses Compose saved-state restoration, and reminder overflow uses repository reopen rather than killing its process. No performance optimization, index, schema migration or cache was justified or added. Existing SQL integrity and application reference validation passed tested fixtures; this is not proof against every possible corruption.

`document-ui.png` was inspected: Files controls and saved-document row were readable, but screenshot was taken during a modal transition; it does not establish final dialog layout. The capture stalled while document instrumentation ran; causation of the timeout is unproven.

## Final preservation and delivery checks

`git diff --check` passed. `.tools/android-audit-20261111/baseline-preservation.json` confirms owner changes to release-version.txt, UPDATES.md, Components.kt, Screens.kt and WebDesign.kt match the initial patch exactly, and docs/ remains deleted. No web files or commands were used. MEMORY/AppUpdates contain authorized additions on top of owner edits. No commits, release builds, signing changes, app-data clears/uninstalls, production updates or architecture/dependency changes occurred.

All source hashes, device identity and individual test XML are preserved by the device receipt runner. Earlier failures remain available. The last broad gate is **not clean**; the isolated rerun supports environment classification but does not rewrite that receipt. No additional product fix was justified for the document timeout. No unrelated performance optimization was attempted.
