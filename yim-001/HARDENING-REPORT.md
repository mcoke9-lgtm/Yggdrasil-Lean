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

## Additional reconciliation finding

7. A partially missing creation-evidence entry was classified as INCOMPLETE before checking its other known fields. That could hide a directly contradictory digest or creation type. The reconciler now evaluates every present field first, preserving CONFLICT precedence while still recording missing fields. Two regression tests cover missing event identity and missing creation type alongside a contradictory digest.

## Additional package-path finding

8. The verifier relied on `PurePosixPath.parts` after path normalization. That normalized away repeated separators and dot components, and did not reject Windows drive-prefixed paths. The verifier now requires canonical POSIX path spelling, rejects empty/dot/dot-dot raw components, and rejects drive-prefixed paths. Two regression tests cover repeated separators and Windows drive paths.

## Added tests

Fourteen additional tests now cover malformed snapshot/package data, recovery failures, partial-evidence conflict precedence, and archive path normalization, including unhashable/empty conflict identities, fsync interruption, a failed first write, known contradictions in incomplete evidence, repeated separators, and Windows drive paths.

## Evidence

- GitHub Actions: 54 tests passed on CPython 3.13.18, Ubuntu.
- Downloaded portable package: 54 tests independently passed in a separate local container on CPython 3.13.5.
- The embedded manifest independently verified all 15 packaged project files.
- Deterministic package build and package-tampering tests passed.

## Remaining limits

- This was a focused implementation hardening pass, not an independent third-party code review; adversarial checks were conducted within this implementation session; external independent review remains outstanding.
- Interrupted replacement is tested by simulated failure, not actual power-loss or filesystem fault injection.
- There is not yet a signed release, automatic backup, multi-device recovery, or network synchronization service.
- The reconciliation rules and snapshot format remain subject to independent review before canonical release adoption.
