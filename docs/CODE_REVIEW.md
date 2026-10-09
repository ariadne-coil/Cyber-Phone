# Code review — 2026-10-09

Reviewed the local checkout of `ariadne-coil/Cyber-Phone`, based on commit `903dd934768bb7519a8c4915d4bfc1d8d15e48b2`, including the existing, uncommitted dependency updates. The review focused on messaging encryption, scheduled sends, mesh packet/resource handling, wallet operations, and backup recovery. The original findings below describe the state before the subsequent repairs; their line references refer to that reviewed state.

## Repair status — 2026-10-09

All nine findings have been addressed in the local working copy. Existing dependency updates are preserved.

| Finding | Applied change | Regression coverage |
| --- | --- | --- |
| 1. Plaintext fallback | Encryption failures stop dispatch and retain the composer/notification draft. Wallet tokens recheck their required secure channel. | Missing-key and protocol-bypass cases in `E2eCipherTest`. |
| 2. Different conversation IDs | New `E2E2:` messages use a shared, versioned HKDF context and authenticated context. Legacy `E2E1:` decoding remains available. | Real ECDH peers with different local IDs, legacy decoding, tampering and truncation. |
| 3. Mesh allocations | Validate typed MessagePack fields and lengths before allocation, part counts, segment metadata and link binding. Limit logical resources to 16 MiB, reserved buffers to 32 MiB and decompressed segments to 1 MiB minus one byte. | Huge count/binary/array headers, malformed hash-map updates, aggregate reservations, decompression bombs, valid delivery and advancing hash-map requests. |
| 4. Wallet restore | Extract into a unique staging directory; check manifest, paths, expanded size, seed and channel state; load through the local LDK builder before replacing the original directory. Preserve the previous directory on failed replacement/rollback. | Rejected validation, incomplete state, wrong manifests, traversal, expanded ZIP limits and validation-before-replacement ordering. |
| 5. Fedimint RPC | Send `amount` and `{secs,nanos}` cancellation durations. Normalize the upstream operation/notes tuple. | Production JavaScript wrapper/engine execution with a mocked transport, plus pinned Rust schema checks. |
| 6. Federation races | Serialize manager operations and JavaScript requests; attach and verify the requested client in both engine and worker. A timeout after dispatch never automatically replays a mutation. | In-flight spend/client-switch interleaving, mismatched engine/worker clients and queued-dispatch timeout states. |
| 7. Key replacement/storage | Distinguish missing from unavailable ciphertext; preserve unreadable identities. Encrypt both key-pair values before committing them together; encryption failure cannot create new plaintext secrets. | Missing/unavailable reads, preservation of stored ciphertext, failed multi-value writes and identity initialization. |
| 8. Scheduled sends | Use `goAsync()` and finish in `finally`. Remove scheduled SMS after persistence/transport acceptance; retain scheduled MMS until all callbacks succeed. Persist attempt tracking and clean it when content is deleted. Overdue failed content remains available. | Pending, duplicate, failed and stale MMS callbacks; persistence across tracker recreation and cleanup. |
| 9. Duplicate ecash spends | Reserve an operation before confirmation, disable Send while pending, and retain state/result in a ViewModel. Save cancellation work before returning a token, including cancellation if its activity is abandoned. | Eight concurrent attempts acquire only one operation; guard can be released for an explicit retry. |

The final suite contains 53 JVM tests (19 app, 34 messaging), including 25 new regression tests, and seven JavaScript bridge tests. The bridge tests run automatically with the app's unit-test tasks.

The combined validation command is:

```sh
./gradlew :app:assembleDebug :messages:assembleDebug \
  :app:lintDebug :messages:lintDebug \
  :app:testDebugUnitTest :messages:testDebugUnitTest
```

Both debug builds, both lint tasks and all 60 tests pass. Lint uses the repository's existing baselines; existing warnings and the Fedimint runtime task's configuration-cache incompatibility remain. The runtime still builds from the pinned Rust source.

Validation also exposed a pre-existing mesh response race: a peer could reply before its request receipt was registered. Receipts now register before transport dispatch, and the existing loopback test replies synchronously to reproduce the previous failure deterministically. Hash-map requests now advance past the second segment instead of repeatedly requesting the same map.

Both SMS peers must run a version that understands `E2E2:` to exchange new encrypted messages. Legacy decoding cannot repair old ciphertext that was originally created using a different device's conversation ID. Restore tests exercise staging and rollback with a validation callback; actual LDK loading, Android lifecycle/background behavior, real keystore outages, carrier delivery and disposable-federation transactions still require device integration checks. No real funds or carrier messages were used in automated validation.

## Original findings

P1 denotes an urgent correctness, privacy, or data-loss issue; P2 denotes an issue for the next development cycle. The original build and unit tests did not cover these failure scenarios.

## 1. P1 — Encrypted conversations can silently send plaintext

**Location:** `messages/src/main/kotlin/org/fossify/messages/messaging/Messaging.kt:62–63`; `messages/src/main/kotlin/org/fossify/messages/helpers/E2eManager.kt:144–153`.

