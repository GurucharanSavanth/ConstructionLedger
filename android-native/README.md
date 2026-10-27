# BuildLedger native Android

Offline construction-finance ledger for Android 8.0 (API 26) and newer. The
app uses Kotlin, Jetpack Compose, Room, and SQLite. Ledger records remain in
the app's private database; original documents remain in private file storage.
Posted monetary values use integer paise. The app does not require an account
or network access.

## Build

Use Android Studio with JDK 17 and Android SDK 37. The canonical Gradle project
is at the repository root; its `:app` module points to `android-native/app`.
Run from the repository root on Windows:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

Install the debug APK on a test device with:

```powershell
adb install -r android-native/app/build/outputs/apk/debug/app-debug.apk
```

The debug APK uses a local debug key. If Android reports a signing mismatch,
keep the existing app data and verify a backup before changing installations.

## Source layout

- `app/src/main/java/.../domain/` contains money and ledger rules.
- `app/src/main/java/.../data/local/` contains Room entities, queries, and
  migrations; `data/repository/` owns transactional writes and read models.
- `app/src/main/java/.../ui/` contains Compose screens, navigation, and
  ViewModel state.
- `app/src/main/java/.../data/backup/` and `platform/files/` handle archives,
  imports, exports, original documents, and recovery.
- `app/src/test/` contains JVM tests; `app/src/androidTest/` contains Room,
  device, and Compose instrumentation tests.

Room schema changes require a migration and a matching migration fixture.
Financial changes should preserve integer-paise calculations, project and
supplier boundaries, audit history, and transaction rollback behavior.

## Device tests

Run instrumentation tests only on a disposable emulator or device. The receipt
runner saves the test evidence and supports ordinary, recovery, and performance
modes:

```powershell
python android-native/scripts/test-device.py --serial emulator-5554 --disposable
python android-native/scripts/test-device.py --serial emulator-5554 --disposable --mode verification
```

Some recovery and volume fixtures are intentionally excluded from the ordinary
suite and require the verification package or explicit fixture preparation.
Follow the runner's guards and use one Gradle workflow at a time. Never clear a
populated installation to make a test pass.

## Backups and release builds

Create and verify a backup before replacing a device or installation. Keep the
archive and its recovery password outside the device. An interrupted restore
can leave a recovery state that must be reviewed before further writes.

Public APK releases must use the owner's permanent signing key. Configure
`BUILDLEDGER_KEYSTORE_PATH`, `BUILDLEDGER_KEY_ALIAS`,
`BUILDLEDGER_STORE_PASSWORD`, and `BUILDLEDGER_KEY_PASSWORD` outside Git, then
run:

```powershell
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

Without production signing credentials, Gradle produces an unsigned release
APK, which cannot be installed as a public update. The
`-PverificationApplication=true` package is for QA only and must not be
distributed. Keep signing keys, passwords, local databases, backups, and built
APK/AAB files out of the repository.
