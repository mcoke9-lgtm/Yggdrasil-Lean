# THO-015 — Probe 4 Identifier Compatibility Evidence

**Probe:** THO-015-PROBE-4  
**Status:** OBSERVED — compatibility behavior recorded; no policy conformance claim  
**Branch:** `tho-015/probe1-ci-bootstrap`  
**Execution commit:** `e9f0e5fc46a6001e5db0a9c4da0b2542e14d81fa`  
**GitHub Actions run:** https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/43016268363  
**Runtime:** CPython 3.13.18; Ubuntu 26.04.1 LTS; Linux x86_64

## 1. Scope

The adopted L2 decision is to grandfather existing identifiers. This probe records the current importer behavior for case-distinct, reserved-looking, and path-like identifiers. It does not invent a normative identifier grammar or claim that every tested string is an actual historical identifier.

## 2. Core Memory snapshot observations

The harness constructed valid one-record snapshots with these identifiers:

| Case | Record ID | Observed |
|---|---|---|
| Case-distinct | `Legacy-ID` | ACCEPT |
| Case-distinct | `legacy-id` | ACCEPT |
| Windows device-like | `CON` | ACCEPT |
| DOS device-like | `AUX` | ACCEPT |
| Dot segment | `.` | ACCEPT |
| Dot-dot segment | `..` | ACCEPT |
| Path-like | `../outside` | ACCEPT |
| Empty identifier | empty string | REJECT |

A snapshot containing an exact duplicate record ID was rejected.

The importer therefore currently accepts non-empty strings without applying case-folding, reserved-word, dot-segment, or path-separator restrictions at the Core Memory snapshot identity layer. Exact duplicate record IDs remain rejected.

## 3. Provenance identifier namespace

The harness appended two events with event IDs `Legacy-ID` and `legacy-id`, and cross-used those strings as record/version identifiers. Both events were accepted. This shows exact-string uniqueness in the event-ID namespace for this case; it does not establish shared uniqueness across record IDs, version IDs, and event IDs.

## 4. Interpretation and limits

- These observations are compatible with a permissive import layer and are relevant to the adopted L2 grandfathering direction.
- They do not establish that the identifiers are present in the user's historical dataset.
- They do not prove that identifiers can safely be used as filesystem paths; current Core Memory persistence serializes identifiers inside a JSON snapshot rather than using them as path components.
- They do not establish behavior on Windows filesystems or any downstream consumer that may impose stricter rules.
- The test does not determine a future creation-time identifier policy. That requires an explicit normative decision.
- No production data was used.

## 5. Overall result

`THO015_PROBE4_RESULT=OBSERVED`

- Case-distinct identifiers: accepted.
- Reserved-looking and path-like non-empty identifiers: accepted.
- Empty identifier: rejected.
- Exact duplicate record ID: rejected.
- Case-distinct provenance event IDs: accepted.
- No source, test-suite, or specification changes.

## 6. Change control

This is an evidence-only artifact. No YIM-001 implementation, adopted L2 decision, or normative specification was changed.
