# Recovery Integrity and Provenance Reconciliation

This layer validates recovery format v2 before the application writes an export. It is a validation and reporting layer only; it does not restore data, overwrite a journal, delete records, or authenticate a signer.

## Checks
- Supported conversation and recovery format identifiers.
- Conversation thread and turn structure, unique thread IDs, and declared counts.
- Explicit provenance state: `available`, `empty`, or `unavailable`.
- Event structure, unique event IDs, positive capture timestamps, required source/schema fields, SHA-256 digest shape, selected Builder roles, and evidence-reference arrays.
- Combined export checksum using the existing format-v2 checksum verifier.
- Reconciliation of source-captured provenance events against exported events, preserving event content and ordering.

## Important limits
- A snapshot digest is preserved and checked for shape, but cannot be recomputed from the journal export because the original snapshot bytes are intentionally not stored.
- Reconciliation proves equality only against the expected source event array supplied to the validator. It does not prove the source array itself is authentic.
- The checksum detects content changes relative to the checksum field; it is not a signature, authentication, encryption, or proof of origin.
- Conversation and provenance databases are still read separately. This layer reports the existing non-atomic capture limitation; it does not create a cross-database transaction.
- An explicitly unavailable provenance journal remains a validly reported limitation, not an empty journal and not a successful provenance reconciliation.

## Runtime behavior
The export flow validates the composed document before opening the user-selected destination. A failed validation or event reconciliation prevents the write and reports an error. No recovery/restore path is added.

## Tests
Unit tests cover successful validation and reconciliation, altered event detection, duplicate IDs, malformed digest strings, conversation count mismatch, explicit unavailable state, and checksum tampering.
