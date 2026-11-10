# BuildLedger for Android

Offline construction-finance ledger for Android 8.0 (API 26) and later. The app uses Kotlin, Jetpack Compose, Room and SQLite. Ledger use does not require an account or network access. Optional [signed APK updates](UPDATES.md) use GitHub over HTTPS and Android's installer approval. Native data is independent of the web server and browser stores; there is no automatic synchronization.

## Build and run

The canonical Gradle project is the repository root; `:app` maps to `android-native/app`. Use Android Studio's JBR (JDK 17 or later), Android SDK 37 and the checked-in Gradle wrapper. On Windows, from the root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

The debug APK is `android-native/app/build/outputs/apk/debug/app-debug.apk`. A debug installation may not match the signing key of an existing installation. Preserve and verify a backup before changing an installation; never clear data or uninstall a populated app to resolve a signing error.

## Architecture and data

Source paths below are under `app/src/main/java/com/constructioncost/nativeapp/`.

| Area | Responsibility |
| --- | --- |
| `ConstructionApplication.kt`, `LedgerOperations.kt` | Shared repository, recovery and retained file/restore operations. |
| `MainActivity.kt`, `platform/UiActions.kt`, `ui/` | Platform contracts, Compose navigation, screens, drafts and commands. |
| `domain/Money.kt`, `domain/LedgerModels.kt` | Decimal calculations, integer-paise postings and ledger models. |
| `data/repository/LedgerRepository.kt` | Transactional writes, audit, validation, deletion journal and read models. |
| `data/local/` | Room schema, queries, migrations and recovery state. |
| `data/backup/`, `platform/files/` | Archive validation and restore, original documents and previews. |
| `LedgerExports.kt`, `data/export/` | CSV, PDF and project workbook/package exports. |
| `ReminderScheduler.kt` | Local reminder jobs; scheduling is not an exact alarm. |

Room stores records in private `ledger.db`; original documents are under private `filesDir/attachments`. DataStore holds auxiliary drafts and settings. Cache files are temporary and are not the ledger. Clearing app data or uninstalling removes private data; use a verified backup for recovery. Current Room schema is 6 and the native snapshot version is 5. See [agent guidance](AGENTS.md) for ownership, compatibility and recovery constraints.

Money posted to the ledger is integer paise. Recorded cost, cash paid, supplier advances, commitments and schedules are distinct; payments and plans must not create duplicate costs. Preserve IDs, audit history, allocation boundaries and original document bytes. Do not silently recalculate imported invoices. A schema change requires an explicit migration, exported schema and matching migration fixtures.

## Verification

JVM tests are in `app/src/test/`; instrumented Room, backup, recovery and Compose tests are in `app/src/androidTest/`. Exported schemas are in `app/schemas/` and migration fixtures are in `app/src/androidTest/assets/`. Add literal expected amounts or bytes for changes to financial or persistence behavior.

Device tests require a disposable emulator and the separate verification package. The receipt runner saves device identity, source hashes and results before a rerun:

```powershell
python android-native/scripts/test-device.py --serial emulator-5554 --disposable
python android-native/scripts/test-device.py --serial emulator-5554 --disposable --mode verification
```

For a focused test, select one class or method per invocation:

```powershell
python android-native/scripts/test-device.py --serial emulator-5554 --disposable --mode verification --class com.constructioncost.nativeapp.data.backup.BackupServiceTest
```

Use `python android-native/scripts/test-device.py --help` for fixture and performance modes. Some tests require explicit preparation; retain their guards. A focused pass does not replace a failed or incomplete full run. Current dated evidence and limitations are in [project memory](../MEMORY.md).

## Backup and release

Export and verify a backup before replacing a device or installation. Keep the archive and its password outside the device. Review any recovery state after an interrupted restore before making further changes.

Release builds enable shrinking. Configure `BUILDLEDGER_KEYSTORE_PATH`, `BUILDLEDGER_KEY_ALIAS`, `BUILDLEDGER_STORE_PASSWORD` and `BUILDLEDGER_KEY_PASSWORD` outside Git, then run:

```powershell
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

Release builds automatically advance `release-version.txt` and embed `f.001`, `f.002`, etc., with increasing Android version codes. Signed builds use the same numbering. Preserve and commit the advanced counter before building releases elsewhere; see [release numbering and update requirements](UPDATES.md).

Without production credentials the release APK is unsigned and cannot be installed as a public update. The `-PverificationApplication=true` package uses a separate application ID and test signing; it is for QA only. Retain the mapping file with each released APK. Never commit keys, passwords, private databases, backups or generated APKs.
