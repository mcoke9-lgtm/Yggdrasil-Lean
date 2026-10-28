# Full-Field Snapshot Integrity Layer 001

**Status:** implementation draft on branch; synthetic tests added; not live-integrated or adopted.

## Purpose
Keep full-field source preservation separate from graph projection. The graph adapter is intentionally narrow; this layer hashes every returned field keyed by field ID and preserves sparse-key semantics.

## Contract
- Input rows must retain connector storage ID, `createdTime`, and raw `cellValuesByFieldId`.
- Schema field IDs are provided separately. `schemaCoverage[fieldId]` records whether a field key was returned; a missing key is not treated as explicit null or empty.
- Canonicalization recursively sorts object keys, preserves array order, and hashes values using SHA-256.
- Each returned field gets a field hash; each row hash binds storage ID, creation time, field hashes/values, and schema coverage.
- Snapshots are sorted by immutable storage ID. Before comparing, the comparator revalidates manifest counts, scope fields, schema coverage, field hashes, and row hashes; malformed or internally inconsistent snapshots fail closed. Comparison reports added, missing, and changed rows/fields.
- Callers may optionally provide `trustedSnapshotHashes` to comparison. When supplied, both expected SHA-256 digests are checked against freshly canonicalized snapshots; malformed evidence and mismatches fail closed. These hashes only provide authentication if their expected values were stored or delivered through an independently trusted channel.
- The builder rejects a non-null next cursor, count mismatch, malformed record shape, and returned field IDs absent from the supplied schema (`SNAPSHOT_FIELD_NOT_IN_SCHEMA`). This prevents accepting a snapshot that the comparator would later reject for an internally inconsistent schema/field map. It cannot independently prove that a connector response is authentic or complete.

## Boundaries
This module does not fetch Airtable, write an immutable file, or inspect revision history. The optional trusted-hash argument checks evidence consistency only; the caller must establish where the expected hashes came from and protect them independently. A caller must first fetch all pages from the trusted connector, then persist the accepted snapshot and manifest in an approved durable location. Do not embed credentials or live source data in the repository.

## Trusted capture receipt — proposed contract, not implemented

The comparator's optional digest check is only as trustworthy as the source of the expected digest. A future capture boundary should produce a receipt that binds the accepted snapshot to its capture conditions. Minimum proposed fields:

- `receiptSchema` and version;
- unique `receiptId` and capture timestamp;
- `baseId`, `tableId`, snapshot schema version, and exact `snapshotHash`;
- returned record count, reported total count, schema-field-set digest, and explicit pagination-complete evidence;
- connector/API version and read-consistency statement, including whether the connector guarantees a point-in-time view;
- parent/previous receipt reference when this capture extends a recorded history;
- receipt authentication metadata, with algorithm/key identifier where signatures are used.

**Trust requirement:** the receipt and snapshot must not rely solely on the same mutable, untrusted storage location. The expected digest must be anchored by an independently protected mechanism, such as a verified signature whose key is protected separately, or a durable store with an explicitly reviewed access-control and tamper-evidence model. A hash chain alone does not prevent an attacker from rewriting the entire chain unless a prior chain head is anchored independently.

**Do not overclaim completeness:** a cursor being absent and a record count matching are necessary checks in this reference model, not proof that the connector returned every record or a consistent point-in-time view. The receipt must record the actual connector guarantees; if those guarantees are unavailable, mark completeness/consistency as unknown rather than certified.

**Status:** proposed contract only. No receipt type, signing key, external anchor, database, or durable storage implementation is selected or implemented here. Backend choice remains governed by the existing durable review-history decision records; do not silently import a provisional backend decision from another branch into this one.

## Required next tests/integration gates
1. **Verified:** the strengthened trusted-hash test passed on `6c376c98a00ee99cf8faa5cdfcdd41cfc4ec293d`: [resolver/snapshot tests](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/40699108190); [Lean build](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/40699108081). The preceding trusted-hash implementation passed both workflows on `4f627951be2850c0783b7a3251b0e759996388fd` (runs [40698544834](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/40698544834) and [40698544913](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/40698544913)). Schema-consistency tests passed on `61f7809f1530e47cc1c394d8e0fbedce671b8879` (runs [40697883788](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/40697883788) and [40697883758](https://github.com/mcoke9-lgtm/Yggdrasil-Lean/actions/runs/40697883758)).
2. Add a trusted connector-boundary wrapper that passes the unmodified complete response into this layer.
3. Persist a live full-field snapshot outside source code, including capture timestamp and connector/pagination receipt.
4. Compare against prior raw evidence, preserving missing-vs-empty semantics.
5. Inspect revision history for the two changed Airtable storage IDs.
6. Keep all source operations read-only; no automatic correction or migration.
7. Report fixture tests separately from live integration.

## Six-Part Integrity Check
1. Scope: full-field snapshot, not graph projection.
2. Identity: immutable Airtable storage IDs are comparison keys.
3. Completeness: count and cursor checks reject obvious incomplete input but do not authenticate the source.
4. Fidelity: sparse fields, null, empty strings, arrays, and nested objects retain distinct representations.
5. Status: implementation draft and synthetic tests only until CI is observed.
6. Persistence: code is committed to the working branch only after repository action; no live snapshot is persisted by this module.
