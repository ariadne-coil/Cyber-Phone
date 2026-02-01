# Changelog

## v0.1.10 (2026-02-01)
- Refined About screen layout with dynamic version, Ariadne artwork, and updated feature list.
- Updated launcher monochrome icon handling and resized Ariadne asset for smaller APK footprint.
- Resolved build warnings and modernized deprecated Android/Kotlin APIs across app and messages modules.

## v0.1.2 (2026-01-31)
- Added Tapback-compatible message reactions (send + receive) with per-message emoji rendering.
- Added compact reaction picker (single-row emoji bar) for received messages only.
- Stored reactions in a dedicated Room table with migration and filtered Tapback system messages.

## v0.1.1 (2026-01-29)
- Added short-number filtering modes and a targeted short-code-only reclassification path.
- Added spam/not-spam actions with safe-number overrides, dynamic menu visibility, and STOP=spam+block handling.
- Added first-time YACB community rating submissions for spam/not-spam actions, including call-block flow.

## v0.1.0 (2026-01-28)
- Migrated Messages list to DB-driven category filtering (Main/OTP/Spam) and SQL sorting to avoid in-memory filtering.
- Added AI spam classifier framework with model manager (Edge/HF sources), settings toggles, and update flow.
- Implemented persistent message category cache (Room table + DAO) with cleanup and message-arrival updates.
- Improved Messages performance: debounced refreshes, throttled full syncs, and reduced redundant provider reads.
- Added mesh status indicators (connected node count + routing activity) and RNS node tracking fixes.
- Integrated libphonenumber for caller ID enrichment, plus OTP notification improvements (caller ID, larger code, copy action).
- Enhanced E2E encryption flow: key-set timestamps, selective decryption after key exchange, and persistent decrypted storage.
- Stabilized thread loading: limited initial message load, cooldown for full thread refresh, and layout manager reset.
- Updated Proguard rules to preserve Gson generic signatures and message attachment models (release crash fix).
