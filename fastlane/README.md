# Fastlane Notes

This directory is kept for metadata compatibility and release documentation.

## Scope

- Store listing metadata is under `fastlane/metadata/android/`.
- `Appfile` and `Fastfile` are retained for tooling compatibility.
- Cyber Phone release publishing is handled by GitHub workflows, not by Fastlane Play upload lanes.

## Local validation

If needed, metadata checks can be run from CI workflows in `.github/workflows/validate-fastlane-metadata.yml`.
