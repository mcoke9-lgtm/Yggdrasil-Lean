# Full-Field Snapshot Integrity Layer 001

**Status:** implementation draft on branch; synthetic tests added; not live-integrated or adopted.

## Purpose
Keep full-field source preservation separate from graph projection. The graph adapter is intentionally narrow; this layer hashes every returned field keyed by field ID and preserves sparse-key semantics.

## Contract
- Input rows must retain connector storage ID, `createdTime`, and raw `cellValuesByFieldId`.
- Schema field IDs are provided separately. `schemaCoverage[fieldId]` records whether a field key was returned; a missing key is not treated as explicit null or empty.
- Canonicalization recursively sorts object keys, preserves array order, and hashes values using SHA-256.
- Each returned field gets a field hash; each row hash binds storage ID, creation time, field hashes/values, and schema coverage.
- Snapshots are sorted by immutable storage ID. Comparison reports added, missing, and changed rows/fields.
- The builder rejects a non-null next cursor and count mismatch. It cannot independently prove that a connector response is authentic or complete.

## Boundaries
This module does not fetch Airtable, write an immutable file, or inspect revision history. A caller must first fetch all pages from the trusted connector, then persist the accepted snapshot and manifest in an approved durable location. Do not embed credentials or live source data in the repository.

## Required next tests/integration gates
1. Run Node tests in CI and review the actual result.
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