`encryptOutgoing()` returns `null` both when encryption is disabled and when an encrypted thread has no readable shared secret. `sendMessageCompat()` interprets either case as permission to send the original text. A keystore read failure, or a missing shared-secret entry with the encrypted-thread flag still set, therefore sends an ordinary message in plaintext. Both the thread composer and notification reply call this function without requiring successful encryption.

**Recommended change:** distinguish disabled encryption, protocol messages that intentionally bypass encryption, and encryption failure. Stop sending ordinary content when an encrypted conversation cannot encrypt it, retain the draft, and show a recoverable error. Recheck encryption at the point of sending wallet tokens as well.

**Validation:** traced the missing-secret return, the plaintext fallback, and the composer/notification callers. No SMS was sent.

## 2. P1 — SMS encryption derives different keys on different phones

**Location:** `messages/src/main/kotlin/org/fossify/messages/helpers/E2eManager.kt:579–581`; encryption at line 153 and decryption at line 216. SMS thread IDs originate from `Telephony.Threads.getOrCreateThreadId()` in `messages/src/main/kotlin/org/fossify/messages/extensions/Context.kt:1282–1305`.

The HKDF context is the device's local SMS conversation ID. These IDs are assigned independently on each phone. Two peers can derive the same ECDH secret but use thread IDs 7 and 42, producing different AES keys. The transmitted message contains only the nonce and ciphertext, so the receiver cannot recover the sender's context. Encrypted messages fail authentication whenever the IDs differ.

**Recommended change:** use a versioned, shared protocol context, optionally bound to the two public keys in a deterministic order. Keep local conversation IDs only for storage lookup. Introduce an explicit wire-format version and consider compatibility with existing ciphertext.

**Validation:** invoked the compiled production `deriveAesKey()` helper with a real ECDH exchange. Matching IDs decrypted successfully; different IDs raised `AEADBadTagException`.

## 3. P1 — A small mesh advertisement can exhaust the app's heap

**Location:** `messages/src/main/kotlin/org/fossify/mesh/rns/RnsLinkResourceManager.kt:88–89`, `139–162`, and `458–498`.

The advertisement's untrusted `n` field is copied directly into `totalParts`, then used to allocate two arrays. The incoming-resource count limit is applied after this allocation and does not bound its byte size. A peer able to establish a mesh link can advertise millions of parts in a tiny packet. The resulting `OutOfMemoryError` is not handled by the surrounding `Exception` catches.

**Recommended change:** validate part count, transfer size, segment count, hash lengths, and consistency with the link's payload capacity before allocation. Enforce both per-resource and aggregate byte budgets. Also bound decompression output: the current BZip2 `readBytes()` at lines 684–690 can expand a small resource without a limit.

**Validation:** a 58-byte advertisement with `n=8,000,000` raised `OutOfMemoryError` in the production resource manager. The probe ran in an isolated JVM with a 32 MiB heap; it used no network or Android process.

## 4. P1 — Backup restore deletes the previous wallet before validating the replacement

**Location:** `app/src/main/kotlin/org/fossify/phone/fragments/WalletFragment.kt:2928–2929`; restore orchestration at `2680–2694`.

The extraction helper accepts an archive containing any one file under the wallet prefix, swaps it into place, and immediately deletes the previous wallet directory. It does not validate the backup manifest or required wallet state. Wallet startup happens afterward, and `ok` is already `true`, so a startup failure still produces a restore-success notification followed by an error. An incomplete or incompatible backup can therefore replace the working wallet while discarding the previous local recovery state.

**Recommended change:** validate the manifest and required files in staging; retain the previous directory until the restored state has passed local validation. Roll back a failed restore, and report success only after that validation. Treat transient network sync separately from local state validation. Limit extracted bytes and clean the staging directory on every failure.

**Validation:** traced extraction, directory replacement/deletion, startup, and the success flag. No real wallet data was modified.

## 5. P1 — Fedimint ecash uses an incompatible RPC contract

**Location:** `app/src/main/assets/fedimint/index.js:229–235`; response handling in `app/src/main/assets/fedimint/engine.html:496–516`.

The wrapper invokes `spend_notes` with `min_amount`, but upstream `SpendNotesRequest` requires `amount`. It also sends a numeric cancellation timeout where the Rust request uses `Option<std::time::Duration>`. Upstream returns `(OperationId, OOBNotes)`, serialized as a two-element array; the engine recognizes only a string or named object fields. Even after correcting the request, that response is passed through as an array, and Android's `spendEcashBlocking()` rejects it because it extracts notes only from an object or string.

The field-name mismatch and tuple response contract also exist in the previous Fedimint submodule commit `e8570bc6b4f9892e560d153b2bf4fd5c4f3c0358`; these are pre-existing integration problems, not newly introduced by the dependency update.

**Recommended change:** align the bridge with the pinned upstream RPC schema: send `amount`, encode the duration in the format expected by Serde, and normalize the returned tuple to `{ operation_id, notes }`. Add contract checks and a disposable test-federation smoke test before relying on ecash transfers.

