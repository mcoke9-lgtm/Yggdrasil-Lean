# ChatGPT-Integrated Adaptive Filesystem Architecture Decision 001

**Status:** revised direction proposed; ChatGPT-first objective adopted as the governing design target; integration route and backend remain unselected pending an access test.  
**Branch:** `review-history-identity-read-evidence-001`  
**Supersedes:** the Android-first target in `REVIEW_HISTORY_ADAPTER_ANDROID_MOBILE_BACKEND_DECISION_001.md` for future design decisions. That older record is preserved as historical rationale, not deleted.

## 1. Objective

Provide a durable, searchable, recoverable review history that can be used **from within ChatGPT** to preserve record identity, ordering, branch lineage, evidence, revisions, governing constraints, and the last justified next action across chats and interruptions.

The objective is not merely to build a database, an Android app, or a standalone interface. The system should help a new chat recover the correct existing branch without mistaking a new chat for a new project branch. It must distinguish proposals, tests, verification, adoption, and unknowns.

## 2. Governing constraints

- **ChatGPT integration first:** prefer a supported path that lets ChatGPT read and write the records in the normal workflow.
- **Verify access before implementation:** available integrations vary by ChatGPT plan, workspace permissions, client, and product status. Do not assume a custom MCP connection can be used from this user's Android app or Free plan.
- **Durability and evidence:** a message in a conversation, a proposed schema, a successful Lean build, or a queued write is not proof of durable history storage.
- **Separate specification from effects:** keep Lean's pure reference/classification model separate from database and network side effects.
- **Branch integrity:** each recovered checkpoint identifies the project, active branch, governing constraints, source records, verified state, open blockers, and justified next action. Chat creation alone must not switch branches.
- **Reversibility and portability:** preserve export and migration paths; avoid binding the record model to one vendor before a working route is demonstrated.
- **No false capability claims:** report precisely which reads, writes, persistence guarantees, and recovery operations have been exercised.

## 3. Candidate integration paths

| Candidate | Fit with “inside ChatGPT” | Strength | Gate / limitation | Current status |
|---|---|---|---|---|
| A. Existing ChatGPT app/connector backed by a structured workspace (e.g. Notion or Airtable) | Potentially direct through an app the user explicitly connects | Fastest low-code read/write prototype; can validate record schema and recovery workflow before custom infrastructure | Must confirm it is available to this account/client, obtain explicit user connection, and test search, append/update, ordering, lineage, and export. A connector's existence does not prove it supports the full contract. | Candidate; not connected or tested here |
| B. Custom remote MCP server / Apps SDK app | Designed for ChatGPT tool integration | Strong control over record-level operations, validation, lineage, access policy, and backend choice; can add custom UI later | ChatGPT custom-MCP availability and client support are plan/workspace-dependent; documented connection path does not establish availability in this user's Android app or Free plan. Requires hosted service, authentication, security review, and a confirmed way to connect it. | Preferred long-term candidate if access is verified; not yet eligible for implementation |
| C. Standalone web/API application | Indirect; not automatically part of the current ChatGPT conversation | Full control and broadly accessible across devices | Fails the primary UX objective unless paired with a supported ChatGPT integration; creates a separate interface and auth/workflow burden. | Fallback only |
| D. ChatGPT built-in memory / conversation history alone | Already present in ChatGPT, but not a controlled queryable record store | No extra backend setup | No guaranteed record-level search, schema, append semantics, identity constraints, transaction guarantees, or complete cross-chat retrieval contract. | Context aid only, not the durable store |

## 4. Decision

Adopt **ChatGPT-first integration as the design objective**. Do not proceed with Android-local SQLite as the default backend: it optimizes for the wrong boundary and does not itself make records available to ChatGPT.

Do **not** yet declare either a custom MCP server or a workspace connector the selected implementation. First perform the smallest useful integration feasibility test:

1. Confirm which existing ChatGPT apps/connectors this account can actually connect to, especially a structured workspace such as Notion or Airtable.
2. If an available connector supports direct read/write in ChatGPT, connect only after the user explicitly authorizes it; create a disposable pilot record set, not the canonical archive.
3. Test six operations against that pilot: exact record lookup; project/branch recovery; ordered append; update/revision with provenance; branch/fork retrieval with missing anchors explicit; export/recovery after a new chat or session.
4. If no suitable connector passes, verify custom MCP availability on a supported ChatGPT client/account before writing a server. If unavailable, report the limitation and keep the record contract/backend-neutral rather than building a separate app prematurely.

The pilot succeeds only if ChatGPT can actually invoke the supported operations and retrieve the saved records—not merely if a connector is listed or a backend can be created.

## 5. Minimal record contract for the pilot

Each record should carry, at minimum:

- stable `record_id` and `project_id`;
- `branch_id`, parent/source branch, and explicit source anchor when forking;
- sequence/order and event type;
- content plus source/evidence references;
- status: proposed, tested, verified, adopted, rejected, or unresolved;
- created/updated metadata and revision relationship;
- known gaps, failed/skipped checks, and confidence limits.

Recovery should return a compact checkpoint: project purpose; governing constraints and transition authority; active branch; verified results; proposals; blockers/uncertainties; last justified next action; and exact source records retrieved. Unknown values remain explicitly unknown.

## 6. Relationship to the existing Lean work

The Lean reference model remains useful for defining outcomes and conformance rules. It is **not** a persistence service, MCP server, ChatGPT connector, or proof that runtime writes are durable. Preserve it and use it to test the pilot's classification rules where feasible.

The existing Android SQLite decision is historical and reversible. No Android app, database backend, MCP server, or connector has been implemented or verified by this decision record.

## 7. Acceptance gate before implementation claims

A candidate is not “integrated” until evidence shows:
- the intended ChatGPT client can invoke it under the user's actual access;
- authorized reads and writes work;
- identity, ordering, idempotency, revision, and branch semantics are tested;
- a fresh session can recover the correct branch from saved records;
- failure and permission errors are visible rather than silently treated as empty results;
- export/recovery is demonstrated;
- security and access boundaries are documented.

## Six-Part Integrity Check

1. **Count:** six sections of design substance precede this check (objective, constraints, candidates, decision, record contract, Lean relationship); acceptance gate is a separate release criterion.
2. **Roles:** purpose; governing rules; candidate comparison; reversible decision/next action; minimum schema; specification boundary; verification gate.
3. **Completeness:** covers ChatGPT access, durable storage, branch continuity, provenance, and a fallback if direct integration is unavailable. It does not assume the user's exact connector entitlements.
4. **Status:** ChatGPT-first objective adopted; connector and MCP paths are candidates; integration test is proposed; no backend is selected or implemented.
5. **Recheck:** Android-local SQLite no longer follows from device choice alone. Custom MCP is not claimed to be available to the user's current plan/client. A listed connector is not treated as proof of successful persistence.
6. **Persistence:** this document records the revised direction only. It does not connect an app, create pilot data, change the Lean model, or establish runtime guarantees. Next justified action: inspect available ChatGPT connector options and test the smallest viable route with explicit user authorization.
