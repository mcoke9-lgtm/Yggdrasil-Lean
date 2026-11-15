# Adaptive Filesystem — Branch Management Operating Model 001

**Record ID:** AFS-BMOM-001  
**Created:** 2026-10-26  
**Status:** VERIFIED — READ-BACK of the saved Library copy completed on 2026-10-26  
**Purpose:** Coordinate the Adaptive Filesystem, Thoth Tarot, and language work as one managed system without merging their evidence or forcing the user to supervise routine operations.

## 1. Reanalysis: the actual failure mode

The problem is not simply that the records are scattered. The existing unified register already contains a useful architecture and test queue. The deeper problem is that the queue describes work but does not yet enforce a branch lifecycle: intake, ownership, prerequisites, execution, evidence, revision, and recheck. Several records repeat status snapshots, while the branch dependencies and next runnable batch are not promoted into a single operational view.

Therefore the correction is **not to replace the source archive or rewrite every old record**. It is to add a coordination layer that routes work, preserves source identity, and keeps status current. Existing records remain evidence; this operating model is a new coordination record.

## 2. Three work branches

### Roots — Adaptive Filesystem

Owns durable records and the rules for handling them:
- immutable source preservation; source IDs, dates, versions, and provenance;
- adaptive index and branch registry;
- test/decision/blocker registers;
- evidence retrieval, recovery, and read-back verification;
- append-only change history and reversible corrections.

The roots preserve both accepted and rejected claims. A new summary never silently replaces its source.

### Trunk — coordination and integrity

Owns cross-branch operations, not every ordinary task:
- a single branch registry and dependency graph;
- batch selection of runnable work;
- cross-branch conflict and duplicate detection;
- status vocabulary and evidence standards;
- shared changes, reconciliation, and release/read-back checks;
- escalation only for genuine policy choices, authorization needs, or blockers that cannot be resolved from evidence.

The trunk is continuously aware but selectively intervenes. Routine intake should not wait for central dispatch.

### Branches — domain work

**Branch A: Adaptive Filesystem engineering** — roots/trunk/branches/leaves architecture, routing, recovery, concurrency, identity repair, deletion receipts, particle/wave count and classification audits, and retrieval pilots.

**Branch B: Thoth Tarot** — Tarot Star, six-member set, RA center, Valknut, hexagrams, build/tool arrangements, symbolic prompts, and graph topology. Tarot is an orientation and memory interface, not mathematical authority. Original edges and vertex mappings must come from original evidence; SAT–CLIQUE candidate edges must never be substituted for Tarot Star edges.

**Branch C: Language work** — Gasametu grammar and roots, English↔Gasametu translation, the three distinct “Me” components, typed intermediary graph, parse/type checks, and translation provenance. The recovered G1–G6 outcomes remain fixed. `Argument` and the G4 derivation remain unresolved; the candidate checker is not the historical parser.

Branches share methods and dependencies, but each keeps its own source records, vocabulary, acceptance tests, and unresolved questions.

## 3. Branch lifecycle

Every work item follows the same six-state lifecycle:

1. **Intake:** identify requested outcome and preserve exact source/artifact.
2. **Classify:** assign one owning branch; add cross-branch dependencies where evidence supports them.
3. **Prepare:** state preconditions, expected result, method, and what counts as failure.
4. **Execute:** run the actual available test or explicitly label a conceptual review. Never simulate missing software or source data.
5. **Reconcile:** compare expected/actual results, preserve conflicting evidence, update dependencies and status.
6. **Commit and verify:** append result to the register, save the revision, retrieve it, and verify the read-back before claiming persistence.

A work item may be blocked, but it must never become invisible. Each blocker names the missing artifact/capability and the smallest evidence that would unblock it.

## 4. Shared work-item schema

Each test, decision, or research task must have:

- stable ID and title;
- owning branch and any dependent branches;
- source references and version/provenance;
- expected result / acceptance criterion;
- prerequisites and execution mode;
- status and date of last verified evidence;
- observed result, evidence pointer, and limitations;
- blocker and next action;
- supersedes / superseded-by relationship, if applicable;
- read-back status for saved updates.

