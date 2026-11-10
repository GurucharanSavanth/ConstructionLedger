# Direct APK updates

BuildLedger checks the latest stable public GitHub release at application launch. Settings also offers **Check for updates**. The first check downloads the release APK to inspect its package, signing certificates and numeric Android version code. Verified APK bytes are cached by SHA-256 and reused on subsequent checks and installation; Android cache cleanup can require another download. Failed background checks do not block the local ledger. No ledger records or attachments are uploaded.

Source: https://github.com/GurucharanSavanth/ConstructionLedger/releases

Publish a stable release with exactly one universal signed `.apk` asset. Tags such as `V23`, `f.006`, or other names are accepted; tags do not determine update ordering. GitHub must expose the asset's `sha256` digest. The verified APK's numeric Android `versionCode` determines whether an update is newer, and its embedded `versionName` appears in the installation prompt. Same-version and older APKs are not offered. Drafts, prereleases and ambiguous assets are excluded.

Release builds reserve the next number in `release-version.txt` when Gradle executes the release version task. `f.001` uses Android version code 2, following the previous code 1; each later number increases both. Padding is at least three digits (`f.999`, then `f.1000`). APK and bundle outputs in the same Gradle invocation share one version. Android Studio's signed release build uses this same pipeline. Signing an already-built APK separately keeps its embedded version.

Sync, help, dry runs, debug builds and verification-package builds do not advance the counter. A release pipeline that reaches version reservation consumes its number even if a later step fails. Keep the counter outside generated build folders, retain it across clean builds, and commit its advanced value before releasing from another checkout or CI. Independent clones need the same latest counter; this is not a central version service. Do not reset it or reuse published version codes.

Downloads require HTTPS, bounded sizes, matching SHA-256, matching application ID, an increased version code, and the same signing certificates as the installed application. Version and signing checks run again before installer handoff. Retain the original production signing key. Debug or verification installations cannot update to a differently signed production APK. Signing-key rotation is not supported by this updater.

The user selects **Install update**, allows installation from BuildLedger when Android requests it, and approves Android's installer. Return to the prompt and select **Install update** after granting permission. Updates replace the application in place; never uninstall or clear its data as an update step. The ledger remains usable offline. Internet permission is used for release metadata and APK downloads; network-state permission is unnecessary.

Already-installed V23 binaries retain the old tag-matching bug until replaced. Install the first corrected, same-key signed build manually, or publish it once under its matching `f.NNN` tag so the older checker can detect it. Subsequent releases may use arbitrary tags.

This direct APK distribution flow is not a Google Play updater. The previous policy forbidding native Internet permission was superseded by the owner's update request on 2026-11-10.
