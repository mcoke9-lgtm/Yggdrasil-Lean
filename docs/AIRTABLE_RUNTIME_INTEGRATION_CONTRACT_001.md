# Airtable Runtime Integration Contract 001

Status: implementation contract; not a claim that production wiring is deployed.

## Goal
Invoke `readAndAnalyzeAirtableTable` from a runtime that has an authenticated Airtable connector, using actual connector responses. Keep credentials and connector authorization outside the repository and never mutate the pilot table as part of this read path.

## Required runtime behavior
1. Use the authorized Airtable connector/SDK and the configured base and table IDs.
2. Resolve Record ID and Parent Record ID field IDs from the actual table schema. Do not infer field IDs from display labels or sample values.
3. Implement `listRecordsForTable({ baseId, tableId, fieldIds, pageSize, cursor? })` as a thin wrapper over the connector's list-records operation.
4. Return each page's connector-provided `records`, `metadata.totalRecordCount`, and `nextCursor` without reconstructing counts or silently dropping fields.
5. Pass that wrapper to `readAndAnalyzeAirtableTable`; use a fresh, unique `sourceReadId` for each read attempt.
6. Record outcome code, source scope, returned count, read timestamp, and a reference to the runtime's own execution log. Do not log secrets or unnecessary cell contents.
7. Fail closed on malformed pages, changing totals, repeated/invalid cursors, missing IDs, count mismatch, or incomplete pagination.
8. Keep reads read-only. No create/update/delete operations are part of this integration.

## Trust and evidence boundaries
- The JavaScript adapter validates pagination and internal consistency; it cannot prove that a connector wrapper is honest or that Airtable's response is complete beyond the connector's own contract.
- A `sourceReadId` is a correlation identifier unless the runtime supplies an independently verifiable receipt.
- Fixture-based CI tests verify deterministic behavior, not live Airtable connectivity.
- A live integration check must be separately labelled and must report the actual execution environment, time, scope, counts, and outcome.
- Never place Airtable credentials in source, fixtures, documentation, workflow logs, or commits.

## Acceptance checks
- [ ] Schema lookup provides the exact two field IDs.
- [ ] Connector wrapper returns unmodified page metadata and cursor values.
- [ ] Two-page live read follows the connector cursor and produces one accepted analysis.
- [ ] Repeated cursor, changed total, count overrun, and missing ID fail closed in automated tests.
- [ ] Live read is read-only and logs a minimal receipt.
- [ ] Fixture CI and live integration evidence are reported separately.
- [ ] No production deployment or source-authenticity claim is made without evidence.

## Current status
The repository has the paginated adapter seam and fixture-based rejection tests. The Airtable connector used in this chat is not itself a callable function inside GitHub Actions or repository JavaScript. No deployed runtime wrapper, credentials, or live CI integration has been established by this contract. The next implementation environment must supply those explicitly; until then, continue with offline tests, contract review, and gate-bypass auditing.
