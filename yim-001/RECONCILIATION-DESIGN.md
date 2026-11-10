# Core Memory System — reconciliation contract

**Status:** Implementation draft; based on the previously agreed six-outcome contract.  
**Operation:** Read-only. Reconciliation does not mutate records, promote writes, or discard evidence.

## Outcomes

- **CONSISTENT** — available evidence agrees.
- **INCOMPLETE** — required material is missing.
- **CONFLICT** — evidence directly conflicts.
- **AMBIGUOUS** — multiple interpretations or duplicate matching events are possible.
- **UNCERTAIN** — evidence is insufficient to establish a result, including malformed or unreadable history.
- **NOT_FOUND** — the requested record or event cannot be located.

## Creation-event matching

A match requires the expected record identity, expected creation type, exact record digest, unique event identity, and a valid sequence. All findings must be preserved; the outcome is a summary, not a deletion or resolution of evidence.

Frozen precedence:
1. CONFLICT
2. AMBIGUOUS
3. UNCERTAIN
4. INCOMPLETE
5. CONSISTENT
6. NOT_FOUND

The implementation in `src/yim001/reconcile.py` is an initial executable expression of this contract. It must be tested and independently reviewed before being treated as verified. Its evidence-envelope interface is an implementation draft, not a change to MRP-001.
