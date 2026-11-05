# Recovery Drill Preview 001

## Purpose
A read-only rehearsal that assesses a supplied recovery export before any restore workflow is considered.

## Checks
- Run the Recovery Integrity v2 validator, including structure, counts, provenance state, event metadata, and combined checksum.
- When an independently retained source event array is supplied, compare it with the export for exact event content and ordering.
- Classify the result as `ready`, `ready_with_limitations`, or `not_ready`.
- Emit a machine-readable report that explicitly records that restoration, overwrite, and deletion were not performed.

## Readiness rules
- `ready`: integrity is valid and available/empty provenance matches the independent source capture.
- `ready_with_limitations`: integrity is valid, but source comparison was not possible or the source journal was explicitly unavailable.
- `not_ready`: integrity validation fails or an independent source comparison mismatches.

## Safety boundary
This is an assessment helper only. It does not open a destination, write files, restore conversations, overwrite databases, delete records, or change device state. A ready result is not proof of authenticity, signer identity, or recoverability of external model/provider state. It is not authorization to perform a restore.

## Verification
Unit tests cover full match, source mismatch, missing independent capture, unavailable source journal, checksum/structure tampering, and null backup input.
