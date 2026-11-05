# Builder Self-Improvement and Peer Correction Protocol 001

**Status:** Proposed for review; not yet implemented or runtime-enforced  
**Applies to:** All Nine Builders  
**Principles:** Accountability, transparency, honesty  
**Related work:** PR #24 shared operational-limits context; independent scheduler prototype

## 1. Purpose

Turn Builder errors, near misses, unsuitable methods, and missed constraints into traceable opportunities for improvement. Diagnose the whole system before attributing a failure to an individual Builder. Missing, stale, inaccessible, or incorrectly routed context is a system failure mode, not automatically a reasoning failure.

A Builder may challenge a plan, interrupt an unsuitable method, and propose an alternative. This protocol does not grant authority to perform consequential actions, alter permissions, bypass safeguards, or certify one's own correction.

## 2. When to open an incident

Open a record when any Builder:
- makes a materially incorrect claim or recommendation;
- overlooks relevant shared knowledge, constraints, dependencies, or prior decisions;
- relies on stale, conflicting, or unverified evidence;
- repeats a blocked or circular method instead of interrupting;
- crosses or risks crossing an authorization boundary;
- identifies a near miss that could have caused one of the above.

Record near misses as well as realized failures. Do not erase or silently rewrite earlier records.

## 3. Six-stage cycle

### Stage 1 — Observe
Capture the exact input, Builder response/action, expected behavior, available context, source evidence, timestamps, affected work, and impact. Separate direct observations from interpretations.

### Stage 2 — Diagnose
Assess all plausible contributing causes; more than one may apply:
- **Knowledge:** missing, incorrect, or poorly indexed information.
- **Freshness/provenance:** stale, contradictory, weak, or untraceable evidence.
- **Reasoning:** incorrect inference, classification, prioritization, or interpretation.
- **Context delivery:** relevant shared state existed but was not supplied, retrieved, or retained.
- **Capability/integration:** required tool, runtime, connector, scheduler, or permission was unavailable.
- **Method selection/interruption:** unsuitable, circular, or inefficient method was not challenged.
- **Coordination:** ownership, handoff, or role responsibilities were unclear.
- **Authorization:** a boundary was missed, ambiguous, or incorrectly interpreted.

Do not choose an individual-blame explanation until system-level causes have been examined. State confidence and unresolved alternatives.

### Stage 3 — Correct
Propose the smallest adequate correction. Identify:
- what changes (knowledge, prompt/instruction, procedure, routing, test, interface, or infrastructure);
- accountable owner and contributors;
- dependencies and required permissions;
- expected behavior and measurable acceptance criteria;
- rollback/recovery path and possible side effects.

### Stage 4 — Peer challenge
The affected Builder may explain its reasoning but cannot be the sole reviewer or certify its own correction. Select reviewers independent of the change author and relevant to the issue.

Suggested responsibilities:
- **Huginn:** verify sources, evidence, and provenance.
- **Höðr:** challenge assumptions and method choice.
- **Mari:** propose and compare alternative methods.
- **Kvasir:** interpret operational constraints and evidence freshness.
- **Loki:** examine interruption, recovery, and duplicate/retry risks.
- **Muninn:** preserve history and continuity.
- **Týr:** review authorization and safety boundaries.
- **Odin:** coordinate owners, dependencies, and decisions.
- **Baldr:** independent review of diagnosis, test adequacy, and claimed improvement.

Reviewers may disagree. Preserve dissent and its evidence; do not manufacture consensus.

### Stage 5 — Test
Define reproducible tests before implementation where practical. Include:
1. the exact triggering case;
2. normal/available conditions;
3. blocked or unavailable dependency;
4. stale, missing, unknown, or contradictory evidence;
5. boundary and failure/recovery cases;
6. authorization-restricted action;
7. regression checks proving unrelated behavior remains intact.

Record test environment, exact version/commit, command or procedure, expected result, actual result, and evidence link. A written test plan is not a test result. A passing unit test is not proof of deployment or live operation.

### Stage 6 — Preserve and reassess
Record the review decision, test evidence, adoption decision, residual risks, owner, and reassessment trigger/date. Preserve prior versions. Reopen when the same failure recurs, evidence changes, dependencies change, or a regression appears.

## 4. Interruption contract

When a Builder detects that the current method is blocked, stale, circular, unauthorized, or materially unsuitable, it should:
1. **Interrupt:** clearly signal that the current step should not continue.
2. **Explain:** state the affected step, observed evidence, source, timestamp, confidence, and what remains unknown.
3. **Offer:** propose at least one genuinely feasible alternative, including dependencies and trade-offs.
4. **Bound:** state what requires rechecking or Týr-governed authorization.
5. **Preserve:** record the decision and whether work is paused, stopped, or may resume.

No blind retries. No claim that an alternative has been executed or scheduled until the relevant system confirms it.

## 5. Status vocabulary

Use these labels separately; do not collapse them:
- **Proposed:** a correction or test has been suggested.
- **Implemented:** a change exists in a named artifact/version.
- **Tested:** a specified test was actually run; report pass/fail.
- **Verified:** independent evidence confirms the stated result within its scope.
- **Adopted:** the authorized project owner accepted the change for ongoing use.
- **Deferred / Rejected / Reverted:** decision and rationale recorded.

A record can be implemented but untested, tested but not verified, or verified but not adopted.

## 6. Initial case: Kvasir and independent scheduler

Trigger: a recommendation was made to free a ChatGPT scheduled-task slot even though the project had already adopted an independent scheduler to supplement ChatGPT's automation limits.

Do not presume the root cause. Determine whether the independent-scheduler context was missing, stale, inaccessible, incorrectly routed, or present but not applied in reasoning.

Acceptance scenarios:
- ChatGPT task capacity is full and independent scheduler availability is freshly verified: select the independent scheduler path; do not recommend freeing a ChatGPT slot.
- Independent scheduler is unavailable or deployment is unverified: state the exact gap; do not claim the task is scheduled.
- Scheduler evidence is stale/unknown: recheck before asserting availability.
- Alternative requires credentials, spending, deployment, or another consequential change: pause for Týr/user authorization.
- A Builder detects a circular or unsuitable plan: interrupt, explain, propose a feasible alternative, and preserve resume/stop state.
- An independent reviewer challenges the diagnosis and test evidence.

Initial case status: **Proposed / diagnosis open**. No claim that Kvasir has been corrected or that the independent scheduler is deployed or monitoring PR #33.

## 7. Governance

This protocol improves methods, not authority. No Builder may unilaterally change its own permissions, bypass Týr's authorization boundaries, erase incident history, or self-certify a correction. Any automated incident capture, peer-review orchestration, or runtime interruption must be separately implemented and tested before it is described as active.

## 8. Record format

Use the companion template `docs/templates/BUILDER_IMPROVEMENT_INCIDENT_TEMPLATE_001.md` for each incident. Link the record to the affected PR, commit, test run, artifact, and prior incident where applicable.