**Validation:** executed the actual JavaScript wrapper and engine handler with a mocked SDK transport, captured the request, and supplied the tuple shape specified by upstream source. Checked the required request fields in both upstream revisions. No federation transaction was executed.

## 6. P1 — Concurrent wallet operations are not bound to their requested federation

**Location:** `app/src/main/kotlin/org/fossify/phone/wallet/FedimintWalletManager.kt:515–531`; client selection in `app/src/main/assets/fedimint/engine.html:128–174`; dispatch in `app/src/main/assets/fedimint/worker.js:403–425`.

The manager opens the requested federation, then sends a separate RPC with no client/federation identifier. The engine and worker share one mutable current client. The startup lock does not cover the subsequent operation. A background cancellation worker for federation B can switch the client between a foreground operation's successful activation of A and its RPC. That operation then runs against B or fails while the client changes. The `isOpen` verification also reads only the open flag, despite the engine returning a client name.

**Recommended change:** serialize federation activation and the entire associated operation, or maintain a separate engine/worker per client. Include and verify the expected client identity on every RPC. Do not let unrelated cancellation or refresh work reset an in-flight wallet session.

**Validation:** traced the foreground and WorkManager paths. The engine probe demonstrated that an unbound request executes against B after a switch from A to B. This reproduces the dispatch behavior; Android thread scheduling was not tested.

## 7. P1 — Keystore failures can overwrite the existing messaging identity

**Location:** `messages/src/main/kotlin/org/fossify/messages/helpers/SensitivePrefs.kt:41–45`; `messages/src/main/kotlin/org/fossify/messages/helpers/E2eManager.kt:41–49`.

After its short retries, `SensitivePrefs.getString()` returns the same empty default for an unreadable encrypted value as for a missing value. `ensureKeyPair()` treats that as a first launch and writes a new key pair over the original preferences. It is called when opening both the messaging list and a conversation. A temporary keystore failure can therefore permanently discard the original private-key ciphertext and change the identity without a deliberate reset. Additionally, encryption failures in `SensitivePrefs.putString()` store the replacement private key or shared secrets as plaintext at lines 60–62.

**Recommended change:** represent missing, unavailable, and corrupt key material separately. Preserve stored ciphertext on failures, avoid generating replacement keys automatically, and offer explicit recovery. Keep secret writes encrypted or fail with an actionable error instead of silently storing plaintext.

**Validation:** traced preference reads, retries, initialization callers, identity replacement, and the write fallback. Keystore failure was not injected on a real device.

## 8. P2 — Scheduled messages are deleted before sending runs

**Location:** `messages/src/main/kotlin/org/fossify/messages/receivers/ScheduledMessageReceiver.kt:50–57`, with receiver lifetime at `23–31`.

The receiver posts `sendMessageCompat()` to the main queue, then immediately deletes the scheduled message on the background thread. The comment assumes the message has already been persisted, but the queued send may not have started. A send/persistence failure or process termination in that interval loses the scheduled content. The receiver also starts asynchronous work without `goAsync()`; its three-second wake lock does not preserve the broadcast's process lifetime until completion.

**Recommended change:** retain the scheduled record until the outgoing message is successfully persisted and handed to the send pipeline. Return an explicit send result, preserve failed content for retry, and use `goAsync()` with `finish()` in `finally`, or durable scheduled work for longer operations.

**Validation:** traced queue dispatch, deletion, and `sendMessageCompat()`'s internal error handling. Process termination was not simulated on Android.

## 9. P2 — Repeated Send taps can create multiple ecash spends

**Location:** `app/src/main/kotlin/org/fossify/phone/activities/WalletSendTokenActivity.kt:242–256`; click registration at line 65.

Starting a spend only shows a progress indicator. The Send button remains enabled, there is no in-flight guard, and the indicator is a separate nonmodal view. Two quick taps can therefore launch two spend operations. The activity returns only one result to its caller, so another generated token can be left unreturned and its funds reserved until cancellation. This becomes observable once the RPC contract in finding 5 is repaired.

**Recommended change:** acquire a single-operation guard before confirmation/dispatch, disable Send while an operation is pending, and retain the operation/result across recreation. Keep operation identifiers available even when the activity closes.

**Validation:** checked all button references, the layout, confirmation paths, and result handling. No ecash was spent.

## Original review verification

The prior dependency-update validation passed both debug builds, lint, and all 28 existing unit tests. The review's additional probes are stored outside the repository in `/workspace/.cyberphone-updates/code-review/`: `ReviewProbe.java`, `fedimint-probe.mjs`, and the `e2e.log`, `resource.log`, and `fedimint.log` outputs. They use the compiled production messaging classes and unmodified wallet JavaScript, with mocked browser/SDK plumbing for the JavaScript checks.

The subsequent repair moved backup extraction/validation out of the wallet fragment, introduced explicit messaging send outcomes, and added the regression coverage listed above. Wallet error reporting still uses shared error strings and boolean/null results in several older call paths; replacing those is a broader maintainability follow-up.
