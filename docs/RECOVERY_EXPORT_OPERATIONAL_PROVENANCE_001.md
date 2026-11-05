# Recovery Export: Operational Provenance

The conversation backup export is upgraded to format version 2. It preserves the existing conversation `threads`, `thread_count`, and `turn_count` fields and adds `operational_snapshot_provenance`.

The provenance section reports one of three explicit states:
- `available`: event records were read; `event_count` and `events` are present.
- `empty`: the journal was read successfully and contained zero records.
- `unavailable`: the journal could not be read; `events` and `event_count` are null, and only the exception class is recorded. This is never represented as an empty journal.

The combined export recalculates `content_sha256` over the complete version-2 document with the checksum field omitted. The checksum detects accidental or deliberate content changes after export; it is not a digital signature, authentication, encryption, or proof of origin. Conversation and provenance databases are read separately, so the export is not an atomic cross-database transaction and may represent slightly different capture instants.

The user still chooses the destination through Android's document picker. No automatic network transfer, backup upload, app installation, deletion, or device-wide scan is introduced. Existing format-version-1 exports remain distinguishable and are not silently rewritten.

Automated tests cover complete provenance, genuinely empty provenance, unavailable provenance, checksum tampering, and unsupported format/status rejection.

The Android journal test workflow is configured to run for this stacked follow-up branch as well as the underlying runtime foundation branch.
