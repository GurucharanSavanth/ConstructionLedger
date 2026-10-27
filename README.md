# BuildLedger for Android

BuildLedger is an offline construction-cost and payment ledger for Android. The
native app is written in Kotlin with Jetpack Compose and Room over SQLite. It
stores ledger records and original documents in private app storage; financial
amounts are recorded in integer paise. No account or network connection is
required.

The project requires Android Studio, JDK 17, and Android SDK 37. The app
supports Android 8.0 (API 26) and newer.

From the repository root on Windows:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
```

The debug APK is written to
`android-native/app/build/outputs/apk/debug/app-debug.apk`. Install it on a
test device with `adb install -r <apk-path>`. The root Gradle project maps
`:app` to `android-native/app`.

See [the Android guide](android-native/README.md) for app structure, device
testing, backup handling, and release signing. A production release requires
the owner's permanent signing key; local debug and verification builds are
not public release artifacts.

See [LICENSE](LICENSE) for the repository's license terms.
