# LDK 0.7.0 Focused Reliability Test (One Pass)

## Scope
Target only high-risk wallet behaviors that can lose funds, duplicate payments, or misreport state.

## Preconditions
1. Install same build on two Android devices.
2. Ensure both devices:
   - have wallet initialized
   - can switch federations
   - have mesh messaging operational
3. Prepare small test balance only.
4. Enable log capture for `LdkWalletManager`, wallet UI, and mesh payment message flow.

## Pass/Fail Rule
- **Pass**: all tests below pass in a single run, no crashes, no stuck infinite loaders, no silent fund loss.
- **Fail**: any mismatch between displayed state and actual balances/payment history.

## Test Matrix

### A) Baseline startup/state
1. Open wallet on app launch.
2. Verify no `Wallet node unavailable` loop.
3. Switch federation -> Mainnet -> federation.
4. Verify balances and recent activity refresh correctly.

Expected:
- No stale “starting” state.
- No forced app restart needed for balance visibility.

### B) Invoice create/pay settlement
1. Device B: create fixed-amount lightning invoice.
2. Device A: pay invoice from send screen.
3. Observe status on both ends until terminal.

Expected:
- Sender: pending -> success (or clear failure).
- Receiver: invoice credited exactly once.
- No duplicate debit/credit entries.

### C) Pending-to-settled handling
1. Repeat with slightly larger amount to trigger realistic delay.
2. While pending, leave screen and reopen thread/wallet.
3. Confirm final state updates without manual recovery.

Expected:
- Pending state persists accurately across refresh.
- Final settlement reflected automatically.

### D) Retry/idempotency guard
1. Trigger a payment that delays.
2. Tap action again (or retry after timeout prompt).
3. Verify only one successful payment per intended transfer.

Expected:
- No accidental double-send.
- Clear message when request already in progress/pending.

### E) Mesh payment request flow
1. Send payment request over mesh with proposed amount.
2. Receiver approves.
3. Sender auto-pays returned invoice.
4. Validate credit on receiver wallet.

Expected:
- Request -> approval -> payment -> receipt completes end-to-end.
- No “missing thread/request” regressions.
- No stuck payment screen after completion.

### F) Failure path correctness
1. Test insufficient balance path.
2. Test invalid/expired invoice path.
3. Test temporary connectivity failure (short network toggle).

Expected:
- Actionable error text (no raw exception dump).
- No false success.
- Recovery path works after Sync/retry.

## Evidence to Record
For each scenario:
1. Start time, federation selected.
2. Amount requested/sent.
3. Final sender state.
4. Final receiver state.
5. Payment identifiers shown in activity logs.
6. Any error text (exact string).

## Exit Criteria
Ship-ready if:
1. All scenarios pass once end-to-end.
2. No duplicate payment creation observed.
3. No balance drift between sender debit and receiver credit.
4. No critical wallet crash/ANR.
