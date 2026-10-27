# Review-History Identity Edge Cases 001

Status: analysis record; proposed policy only. No identity policy is adopted by this document.

## Purpose

Make multi-record retry identity behavior explicit before extending the pure reference classifier or implementing a durable adapter. This record supplements the provisional durable-history decision record and the reference-model conformance review.

## Observed behavior

The current single-record classifier in `YggdrasilLean/ReviewHistoryAppendRules.lean` behaves as follows:

- Different project: the record does not collide with this project's identity namespace.
- Same project, same idempotency key, same branch and exact payload: `alreadyCommitted(existing.eventId)`.
- Same project and same idempotency key but different branch or payload: conflict.
- Same project and same event ID under a different idempotency key: conflict.
- Otherwise: fresh.

The current multi-record classifier folds these decisions over the supplied list. A conflict dominates, but when multiple records produce `alreadyCommitted` with different event IDs, the first retry result is retained. Therefore the returned event ID can depend on history-list order.

## Edge cases that must remain visible

1. **Same key and payload, different attempted event ID.** Current behavior resolves to the existing stored event. This is consistent with treating the idempotency key as the retry identity, but may conceal a caller's failure to preserve the stable event ID.
2. **Same key and payload appears in two stored records with different event IDs.** Current fold returns whichever matching event appears first unless another record causes conflict. This is order-sensitive and should not be treated as valid history.
3. **Same key, different payload anywhere in supplied history.** Conflict must dominate any matching retry elsewhere in the list.
4. **Same event ID under a different key anywhere in supplied history.** Conflict must dominate any matching retry elsewhere in the list.
5. **Incomplete or stale history.** A list-level result cannot establish global uniqueness unless the adapter guarantees that the list is complete and current; the durable adapter must enforce uniqueness atomically.

## Policy options for edge case 1

- **Option A — key is retry authority:** same project + key + immutable payload resolves to the canonical existing event, even if the attempted event ID differs. The result must return the stored event ID and record the mismatch as diagnostic metadata if the interface supports it.
- **Option B — event ID must also match:** same project + key + payload but different event ID is a conflict. This strengthens caller-stable identity but may reject otherwise safe retries when a caller regenerates an ID.
- **Option C — explicit mismatch outcome:** return a distinct identity-mismatch outcome, avoiding both silent acceptance and generic conflict. This requires expanding the result model and adapter contract.

## Recommendation for evaluation

Evaluate Option C against the existing adapter result vocabulary and contract before adopting it. If adding a distinct outcome is too broad for the first milestone, compare A and B against the caller-supplied stable event-ID requirement and retry/reconciliation workflow. Do not silently change the classifier while this question remains open.

## Required follow-up checks

- Duplicate matching key/payload records with distinct event IDs must not be accepted as an unambiguous canonical retry.
- Conflicts found anywhere in the supplied history must dominate retry matches regardless of list order.
- Tests must distinguish list-level model properties from complete-history uniqueness and atomic backend enforcement.
- Compare both history orders for every multi-record case.
- Keep exact-string payload equality marked provisional until schema-defined logical equality is implemented.

## Status

- Analysis and observed behavior: documented.
- Option C recommendation: proposed, not adopted.
- Lean compilation for this document's related theorem file: not asserted here.
- Durable adapter, atomicity, and integration tests: not implemented/verified by this record.
