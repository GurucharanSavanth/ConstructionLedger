# Direct APK updates

BuildLedger checks the latest stable public GitHub release at application launch. Settings also offers **Check for updates**. Failed background checks do not block the local ledger. No ledger records or attachments are uploaded.

Source: https://github.com/GurucharanSavanth/ConstructionLedger/releases

Publish a stable tag matching the generated release version (`f.001`, `f.002`, etc.) with exactly one universal signed `.apk` asset. An optional `v` prefix is accepted. GitHub must expose the asset's `sha256` digest. Drafts, prereleases, ambiguous assets and unsupported tags are excluded. Legacy `vMAJOR.MINOR.PATCH` releases remain comparable; an `f.NNN` release upgrades a legacy installation, while legacy tags never replace an `f.NNN` installation.

Release builds reserve the next number in `release-version.txt` when Gradle executes the release version task. `f.001` uses Android version code 2, following the previous code 1; each later number increases both. Padding is at least three digits (`f.999`, then `f.1000`). APK and bundle outputs in the same Gradle invocation share one version. Android Studio's signed release build uses this same pipeline. Signing an already-built APK separately keeps its embedded version.

Sync, help, dry runs, debug builds and verification-package builds do not advance the counter. A release pipeline that reaches version reservation consumes its number even if a later step fails. Keep the counter outside generated build folders, retain it across clean builds, and commit its advanced value before releasing from another checkout or CI. Independent clones need the same latest counter; this is not a central version service. Do not reset it or reuse published version codes.

Downloads require HTTPS, bounded sizes, matching SHA-256, matching application ID, an increased version code, matching version name, and the same signing certificates as the installed application. Retain the original production signing key. Debug or verification installations cannot update to a differently signed production APK. Signing-key rotation is not supported by this updater.

The user approves the download, allows installation from BuildLedger when Android requests it, and approves Android's installer. Return to the prompt and select **Install update** after granting permission. Updates replace the application in place; never uninstall or clear its data as an update step. The ledger remains usable offline. Internet permission is used for release metadata and APK downloads; network-state permission is unnecessary.

This direct APK distribution flow is not a Google Play updater. The previous policy forbidding native Internet permission was superseded by the owner's update request on 2026-11-10.
