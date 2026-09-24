# Messaging runtime independent review

Source review of `MessagingEngine.kt`, the storage transaction/outbox API, messaging codecs, Signal rollback implementation, and Kotlin MLS boundary against `SPEC.md` and the development API contracts. Reviewed 2026-09-13. No runtime tests, Android device exchanges, or crash-injection tests were executed for this review. Line numbers refer to the reviewed source and may move during fixes.

## Findings requiring fixes

1. **P1 — A crash can permanently prevent an attachment from being received.** `MessagingEngine.kt:509-516` moves the encrypted attachment to its final path before committing its database file/message/ratchet/receipt transaction. A process death after the move but before commit leaves an unreferenced `<fileId>.bin`. Startup (`49-53`) only deletes `.part` files. An unchanged durable retransmission then fails the existing-target check at `509` on every attempt, so neither message delivery nor ACK can finish. The file's parent directory is also not synced before the SQL commit, allowing a committed reference to outlive a nondurable rename on power loss. **Minimum fix:** reconcile unreferenced runtime file objects against SQLCipher on unlock, preserving intentional allocation-cover objects, or safely reuse/replace an unreferenced target only after validating the authenticated retransmission; ensure file and directory durability precede database commit. Verify crashes immediately before/after rename and SQL commit.

2. **P1 — Unauthenticated packets cause whole-vault ratchet serialization for every candidate while holding the vault mutex.** `MessagingEngine.kt:298,311-320` trials up to 1,024 contacts. `SignalSessions.kt:25-27,34-41` exports the complete Signal store before even checking the ciphertext type, then reconstructs the complete store on each failed candidate. A minimally framed Signal packet containing two invalid bytes triggers all these copies without authenticating any sender; repeated packets consume CPU and heap and block legitimate work behind the same mutex. MLS trials likewise deserialize up to 128 secret group snapshots. A count cap alone does not bound aggregate authentication work over time. **Minimum fix:** reject cheap malformed ciphertext forms before any snapshot, scope Signal rollback to the candidate's affected records, and enforce a global unauthenticated work/rate budget before taking the vault mutex. A validated connection hint may narrow trials but must never substitute for authentication. Verify malformed and valid-shaped failed-MAC floods with populated stores.

3. **P1 — Unrelated pairing proofs can disable all group operations.** `MessagingEngine.kt:471-487` accepts any cryptographically valid bilateral evidence and forwards newly seen edges to every contact. Group creation/completion (`104,422-430`) uses the entire accumulated graph, while `CliquePolicy.kt:15` rejects more than 4,950 proofs and the envelope codec enforces the same per-envelope limit. A paired adversary can generate identities and bilateral signatures it controls and send 4,951 unrelated edges in two otherwise valid envelopes. Those edges persist and make even a three-member legitimate group fail; removals and evidence sync can also fail when encoding the oversized global proof list. **Minimum fix:** use only proofs whose endpoints belong to the proposed group, and bound/admit gossip independently of the 100-member group proof limit. Do not broadcast arbitrary unrelated graphs to every contact. Verify more than 4,950 total stored edges with a valid small clique.

4. **P2 — One peer can consume all fresh MLS KeyPackage slots permanently.** `MessagingEngine.kt:384-391` allows 128 stored requests globally and never expires them; the only deletion is successful invitation acceptance at `450`. A peer can send 128 fresh request UUIDs and stop, or normal abandoned creations can accumulate, after which all future creators are rejected. **Minimum fix:** persist creation/expiry and requester metadata, reclaim expired/abandoned requests and corresponding private KeyPackage state, and enforce a small per-peer outstanding quota. Verify that exhausted slots become usable without deleting the vault.

5. **P2 — Received group invitations bypass the group count enforced by the decrypt loop.** Local creation checks fewer than 128 groups (`100`), but `acceptInvite` (`439-450`) has no equivalent admission check. MLS receive only selects `LIMIT 128` groups (`324`). After enough valid received invitations, at least one joined group is never trialed and therefore cannot receive any applications. Leaving groups does not remove their rows, so inactive rows also consume the query's limit. **Minimum fix:** enforce a consistent active-group limit on both create and invite; exclude inactive/pending rows before limiting, or iterate all eligible groups through a bounded resumable authentication mechanism. Verify invitations at the boundary and after leaving earlier groups.

6. **P2 — Temporary private MLS snapshots are not wiped on several control paths.** `MessagingEngine.kt:398-407` retrieves an entire secret `GroupRecord.state` for GroupLeave and leaves it unwiped, including the early return. `444-446` passes the result of `getBlob("key_packages", ...)` directly to `unpack`, which copies its private state, and only wipes the unpacked copy; the original packed secret and rejection paths remain in managed memory. **Minimum fix:** retain ownership of each raw database snapshot, put all validation/use in `try/finally`, and wipe both raw packed storage bytes and decoded secret fields, including exceptional paths. The Kotlin MLS wrapper already wipes its encoded JNI arguments/result in `MlsNative.kt:54-75`; preserve that behavior.

## Boundaries that appear sound in source

