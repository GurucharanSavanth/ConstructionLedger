# Direct APK updates

BuildLedger checks the latest stable public GitHub release at application launch. Settings also offers **Check for updates**. Failed background checks do not block the local ledger. No ledger records or attachments are uploaded.

Source: https://github.com/GurucharanSavanth/ConstructionLedger/releases

Publish a stable tag `vMAJOR.MINOR.PATCH` with exactly one universal signed `.apk` asset. Set its Android `versionName` to the tag without `v`, and increase `versionCode`. GitHub must expose the asset's `sha256` digest. Drafts, prereleases, ambiguous assets and unsupported tags are excluded.

Downloads require HTTPS, bounded sizes, matching SHA-256, matching application ID, an increased version code, matching version name, and the same signing certificates as the installed application. Retain the original production signing key. Debug or verification installations cannot update to a differently signed production APK. Signing-key rotation is not supported by this updater.

The user approves the download, allows installation from BuildLedger when Android requests it, and approves Android's installer. Return to the prompt and select **Install update** after granting permission. Updates replace the application in place; never uninstall or clear its data as an update step. The ledger remains usable offline. Internet permission is used for release metadata and APK downloads; network-state permission is unnecessary.

This direct APK distribution flow is not a Google Play updater. The previous policy forbidding native Internet permission was superseded by the owner's update request on 2026-11-10.
