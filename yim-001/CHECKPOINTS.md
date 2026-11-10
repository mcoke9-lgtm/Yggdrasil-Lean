# YIM-001 recovery checkpoint verification

**Status:** Additive draft implementation. This layer does not change MRP-001 Revision 4 or any existing snapshot, exchange, or recovery-backup format.

## Three separate questions

1. **Integrity:** Does a backup match its canonical SHA-256 digest and pass nested audited-snapshot/provenance validation?
2. **Continuity:** Do checkpoint records form a linked, gap-free sequence, and do their referenced backups exist and match the declared source instance?
3. **Custody:** Is there an independently retained copy or externally retained checkpoint anchor?

The first two can be checked by this implementation. The third cannot be proven by a local hash chain. A hash chain detects edits and broken links relative to the supplied records; a party able to rewrite the whole chain can recompute every digest.

## Checkpoint records

A checkpoint record binds:

- positive integer sequence number;
- caller-supplied source instance ID;
- timezone-qualified recording timestamp;
- SHA-256 digest of one validated recovery backup;
- digest of the preceding checkpoint record (except sequence 1);
- its own canonical SHA-256 digest.

The record is detached metadata. It does not alter the backup or audited snapshot. The caller is responsible for retaining each record and backup, preferably in separate failure domains.

## Verification

```python
from yim001 import (
    create_checkpoint_record,
    verify_checkpoint_chain,
)

first = create_checkpoint_record(backup_1, 1)
second = create_checkpoint_record(
    backup_2, 2,
    previous_checkpoint_sha256=first["sha256"],
)

report = verify_checkpoint_chain(
    [first, second],
    [backup_1, backup_2],
    expected_latest_sequence=2,
    expected_source_instance_id="phone-instance",
)
```

The verifier sorts by sequence and validates record structure/digests, sequence continuity, predecessor links, source identity, backup references, and nested backup validity.

## Outcomes

- `VERIFIED`: supplied records and backups are internally consistent and match the supplied latest-sequence/source anchors.
- `UNCERTAIN`: internally consistent supplied material, but no external latest-sequence anchor was provided. The verifier cannot establish that a newer checkpoint does not exist.
- `STALE`: supplied latest sequence is behind the externally supplied latest-sequence anchor.
- `INCOMPLETE`: a sequence gap, missing backup, or incomplete supplied material prevents full verification.
- `CONFLICT`: digest/link failure, fork, source identity mismatch, invalid anchor, or invalid record/backup.

Status precedence is conservative: a conflict is not downgraded to incomplete or stale. The result always reports `anchor_authenticated: false`. The verifier does not authenticate the source ID or the external anchor; the caller must obtain those from an independent trusted channel.

## Custody procedure (operational, not automated)

For stronger recovery confidence, retain at least two copies in distinct failure domains (for example, the phone and an independently controlled offline medium). Retain the checkpoint record and the latest-sequence anchor separately from the primary backup set. Periodically verify copies after transfer. Record where each copy is held and who controls access in an external custody register; do not put secrets or access tokens in checkpoint metadata.

A second directory, filename, partition, or account is not automatically independent custody. Independence depends on actual control, credentials, infrastructure, and failure domains. This implementation cannot inspect or prove those facts.

## Limits

- No signatures, key management, identity authentication, trusted timestamping, or remote witness.
- No automated scheduling, backup rotation, deletion, or remote replication.
- No concurrency control or distributed consensus.
- A valid digest proves consistency with the supplied bytes, not authorship, recency, completeness, or trustworthiness.
- A caller-supplied expected latest sequence is an assertion, not an authenticated fact.
- A complete chain can be rewritten by an attacker who can replace all records and recompute hashes.
- Checkpoint records are currently caller-retained data; this module does not create a protected append-only filesystem or remote catalog.

## Test coverage

Tests cover record digest validation, sequence/predecessor requirements, complete verification with an explicit anchor, uncertainty without an anchor, missing backups, stale sequence, broken links, forks, source identity mismatch, and invalid external anchors.
