# BuildLedger project instructions

## Initialization and project memory

Read [MEMORY.md](MEMORY.md) when starting, resuming or handing off a project task. It maps current owners, completed changes, evidence and open work. Before editing a subsystem, read its scoped guidance: [Android](android-native/AGENTS.md) or [web](BuildLedger_Web/AGENTS.md).

When asked to run or refresh `/init`, update this file and the relevant memory/agent maps from the actual source and configuration. Preserve user decisions, distinguish implementation from verification, and keep the maps concise. `MEMORY.md` is linked context, not a separate automatically discovered instruction file.

The user deliberately deleted `docs/`. Keep it deleted; maintain project context in these agent files, the READMEs, MEMORY.md and the existing output audit. The web server supports SQLite only. Preserve that database choice and its dependency-free server implementation.

## Workspace and ownership

- Working native app: `android-native/app/`, Kotlin/Compose, Room over SQLite. Root `settings.gradle.kts` maps `:app` here.
- `app/` is an unused scaffold. Do not implement native features there.
- `BuildLedger_Web/` is a separate Git repository and is ignored by the parent repository. Check Git status/diffs in both repositories for cross-project changes; stage and commit in the repository that owns each file.
- Root and `android-native/` both have Gradle entry points. Prefer root commands for the project users open. Native dependencies live in `android-native/gradle/libs.versions.toml`.
- `.tools/` holds ignored, local evidence. `output/` holds reports and copied APKs/assets; a copied APK is not proof of the current source build.
- The checkout contains substantial existing work. Preserve unrelated edits and inspect current status before changing files. Git history exists in both repositories; do not initialize or replace either repository.

## Data and financial rules

- Native SQLite, server SQLite and browser IndexedDB are independent stores. No automatic synchronization is implemented.
- Keep posted money in integer paise. Reuse `domain/Money.kt` and repository validation on Android; reuse `finance.js` and server validation on the web. Native calculations use BigDecimal; web postings must remain safe integers.
- Recorded cost, cash paid, supplier advances, commitments and future schedules have different meanings. A payment or plan must not create another recorded cost.
- Keep allocations within their source amount, project and supplier rules. Preserve revisions, idempotency, audit history and transaction boundaries.
- Preserve original document bytes, IDs and import evidence. Do not silently recalculate or classify historical invoices.
- Native repository ownership belongs to ConstructionApplication. Preserve shared observers across Activity recreation and use the existing recovery state before replacing or cleaning storage.

## Commands and verification

Use the pinned Node version in `BuildLedger_Web/.node-version`. Run web commands from `BuildLedger_Web/`; use `npm.cmd` on PowerShell if execution policy blocks `npm`.

| Task | Command |
| --- | --- |
| Web configuration | `npm.cmd run setup` |
| Web server/API, localhost:8080 | `npm.cmd run dev` |
| Browser-only preview | `npm.cmd run local` |
| Web unit and SQLite/API tests | `npm.cmd test` |
| Web syntax | `node --check app.js` and changed modules |
| Android debug APK, root cwd | `.\gradlew.bat :app:assembleDebug` |
| Android JVM tests, root cwd | `.\gradlew.bat :app:testDebugUnitTest` |
| Android static checks, root cwd | `.\gradlew.bat :app:lintDebug` |
| Android device tests, disposable target only | `.\gradlew.bat :app:connectedDebugAndroidTest -PverificationApplication=true` |

On this Windows machine, the installed Android Studio JBR is `C:/Program Files/Android/Android Studio/jbr`. Set process-local JAVA_HOME when the shell cannot find Java. ADB is under the local Android SDK's `platform-tools/` directory.

Run checks appropriate to the change. Financial, persistence and recovery changes need meaningful regression assertions with literal expected amounts/bytes. UI changes need the affected device/browser journey. Documentation-only edits need path, instruction and whitespace checks; do not rerun large app suites without a reason.

Use `node:test` with strict assertions and descriptive `*.test.mjs` names. Native tests use JUnit and AndroidX/Compose `*Test.kt`. No coverage percentage is configured. Use disposable SQLite and the browser procedure in the web README; the Android guide covers guarded fixture and stress runs.

## Coding and delivery

Match surrounding formatting; avoid unrelated reformatting. Kotlin uses four spaces, PascalCase classes and camelCase members. JavaScript uses ES modules and camelCase. Reuse existing helpers and components before adding layers or dependencies.

Use concise imperative commit messages, such as `Fix payment allocation rounding`. PRs describe changed behavior, relevant issues, actual checks and screenshots for UI changes. Do not equate compiled screens, individual passing reruns or old receipts with a clean full acceptance gate.

## Safety and memory maintenance

Never commit `.env`, databases, uploaded originals, credentials, signing keys, heap dumps or private records. Preserve `data/` and existing SQLITE_PATH during upgrades. Verify backups before restore/storage changes; do not clear app data or uninstall a populated installation to fix a test.

Network web deployments require owner authentication and HTTPS. Password-free mode is localhost-only. Keep native ledger operations offline. Native Internet and user-approved package installation permissions are authorized for GitHub APK updates; see android-native/UPDATES.md.

After changing architecture, commands, user decisions or verification status, update the relevant map and the dated state in MEMORY.md. Record the exact check, scope and evidence location; mark untested claims explicitly. Preserve full device-run receipts before focused reruns overwrite Gradle's report directory. Store no credentials or financial record contents in memory.
