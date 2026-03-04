# Changelog

## v0.6.1 (2026-03-04)
- Privacy/compliance: added a direct in-app link to the published Cyber Phone privacy policy from the About screen.
- Release/docs: published the Cyber Phone website and privacy-policy pages on GitHub Pages for store and release metadata use.
- Dependency maintenance: refreshed libphonenumber metadata components and the Fastlane/Robolectric toolchain used around release and test workflows.

## v0.6.0 (2026-02-28)
- Mesh reliability: rolled back the regressive small-message dual-path send behavior and replaced it with an active-link-only fast path plus safer direct-packet fallback, substantially reducing the remaining one-way delay/loss pattern.
- Mesh link health: added responsive-link checks for message fast-path routing so stale one-sided links stop hijacking text delivery after invoice/call activity.
- Mesh calls: stopped pinning call sessions to stale link IDs; control/audio now prefer the freshest destination-based active link mapping, improving post-invoice call stability.
- Fedimint runtime: replaced vendored WASM blobs with a source-built runtime pipeline (pinned submodule + build scripts), keeping the wallet reproducible and F-Droid-friendlier.
- Fedimint compatibility: fixed WebView runtime integration for the new generated client (`env` import issue and async `RpcHandler` construction), restoring working federation access.
- Lightning UX: added federation recurring LNURL generation support, capability detection, and wallet UI handling for reusable Lightning payment codes.
- Wallet identity/sharing: switched user-facing Lightning address display toward LNURL where available, reduced invoice clutter in the wallet UI, and expanded thread sharing flows for wallet/contact payloads.
- Release/docs: updated release metadata and F-Droid notes to reflect the new source-built Fedimint runtime path.

## v0.5.2 (2026-02-25)
- Stability: fixed the Settings crash caused by missing required bound layout IDs in settings includes.
- Install reliability: disabled release baseline-profile sidecar generation to avoid `INSTALL_BASELINE_PROFILE_FAILED` on affected Studio/device combinations.
- Release hardening: replaced post-build APK mutation with a safe `verifyReleaseUrlScrub` check to prevent dex/resource corruption.
- Metadata/privacy cleanup: removed upstream ecosystem/store links from packaged commons/license metadata while preserving Cyber Phone-owned links.
- About screen: restored explicit GitHub + Substack links and added safer external-link handling.
- Release automation: switched to deterministic asset naming (`CyberPhone-v<version>.apk`) and cleanup of legacy generic APK asset names.
- Fastlane metadata: aligned package ID to `org.cyberphone` and cleaned full-description footer links.

## v0.5.1 (2026-02-25)
- Appearance: removed the non-functional **App icon color** option from customization.
- Appearance: extended **App font** selection with additional detected system fonts while keeping built-in and custom file options.
- Customization hardening: patched Commons customization UI at runtime to keep Cyber Phone behavior consistent without forking the Commons dependency.

## v0.5 (2026-02-24)
- Wallet reliability: expanded Bitcoin Mainnet recovery with stronger Esplora endpoint failover/health scoring, fee-rate preflight hardening, and improved node restart/sync behavior to reduce "balance unavailable" and startup outages.
- Wallet/Fedimint: upgraded and hardened Fedimint compatibility/runtime probing, improved federation switching/join flows, and stabilized invoice/payment execution paths across mixed federation capabilities.
- Wallet UX: improved send/pay/invoice dialogs and overall wallet visual consistency with Fossify theme/accent/font settings, including refined amount-entry and action flows for exchange/mint/withdraw operations.
- Wallet conversions: rebuilt BTC mainnet on-chain/Lightning conversion UX with explicit amount handling and safer bidirectional conversion behavior.
- Wallet + messaging: enhanced payment request/approval flow in threads with stronger request/invoice validation (amount, destination, uniqueness) and cleaner inline message rendering.
- Wallet + threads: improved "send in messages" selection to better support mesh conversations and existing-thread dispatch from wallet actions.
- Contacts/identity: expanded wallet contact data support (including Lightning fields), improved QR/vCard payload handling, and reduced duplicate custom-field import issues.
- Mesh reliability: reduced asymmetric delivery delay with event-driven burst propagation windows and tuned sync behavior, improving message/call responsiveness without continuous high overhead.
- Mesh calls: fixed multiple mesh call stability regressions (answer-time crash, one-way lifecycle issues, and robustness around connect/disconnect handling).
- Mesh thread visibility: fixed missing mesh conversation discovery by adding DB backfill/self-heal for mesh conversation rows and category/visibility safeguards in conversation listing/pickers.
- Branding and app identity: normalized branding to **Cyber Phone** across repo/app assets and advanced package identity migration to `org.cyberphone` with compatibility fixes.
- Commons decoupling: removed or overrode ecosystem-specific commons behavior that is not appropriate for this fork, including fake-version popup paths.
- App stability: fixed settings startup crash caused by missing bound settings sections after package/branding refactors.
- Security hardening: strengthened wallet/secret backup and restore integrity paths, plus additional defensive validation from security/code-review sweeps.
- Release automation: added GitHub Actions workflow support for building and attaching signed APK assets to GitHub releases via repository secrets.
- Known issue: Fedimint withdrawals can still be unreliable on some federation/network states and may require retries.