Do not make the user the default task router. The coordinator should infer ownership from the subject and known dependencies, batch compatible tasks, and ask the user only when a real decision or missing authorization is essential.

## 5. Status vocabulary

- **PROPOSED:** model or procedure suggested, not accepted or tested.
- **SPECIFIED — NOT RUN:** acceptance criteria exist; execution evidence absent.
- **QUEUED — RUNNABLE:** exact inputs and capability are available; not yet executed.
- **BLOCKED — INPUT NEEDED:** specific missing source, code, toolchain, permission, or policy.
- **CONCEPTUALLY REVIEWED:** reasoning/fixture reviewed, not an implementation test.
- **EXECUTED — PASS / PARTIAL PASS / FAIL:** actual run plus observable evidence exists.
- **VERIFIED — READ-BACK:** saved artifact was retrieved and its relevant contents checked.
- **ADOPTED:** explicitly authorized design/policy. Discussion alone does not imply adoption.

A source file being found is not proof that its software runs. A test specification is not a test run. A read-back verifies persistence of that record, not correctness of every claim inside it.

## 6. Branch dependency rules

- **Filesystem engineering → Thoth Tarot:** supplies source preservation, graph data provenance, and test discipline. It does not supply missing Tarot edges.
- **Filesystem engineering → Language:** supplies versioning, parser test harness conventions, and evidence/ambiguity handling. It does not invent grammar productions.
- **Language → Filesystem engineering:** can test typed graph and routing concepts as a domain fixture, but only once its grammar and expected parse are adequately defined.
- **Thoth Tarot → Language:** symbolic associations may generate questions, never authoritative grammar or mathematical constraints.
- **Thoth Tarot ↔ graph/classification tests:** any shared structural mapping must state its rule and preserve the source graph. Equal counts alone do not establish a mapping.

When a dependency is uncertain, record it as a hypothesis rather than making the downstream branch wait on it as if established.

## 7. Initial coordinated test batch

### Branch-management checks (can be audited against current records now)

| ID | Check | Initial result | Status |
|---|---|---|---|
| BM-01 | One named owner for each of the three domain branches | Adaptive Filesystem, Thoth Tarot, Language work assigned above | CONCEPTUALLY REVIEWED |
| BM-02 | Preserve original records and avoid overwriting historical evidence | Operating model requires additive records and explicit supersession | SPECIFIED — NOT RUN as an automated check |
| BM-03 | Distinguish specification, conceptual review, execution, and read-back | Status vocabulary defined; prior register contains distinct categories | CONCEPTUALLY REVIEWED |
| BM-04 | Surface dependencies without merging domain truth | Dependency rules defined; cross-branch links remain typed/hypothetical until evidenced | SPECIFIED — NOT RUN as an automated check |
| BM-05 | Every blocked item identifies a concrete missing prerequisite | Prior register identifies blockers for AF-04–AF-06, AF-10–AF-11, TW-01, GA-01, and YG-01; completeness across all records not audited | PARTIAL PASS — bounded review only |
| BM-06 | Saved updates require read-back before persistence is claimed | Prior unified register was found and read in this session; its body still carries a historical “read-back pending” line | PARTIAL PASS — the existing record is now verified retrievable/readable; historical line remains as provenance and is superseded by this read-back result |

These are audits of the current documentation and proposed process, not tests of a deployed Adaptive Filesystem application.

### Next execution queue by branch

**A — Adaptive Filesystem engineering**
- AF-01/02/07/08: runnable only if the actual routing/dependency/identity implementation or a fully specified executable fixture is available. Current register does not establish that implementation; do not report these as passed.
- AF-11 particle/wave counts: intended counts remain particle = 6 (Lower TERA, Upper TERA, Lower FIRA, Upper FIRA, WATA, AIRA), wave = 5 (Lower TERA, Upper TERA, FIRA, WATA, AIRA). Actual 7/6 object and construction trace remain unavailable; root cause NOT ASSESSABLE.
- AF-12 local/global classification: requires exact graph universe and definitions before an exhaustive run can be valid.
- AF-03/04/05/06/09/10 remain blocked by implementation, topology, or policy inputs as documented in the prior register.

