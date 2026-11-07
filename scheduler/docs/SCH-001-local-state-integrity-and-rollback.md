# SCH-001 — Local state integrity and rollback detection limits

**Status:** implemented local integrity checks and deterministic tests. Not a rollback-proof authority witness, independent backup verification, or production recovery certification.

## What the local integrity sidecar does

`JsonFileStore` now maintains a companion file at `<state-file>.integrity.json`. The sidecar records:
- sidecar schema version;
- monotonically increasing save revision within the surviving sidecar history;
- SHA-256 of the exact serialized state-file bytes;
- last update timestamp for diagnostics.

On load, the store requires the state file and sidecar to either both be absent (new local store) or both be present and mutually consistent. It rejects malformed JSON, invalid sidecar shape, unsupported state shape, checksum mismatch, or a one-sided missing file. It does not silently replace or normalize a damaged state file. A pre-sidecar legacy state file is quarantined until an operator explicitly adopts it with `adoptLegacyState({ expectedSha256 })`, supplying a SHA-256 calculated independently from the preserved original bytes. Adoption creates the sidecar without rewriting the legacy state; the next normal save migrates its schema. It is never performed automatically at startup.

Writes use unique temporary filenames, sync the temporary file, atomically rename it, and sync the containing directory. The state file is replaced first and the sidecar second. A crash between these replacements intentionally creates a detectable mismatch and causes startup to stop for recovery rather than guess which version is authoritative.

The runner validates the state through `listTasks()` before opening the dashboard or publishing readiness. On validation failure it releases the process lock and exits without starting the service.

## What it does not prove

The sidecar is stored on the same local filesystem as the state file. It is an integrity check, not an independent trust anchor.

- If only the state file is corrupted, replaced, or restored to an older version while the current sidecar remains, the checksum mismatch is detected.
- If only the sidecar is missing or replaced, the pair mismatch or checksum mismatch is detected.
- If both the state file and its matching sidecar are restored together from the same older backup, the old pair is internally consistent and can pass validation. This is a known, explicitly tested limitation.
- The revision is monotonic only relative to the sidecar that remains present. It is not protected from coordinated rollback.
- SHA-256 detects byte changes only when the sidecar itself is trusted; it does not authenticate a maliciously rewritten pair.
- This does not detect deletion of both files followed by treating the path as a new installation. Installation identity and expected-state policy remain an operator/deployment responsibility.

Therefore, this sidecar does **not** solve AUD-AC-02. It cannot prevent authority epoch reuse after coordinated backup rollback.

## Required backup and restore procedure

1. Stop the scheduler and preserve the state file, sidecar, process lock, backups and logs before changing anything.
2. Record the source backup identity, timestamp, and both files' SHA-256 values in an independently retained recovery record.
3. Restore the state file and sidecar only as a pair. A mismatched pair must remain quarantined; do not regenerate the sidecar to make it pass.
4. Compare the restored authority records, transfer IDs and save revision against a checkpoint retained outside the backup set.
5. If the checkpoint is missing, older, or conflicts with restored state, declare authority history unresolved. Keep transferred tasks inactive and do not advance ownership.
6. Resume only after a reviewed reconciliation establishes which task/run/handoff records and external effects are valid. Record the reviewer, evidence, and decision.

A recovery record kept in the same backup set is not independent. A robust rollback-proof design requires a separately controlled monotonic witness, append-only remote log, or consensus-backed authority service. None is implemented in this local scheduler increment.

## Verification

Deterministic tests cover new empty store initialization, sidecar creation and valid reload, state-byte corruption, missing sidecar, restoring an old state file against the current sidecar, and coordinated restoration of an old state file and its matching sidecar, demonstrating the known detection gap.

The coordinated-rollback test is intentionally a passing reproduction of a limitation, not a safety pass.

CI results must be reported against the exact commit tested. Tests do not simulate power loss, disk/controller cache failure, filesystem-specific durability, hostile sidecar rewriting, or independent-witness recovery.

## Adoption boundary

No external monotonic witness, backup service, production coordinator, distributed fencing, automatic restore, task activation, merge, or deployment is implemented. `AuthorityCoordinator` remains `LOCAL_REFERENCE_GRANT_ONLY`.