- Signal identity/ratchet state, received content, durable outbox, accepted IDs and cached encrypted ACKs commit in the same SQLCipher transaction. `ChatDatabase.transaction` does not return until `endTransaction` completes. `atomic` restores identity in place on transaction failure and invokes its rendering callback after the commit, so a rendering failure does not roll back committed ratchet state.
- Exact ciphertext retries return the cached encrypted ACK before attempting a second ratchet decrypt. New ciphertext duplicates are bound to the authenticated sender. ACKs are encrypted with the paired session and never ACKed; recipient-specific outbox entries are removed only using the authenticated sender. Group application ciphertext is encrypted once and copied to recipient rows; storage deduplicates the ciphertext.
- Incoming frames use a separate `FrameAssembler` per connection, with count/aggregate-byte bounds and expiry. The identity-free packet format does not trust a plaintext claimed sender. Native Tor currently has a 120-second inactivity deadline, mitigating indefinite idle outbound-stream retention; no indefinite-stream leak is reported here.
- Group invitation acceptance checks a request bound to the actual paired sender, local contact availability, bilateral clique proofs, exact native MLS membership and the Welcome coordinator. Membership removals require the designated coordinator, a one-member reduction, native exact membership, and valid remaining clique proofs. Coordinator leave travels over each paired Signal channel; a commit arriving before the transfer is rejected transactionally and can be retried after the transfer.
- Attachment ciphertext is authenticated and bounded before it is written; the per-file key is wrapped with the current vault file key and omitted from stored history metadata.

## Remaining verification

Two-device text/attachment exchange, lost ACK retries across restart, crash recovery, delayed/reordered MLS application/commit delivery, full three-member create/remove/coordinator-transfer flows, and lock-time secret cleanup require execution. In particular, the outbox sends different items concurrently and may deliver a removal commit before older MLS applications; native past-epoch retention and the delivery policy must be checked together. These outcomes are not established by a source-only review.

## Follow-up source check during implementation

The implementation owner subsequently added authenticated exact-ciphertext orphan adoption plus a directory sync, proof selection scoped to group members, admission filtering for gossip, per-peer KeyPackage quotas/expiry, consistent active-group caps, stream deadlines, and improved temporary-buffer ownership. These changes address substantial parts of the original findings by inspection; they were not executed in this review. Signal candidate decryption was also changed in the protocol module, so the original whole-store-per-candidate finding describes the earlier snapshot.

Two remaining concerns were sent immediately to the implementation owner and root:

- **Unthrottled MLS branch:** in the follow-up snapshot, `accept` applied its token bucket only when `packet.kind == SIGNAL`. An unauthenticated sender could choose the outer MLS kind and still trigger up to 128 native group deserializations without that budget. The budget must cover both unauthenticated branches.
- **P1 — Global FIFO and rejected controls can deadlock a peer:** the new outbox query sends only the oldest row for each destination. With four outstanding local KeyPackages permitted per peer, a fifth creation request is rejected and remains at that destination's head. Final invitations for the first groups can subsequently be queued behind it and cannot arrive to consume/free those packages. At expiry, older invitations can become permanently unjoinable. An expired or otherwise permanently rejected control can therefore block unrelated future texts to the same peer. Add a durable terminal rejection/cancellation outcome or dependency-safe scheduling; do not enforce indefinite destination-wide blocking behind a rejected control. Preserve the ordering actually required for MLS epochs.

Later implementation changes may supersede these follow-up snapshots; completion requires re-reading the final source and executing the stated scenarios.

## Terminal-rejection and queue follow-up

The later source applies the shared token bucket to Signal and MLS, bounds both
candidate searches, orders outbox entries by destination plus relevant group or
control lane, and handles explicit `ControlRejected` responses. A rejection is
authenticated, correlated with that sender's outstanding control metadata,
durably queued/cached and itself ACKed; it cannot mark an application delivered.
Only explicit precondition failures produce terminal rejection. This addresses
the previously described unthrottled MLS branch and cross-chat FIFO deadlock by
inspection; this reviewer did not execute the implementation owner's tests.

**P1 remaining in this snapshot — a cached candidate list can permanently miss a
newly joined group.** `accept` captures eligible group IDs in a TrialCursor on
first receipt. If member B's application reaches C before coordinator A's
Welcome, that list lacks the group. C can subsequently join successfully, but
retries reuse the old list and every failure refreshes its expiry timestamp, so
the group can remain untried forever. Ordering B's own group lane does not order
it against A's Welcome. Refresh/invalidate eligible candidate sets when group
admission changes or a full search cycle completes, preserving resumable offsets
only for a matching eligible-set version. The same rule should cover newly
paired Signal sessions. This was sent to both implementation owner and root.

**P1 UI integration blocker — newly created groups have no selectable chat.**
The reviewed `ChatDatabase.listChats` uses an inner JOIN to the latest message
for group rows. Creation and invitation acceptance persist only the group, and
the controller ignores createGroup's returned ID. A group with no messages is
therefore absent from the chat list; its creator cannot select it to send the
first application, and a pending group cannot be selected to cancel creation.
Include message-less groups with a nullable latest message and a created-at
fallback (or explicitly route to and retain the new group chat). A source read
confirmed this query remained unchanged when the issue was sent to root.

**Follow-up: both final P1 findings are addressed in the later source.** Signal
and MLS now query current eligibility on every attempt and call
`MessagingTrialCursor.refresh`; newly eligible candidates are added first while
existing scan progress survives mere ordering changes. The storage chat query
now LEFT JOINs group messages and falls back to group creation time. These changes
were re-read, but this reviewer did not execute late-Welcome/late-pairing or empty
group UI tests.