**B — Thoth Tarot**
- TW-01 source recovery: find the original Star drawing/thread/derivation; capture literal six edges, vertex-to-member mapping, generation rule, date, and provenance.
- Until source recovery, no degree sequence, connectivity, cycle, rotation, or Valknut correspondence claims should be newly asserted.
- Keep Tarot's interface prompts separate: OTO asks “Why is this connected?”, Unicursal Hexagram asks “How is this useful?”, Fool asks “What path does this provide?”

**C — Language work**
- GA-01: rebuild parser only after recovering `Argument` and the eight-root inventory. Keep syntax parsing separate from value/type checking.
- Lock the recorded regression outcomes: G1–G4 ACCEPT (G4 derivation unresolved); G5–G6 REJECT. Do not alter expectations to fit the candidate checker.
- Translation tests L1–L12 remain specified/conceptually reviewed, not executed against an implemented translator. L6 needs a source-backed multi-root expression and validated grammar.

## 8. Current decision and difficulty register

### Decisions already supported by the record
- Preserve source artifacts; adapt indexes rather than silently rewriting sources.
- Use one coordination layer and three distinct domain branches.
- Keep routine processing local where safe; coordinate cross-branch changes selectively.
- Keep Tarot symbolic structure separate from mathematical proof.
- Keep Gasametu historical grammar evidence separate from provisional checker assumptions.
- Keep intended particle/wave counts at 6/5; do not alter conceptual members to conceal a construction discrepancy.

### Genuine open decisions / blockers
- **D1:** closure criteria when external recipients cannot be verified.
- **D2:** consent withdrawal versus applicable retention obligations.
- **D3:** correction propagation to recipients/copies outside system visibility.
- **Count audit:** actual faulty 7-item/6-item object and first divergence stage not recovered.
- **Tarot:** original Star edges and vertex mapping not recovered.
- **Language:** `Argument`, full root inventory, and G4 derivation unresolved.
- **Formal validation:** Lean toolchain/build outputs and exact particle/wave graph-classification definitions not verified.

Do not elevate these to user decisions unless the next evidence-gathering pass shows a genuine policy choice or irreducible ambiguity. Missing source artifacts should first trigger another bounded retrieval pass, not a request for the user to reconstruct them from memory.

## 9. Batch operating cadence

At the start of a session:
1. Read the current control model, index, and affected branch records.
2. Build a dependency-aware queue across all three branches.
3. Run all runnable tests in a batch, grouped by execution environment.
4. For blocked work, conduct targeted source recovery and name the exact missing item.
5. Update the branch registry and decision register additively.
6. Re-read the saved update and verify status/content.
7. Report a compact outcome: completed, partial, blocked, decisions required, next batch.

No claim of background work, deployed automation, code execution, or persistent saving is allowed without tool evidence. Do not ask the user to manage routine branch assignments or approve every ordinary next step.

## 10. Six-Part Integrity Check

1. **Count:** Three domain branches are explicitly defined; six lifecycle stages are listed.
2. **Role mapping:** Roots = durable evidence; trunk = coordination; branches = domain work; leaves remain the intake/routing layer within the filesystem architecture.
3. **Completeness:** Includes Adaptive Filesystem engineering, Thoth Tarot, language work, their dependencies, test statuses, blockers, and next batch; this is not a complete Library inventory.
4. **Status discipline:** Conceptual reviews, specifications, executed tests, blocked items, and read-back verification are distinguished.
5. **Correctness/recheck:** Preserve 6/5 intended particle/wave counts, unresolved Tarot topology, and unresolved Gasametu grammar; verify saved content by read-back.
6. **Persistence/uncertainty:** Library upload succeeded and the saved copy was retrieved and read back. No root cause or implementation success is inferred from documentation alone.