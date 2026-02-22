# LDK Node 0.7 Migration Plan

## Goal
Migrate from `ldk-node-android 0.6.1` to `0.7.x` with:
1. Backward-compatible behavior for existing wallet flows.
2. Maximum transaction stability (no duplicate sends, no silent state loss, no false success).

## Current Compatibility Prep
- Added LSPS payment-state adapter:
  - `app/src/main/kotlin/org/fossify/phone/wallet/LdkLspsStateCompat.kt`
- Replaced direct `PaymentState` checks in:
  - `app/src/main/kotlin/org/fossify/phone/wallet/LdkWalletManager.kt`
- Added unit coverage:
  - `app/src/test/kotlin/org/fossify/phone/wallet/LdkLspsStateCompatTest.kt`

This isolates the known 0.7 rename (`PaymentState` -> `Lsps1PaymentState`) and avoids hard-typed enum coupling.

## Probe Result
- A dry probe with `ldk-node-android 0.7.0` now compiles and passes lint on this codebase.
- Active branch now uses `0.7.0`; final sign-off depends on focused reliability validation.

## Migration Sequence (Reliability First)
1. **Compile-only switch branch**
   - Change `ldkNode` to `0.7.0`.
   - Fix remaining API deltas only (no behavior refactors yet).
   - Gate completion: clean `:app:assembleDebug` + `:app:lintDebug`.

2. **Payment lifecycle parity pass**
   - Audit all `PaymentStatus` transitions used by UI/logging.
   - Verify pending/success/failure/refund mapping against previous behavior.
   - Add fallback handling for unknown statuses (never mark success on unknown).

3. **Reliability regression tests**
   - Automated checks:
     - status mapping tests
     - idempotency checks for repeated send attempts
   - Manual checks on two real devices:
     - create invoice -> pay -> receiver credit
     - timeout/retry path
     - pending then settle path
     - outbound and inbound mesh payment flows

4. **Staged rollout guardrails**
   - Keep detailed error normalization.
   - Keep conservative retry behavior around network/feerate failures.
   - Verify no regression in startup/sync responsiveness.

## Stability Acceptance Criteria
- No new crashers in wallet startup/send/receive paths.
- No duplicate transaction creation under repeated user taps/retries.
- No “success” UI state unless payment is settled by node state.
- Pending states eventually resolve or provide actionable error message.
- Mesh payment request/approval flows continue to complete end-to-end.

## Rollback Plan
- Keep `ldkNode = 0.6.1` tag checkpoint before cutover.
- If parity or reliability criteria fail, revert dependency and retain compatibility shim/tests.
