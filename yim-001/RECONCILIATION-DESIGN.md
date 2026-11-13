# Core Memory System — reconciliation contract

**Status:** Implementation draft; 27 automated tests pass in GitHub Actions on CPython 3.13.18. A second independent reproduction and independent review remain pending.  
**Operation:** Read-only. Reconciliation does not mutate records, promote writes, or discard evidence.

## Outcomes

- **CONSISTENT** — available evidence agrees.
- **INCOMPLETE** — required material is missing.
- **CONFLICT** — evidence directly conflicts.
- **AMBIGUOUS** — multiple interpretations or duplicate matching events are possible.
- **UNCERTAIN** — evidence is insufficient to establish a result, including malformed or unreadable history.
- **NOT_FOUND** — the requested record or event cannot be located.

## Creation-event matching

A match requires the expected record identity, expected creation type, exact record digest, unique event identity, and a valid sequence. Evidence with a missing event ID but the expected record identity is retained as incomplete candidate evidence. All findings must be preserved; the outcome is a summary, not a deletion or resolution of evidence.

Sequence representation is currently restricted to non-negative built-in integers; booleans, floats, and negative values are rejected. This validates the value's representation, not the integrity or continuity of a complete event history.

Frozen precedence:
1. CONFLICT
2. AMBIGUOUS
3. UNCERTAIN
4. INCOMPLETE
5. CONSISTENT
6. NOT_FOUND

The implementation in `src/yim001/reconcile.py` is an executable draft of this contract. Its current test result is evidence of tested behavior, not proof of complete correctness. Its evidence-envelope interface is an implementation draft, not a change to MRP-001.
