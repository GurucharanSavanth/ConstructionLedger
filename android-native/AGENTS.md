# Native Android agent map

Applies to `android-native/`. Read the parent [project instructions](../AGENTS.md) and [memory map](../MEMORY.md), including dated evidence and open work, before modifying the app. Root `:app` maps to `app/` inside this directory; the sibling root-level scaffold is unused.

## Ownership and flow

Production package base is `app/src/main/java/com/constructioncost/nativeapp/`.

- `ConstructionApplication.kt` owns the shared LedgerRepository. MainActivity, the ViewModel and reminder work must use the existing owner; do not create competing Room instances per Activity or close the shared repository in Activity destruction.
- `LedgerOperations.kt` belongs to ConstructionApplication and owns long file/backup/restore/export operations, retained confirmations/progress, the interrupted-operation journal and committed restore outcomes. It shares `ledgerLifecycleMutex` with startup/reminders. Never capture an Activity in retained work or automatically replay an interrupted write.
- `MainActivity.kt` owns startup/recovery, SAF/camera/export activity results and viewer host/back handling. `platform/UiActions.kt` is the existing UI/platform boundary.
- `ui/BuildLedgerApp.kt` owns navigation. `ui/WebReplicaScreens.kt` and `ui/Screens.kt` are native Compose screens, not a web runtime. `ui/LedgerViewModel.kt` owns drafts, preference persistence and serialized commands.
- `ui/LedgerReads.kt` owns shared loading/read recovery and date refresh. Preserve verified-empty versus loading states in screen and one-time dialog reads.
- `domain/Money.kt` owns decimal parsing, posted rounding, units and allocations. UI ratios may use floating point; posted paise must not.
- `data/repository/LedgerRepository.kt` owns transactional finance/audits, project deletion journals, SQL read models and snapshot validation. `data/local/LedgerDatabase.kt`, `LedgerEntities.kt`, `LedgerDao.kt` and `LedgerRecoveryStateStore.kt` own Room schema/reads/recovery.
- `data/backup/BackupService.kt`, `ArchiveIO.kt`, `NativeBackupEncryption.kt` and `LegacyArchiveReader.kt` own native/legacy archive boundaries. `platform/LegacyImportMapper.kt` preserves original evidence and identifiers.
- `platform/files/AttachmentStore.kt` and `DocumentPolicy.kt` own private file operations; preview rendering is in DocumentPreviewScreen and `platform/pdf/PdfScreen.kt`.
- `LedgerExports.kt` owns CSV/PDF export. `ReminderScheduler.kt` also declares InstalmentReminderService. Daily jobs can be delayed; do not promise exact alarms.
- `data/export/ProjectWorkbookExport.kt` owns project-scoped Open XML workbooks and verified ZIP packages. `platform/files/AttachmentStore.kt` owns verified project-original quarantine, deletion cleanup and restart retry.

## Persistence and compatibility

Current facts: Room schema 6, native snapshot version 5, private database `ledger.db`, min SDK 26, compile/target SDK 37. Verify declarations before changing these values. Kotlin/Java output targets JVM 17; use the installed Android Studio JBR to run Gradle. The Room 5→6 migration adds the durable `project_deletions` journal; keep its recovery phases and file ownership checks aligned with `AttachmentStore`.

Schema changes need explicit migration, exported schema and migration fixture updates together. Preserve posted totals, existing IDs, original document hashes/bytes, audits, idempotency and revision watermarks. No destructive migration fallback.

Keep costs, actual cash, supplier advances, agreements and schedules separate. Do not silently recalculate imported invoices. Reuse repository validation and exact Money helpers instead of reproducing financial rules in Compose.

Backup installation must be based on an isolated validated candidate. Public `replaceRecordsFile` validates input; the internal inspected replacement path is only for BackupService's already inspected private staging file. Do not turn this optimization into a validation bypass.

Preserve recovery markers, old originals and transaction rollback. Missing metadata alone does not authorize deleting a private original or recovery artifact. Keep attachment-operation locks and critical commit/cancellation behavior when moving code.

## Checks and fixtures

Prefer commands from the workspace root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

