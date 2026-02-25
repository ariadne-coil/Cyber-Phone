# F-Droid Submission Notes (Cyber Phone)

This document is for F-Droid reviewers and maintainers.

## App Identity

- App name: `Cyber Phone`
- Application ID: `org.cyberphone`
- License: `AGPL-3.0-only`
- Source repository: `https://github.com/ariadne-coil/Cyber-Phone`

## Build/Release Basics

- Android build system: Gradle wrapper (checked in).
- Version metadata is static in `gradle.properties`:
  - `VERSION_NAME`
  - `VERSION_CODE`
  - `APP_ID`
- Release notes are curated in `CHANGELOG.md`.
- Fastlane metadata is tracked under `fastlane/metadata/android`.

## Signing

- GitHub releases may include a maintainer-signed APK.
- F-Droid builds and signs with F-Droid signing keys.
- No F-Droid build step requires private signing secrets.

## Network Behavior

Cyber Phone does not include analytics SDKs or Google Play Services.

Network access is feature-gated and user-driven:

- Community spam reputation sync (YACB): optional.
- AI model downloads/updates: optional.
- Mesh networking interfaces: optional.
- Wallet federation/rate refresh: optional, only when wallet features are used.

## Reproducibility Notes

- Dependency versions are pinned in `gradle/libs.versions.toml`.
- One repository source is JitPack (`https://jitpack.io`) for specific pinned artifacts.
- Fedimint web runtime assets are currently vendored in `app/src/main/assets/fedimint/` (JS + WASM).

These two points are intentionally documented because they may be review-sensitive.

## Metadata Checklist

Before each release/RFP update:

1. `CHANGELOG.md` has a section matching `VERSION_NAME`.
2. `fastlane/metadata/android/en-US/changelogs/<VERSION_CODE>.txt` exists.
3. `fastlane` title/short/full descriptions reflect current features.
4. RFP links point to current source and current tag/release page.

## RFP Template Notes

- Multiple categories are valid.
- Donation checkbox is optional.

