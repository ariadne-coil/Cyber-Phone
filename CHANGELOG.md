# Changelog

## v0.4.0 (2026-02-15)
- Wallet: introduced a full in-app wallet tab with federation selection, balance view, USD rate display, payment history scaffolding, and send/receive actions.
- Wallet: added dual-backend support for LDK (Bitcoin on-chain + Lightning) and Fedimint (Lightning + e-cash token flows), including runtime federation switching.
- Wallet: added contact-integrated wallet actions (pay, request, destination field) and message-thread wallet flows.
- Wallet: added encrypted wallet backups/restores (v2), federation-aware restore handling, and passphrase-gated import/export.
- Wallet: enforced sender-side policy controls, including secure-channel requirements for high-value flows and a hard 100 BTC single-transaction cap.
- Wallet: hardened federation directory handling (URL trust restrictions, certificate pinning, payload limits, rollback guards, and metadata integrity tracking).
- Wallet: improved Fedimint integration robustness with startup/retry hardening, compatibility handling, and sender-side spend-cancel scheduling.

## v0.3.0 (2026-02-08)
- Mesh Calls: fully functional voice calls over the mesh (in-app VoIP + Opus) with a native incoming call UI.
- Mesh Calls: major reliability improvements (link-request retries, INVITE/ACCEPT retries, implicit connect on first audio, and robust remote hangup propagation).
- Audio: low-latency playback (no queued stale audio), bounded jitter buffering, urgent-audio thread priorities, and Bluetooth headset routing with dynamic connect/disconnect handling.
- Mesh Files: file and media transfers over the mesh using Reticulum-compatible segmented resources, integrated with the standard attachment picker and thread rendering.
- Messages: the thread call button now chooses Mesh vs Telecom based on user mesh mode + stored mesh address, with optional fallback to PSTN.

## v0.2.2 (2026-02-05)
- Mesh: stabilized UDP transport (single socket + multicast join) and made peer unicast port-stable to prevent “works once” delivery failures.
- Mesh: added LAN unicast probing to bootstrap discovery on networks where broadcast/multicast is blocked.
- Mesh UX: added in-app QR scanner + save flow (select existing contact or create a new contact) and added a first-class “Mesh link” field in the contact editor.
- Permissions: marked BLE scan as `neverForLocation` to avoid unnecessary location coupling.
- Tests: fixed RNS loopback tests and added a UDP peer test to prevent regressions.

## v0.2.1 (2026-02-03)
- Moved all mesh controls into Cyber Features and renamed the Profile section.
- Added Wi‑Fi Direct discovery toggle and status readout (group/role/credentials).
- Added BLE mesh transport (advertise/scan + GATT) and toggle.
- Added Wi‑Fi Aware (NAN) transport with feature gating, toggle, and status.
- Added mesh diagnostics counters (packets/announces/last packet).

## v0.2.0 (2026-02-03)
- Added Reticulum-compatible link resource segmentation (multi‑segment transfers) and request/response resource support.
- Enabled mesh attachments (photos, videos, files) via LXMF fields with local persistence and thread rendering.
- Improved mesh fallback logic and attachment size handling when mesh is available.
- Added a changelog link to the README for easier release visibility.

## v0.1.11 (2026-02-02)
- Added opt-in YACB community reputation with auto-update toggle and last-refresh status.
- Added caller reputation line in the in-call UI (emoji + colored counts) with DB readiness messaging.
- Added thread ID subtitle in message threads for E2E debugging.
- Added Gradle build speedups (parallel/cache/vfs) and disabled minify for debug builds.

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