Run only the checks the change needs. For affected device journeys, use a disposable emulator/application and the instrumentation class selector, for example from the root:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -PverificationApplication=true '-Pandroid.testInstrumentationRunnerArguments.class=com.constructioncost.nativeapp.data.backup.BackupServiceTest'
```

- `app/src/test/`: exact-money, chart, document-policy, encryption and archive JVM assertions.
- `app/src/androidTest/`: Room/repository, backup/originals, migration, recovery, navigation and Compose workflows.
- Project deletion and XLSX coverage: `data/repository/ProjectDeletionTest.kt`, `data/export/ProjectWorkbookExportTest.kt`, and Room edge migrations in `data/local/LedgerMigrationTest.kt`.
- Project manager create/switch/cancel/active-and-final deletion UI coverage is in `ui/VendorNavigationTest.kt`.
- `app/schemas/` and `app/src/androidTest/assets/`: Room schema and migration/format fixtures.
- Legacy conversion work also reaches `scripts/convert-legacy*.mjs`, their `*.test.mjs` files and NativeConverterTest assets/cases.
- SafV7FixtureTest requires a special disposable verification package and explicit preparation argument. RecoveryV7ActivityTest also requires that package. Inspect their guards before choosing a run; do not disable preconditions to make the default suite green.
- LedgerPerformanceTest has long 10k/100k scenarios. Run it for relevant performance changes, record fixture/device conditions and avoid claiming a speedup from an unpaired sample.
- Preserve the full run XML/logs before another connected run overwrites them. A passing focused rerun does not prove the previous broad run passed.
- `scripts/test-device.py --serial SERIAL --disposable` preserves old/new receipts, source hashes and device identity. Its ordinary mode excludes classes marked `SeparateVerification`; verification, explicit SAF preparation and performance modes are separate. Use one class or method per focused invocation: installed AGP 9.4.1 truncates comma-valued instrumentation arguments. The receipt gate rejects any skipped case.
- If the ordinary application already has data on the emulator, use the separate `verificationApplication=true` package for isolated project tests. Do not clear or uninstall the existing package to create a disposable target.

After changes to money, posting, allocation, migration or restore, use literal expected paise and original bytes in meaningful regression cases. UI work also needs loading/error, recreation, draft retention and accessibility assertions relevant to that flow.

## Current cautions and handoff

The shared owner, committed-restore outcome, retained operations and shared read-recovery fixes exist. Current-source verification is recorded in the parent memory and audit; compile success alone does not establish full acceptance. The root CI workflow now includes focused API-36 device checks, but configuration does not prove a remote CI run.

Project-management verification (2026-10-24): `:app:testDebugUnitTest` passed 32 tests with 0 failures; one symlink case was skipped because the Windows host lacks symlink privilege. `:app:lintDebug` passed. `python android-native/scripts/test-device.py --serial emulator-5554 --mode verification --class com.constructioncost.nativeapp.ui.VendorNavigationTest#projectManagerCreatesSwitchesAndCancelsDeletionInAnEmptyLedger --disposable` passed 1/1 on Pixel_10_Pro_XL/API 36. Receipt: `../.tools/device-runs/20261024-133851-verification-26d278c3/summary.json`. The ordinary package already contains `ledger.db`; use the verification package for isolated project fixtures and volume runs.

Keep no-login/offline ledger operation and narrow FileProvider grants. Internet and user-approved package installation permissions are authorized for GitHub APK updates; see UPDATES.md. Never clear a populated app's storage or uninstall it without preserving a verified backup. Release signing uses the BUILDLEDGER_* environment variables in `app/build.gradle.kts`; keep keys and passwords outside Git.

Public distribution targets direct APK downloads. Release builds enable R8 and resource shrinking. With no production signing environment the public package stays unsigned. `verificationApplication=true` changes the application ID and signs its release variant with the local test key solely for runtime QA; do not distribute that package. CI also compiles/optimizes the unsigned release and runs release lint. Preserve mapping files with each final APK.

Production release variant tasks reserve `f.NNN` and numeric code `N + 1` through `ReserveReleaseVersion` in `app/build.gradle.kts`. `release-version.txt` holds the last reserved number outside generated outputs; preserve and commit its advanced value before releasing from another checkout. Failed builds can leave gaps. APK/AAB in one invocation share a reservation. Sync/dry-run/debug/verification builds do not reserve. `ui/AppUpdates.kt` compares both sequential and legacy semantic release names; keep its tests and UPDATES.md aligned with version format changes.

`platform/files/CameraCaptures.kt` keeps camera bytes under persistent `filesDir/camera-captures` until import commits or the user confirms discard. Files exposes explicit review/retry. Nonempty legacy captures must survive cache cleanup; no automatic financial/file replay on startup. A kept source may already have a committed document after interrupted cleanup, so recovery UI must describe that uncertainty.

Match existing Kotlin formatting and Compose components. No new framework or dependency for routine refactoring. Update the parent memory map when changing ownership, schema, commands or verification status.