## v0.4.4 (2026-02-22)
- Mesh reliability: added event-driven burst propagation sync (startup/announce/send/manual-trigger windows) with strict caps and exponential backoff to reduce delivery latency without sustained battery drain.
- Mesh transport/runtime: improved service/interface startup behavior and transport coordination across UDP multicast, BLE, Wi-Fi Direct, and Wi-Fi Aware paths.
- Wallet core: migrated and hardened Lightning runtime integration around `ldk-node` 0.7.x with compatibility wrappers and restart/recovery improvements.
- Wallet reliability: expanded Bitcoin Mainnet node/feerate/esplora failover handling and recovery paths to reduce balance/sync/payment outages across federation switches and retries.
- Wallet flows: rebuilt top-up and liquidity orchestration plumbing (`WalletFederationTopupManager`) and strengthened automatic route/bootstrap handling for pay/send operations.
- Wallet UI/UX: significant wallet surface polish (cards, send/pay dialogs, payment list rendering, federation selector behavior, and theme-accent consistency).
- Wallet + messaging integration: improved wallet protocol token handling in threads/direct reply and compacted mesh wallet payloads for more reliable mesh transport.
- Security hardening: introduced sensitive preference handling paths for private wallet/message data and tightened related persistence/restore handling.
- Settings/UI structure: refactored settings layout composition (modular sections) and aligned call/wallet/message surfaces with consistent app theming and typography behavior.
- Localization/resources: added wallet-core localized string sets across Fossify locales and performed a broad resource cleanup/reorganization (including launcher/art asset placement).
- Dev/testing/docs: added wallet compatibility tests/helpers and checked-in wallet federation/migration reference docs/data used during stabilization.
- Known issue: Fedimint withdrawals can still be unreliable on some federation/runtime combinations and may require retries.

## v0.4.3 (2026-02-21)
- Branding: normalized product naming to **Cyber Phone** across repo metadata/templates and app-facing assets.
- Wallet/Mesh: fixed Bitcoin over mesh delivery by switching wallet invoice messages to a compact payload format suitable for mesh transport.
- Reliability: backported upstream Phone/Messages fixes for long caller-name rendering (call screen + recents marquee) and unknown-number blocking behavior to avoid false global blocking on lookup/permission issues.
- Dependencies: updated key shared libs for compatibility and fixes (`org.fossify:commons` 6.1.5, `geocoder` 3.24, `libphonenumber` 9.0.24, `ez-vcard` 0.12.2).
- Contacts UX: aligned contact-profile icon tap selector behavior with upstream drawable handling.
- Customization: unlocked font-selection paths in this fork by disabling Google-relation gating and forcing local thank-you entitlement state for gated commons checks.

## v0.4.2 (2026-02-18)
- Wallet reliability: hardened Esplora endpoint handling with health scoring, remembered-good endpoint preference, retry-aware preflight checks, and stronger feerate recovery during startup/sync/balance refresh.
- Wallet reliability: fixed federation-switch behavior that could leave Bitcoin Mainnet in recurring "fee rates unavailable" states by adding safer restart/retry paths and federation-scoped balance snapshot handling.
- Fedimint payments: expanded `payInvoice` compatibility fallback (wrapper + raw RPC probing) across module/method/payload variations used by different runtime/API shapes.
- Fedimint payments: improved retry/error classification to continue probing on RPC discovery/shape mismatches while surfacing real payment execution failures earlier.
- Withdraw flow: improved receive-invoice creation resilience with deterministic stop/start handling and pre-invoice sync/recovery logic.
- Known issue: Fedimint withdrawals can still be unreliable on some federation/runtime combinations.

## v0.4.1 (2026-02-17)
- Wallet: major stabilization pass after v0.4.0 where wallet flows were partially broken; fixed multiple startup/sync failures that could leave balances unavailable or the wallet non-functional.
- Wallet: fixed Bitcoin Mainnet regressions around fee-rate updates and hardened LDK recovery with Esplora fee-estimate preflight + endpoint failover during startup/sync.
- Wallet: upgraded and hardened Fedimint runtime integration (startup/open/join lifecycle, federation compatibility handling, and improved error reporting paths).
- Wallet: expanded federation directory handling (remote feed integration, deduping/normalization, and improved federation switching behavior).
- Wallet: added liquidity provider management (auto/manual selection plus custom provider configuration) in Cyber Features.
- Wallet: added automatic federation top-up plumbing from Bitcoin Mainnet with quote/fee prompts and liquidity bootstrap orchestration.
- Wallet: added explicit balance transfer actions in the wallet UI (on-chain/Lightning exchange, federation Mint, federation Withdraw).
- Wallet: rebuilt wallet send/pay flows to support request/approve/deny handshakes for Fedimint payments in conversations.
- Wallet: improved message-side wallet token parsing/validation and request state tracking to prevent mismatched or duplicate invoice responses.
- Wallet: improved contact + identity integration for wallet/mesh data (including better QR/field handling and contact wallet metadata updates).
- Wallet UI: substantial redesign and theme integration across wallet screens, dialogs, and payment review surfaces.
- Known issue: Fedimint withdrawals are still unreliable and may fail or remain pending.

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
