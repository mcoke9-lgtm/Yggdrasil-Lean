# Checkpoint 1 hardening report

## Scope

This pass reviewed the snapshot importer and portable ZIP verifier. It did not change MRP-001 Revision 4 or widen the Core Memory System's distributed-synchronization scope.

## Findings corrected

1. Snapshot format version used Python equality, which would accept `true` as integer version `1`. Import now requires an actual integer type.
2. Snapshot graph validation allowed a non-initial version with no parents. Import now requires the first version to be the root and every later version to have at least one earlier parent.
3. Snapshot import accepted duplicate parent references. Import now rejects them.
4. Snapshot import silently collapsed duplicate directional conflict annotations. Import now rejects duplicate annotations instead of silently normalizing the supplied evidence.
5. ZIP verification did not explicitly reject duplicate archive member names or unsafe manifest paths. It now rejects duplicate members, absolute/traversal paths, duplicate/reserved manifest paths, malformed entries, invalid sizes, and malformed SHA-256 strings.

## Additional finding from adversarial review

6. Malformed snapshot conflict endpoints could reach dictionary lookup before their types were checked, producing a raw `TypeError` for list/dictionary identifiers. The importer now validates non-empty string endpoint identities before lookup and raises the controlled `ValidationError` for malformed data.

## Added tests

Eight additional tests now cover malformed snapshot/package data and recovery failures, including unhashable/empty conflict identities, fsync interruption, and a failed first write.

## Evidence

- GitHub Actions: 50 tests passed on CPython 3.13.18, Ubuntu.
- Downloaded portable package: 46 tests independently passed in a separate local container on CPython 3.13.5.
- The embedded manifest independently verified all 15 packaged project files.
- Deterministic package build and package-tampering tests passed.

## Remaining limits

- This was a focused implementation hardening pass, not an independent third-party code review; adversarial checks were conducted within this implementation session.
- Interrupted replacement is tested by simulated failure, not actual power-loss or filesystem fault injection.
- There is not yet a signed release, automatic backup, multi-device recovery, or network synchronization service.
- The reconciliation rules and snapshot format remain subject to independent review before canonical release adoption.
