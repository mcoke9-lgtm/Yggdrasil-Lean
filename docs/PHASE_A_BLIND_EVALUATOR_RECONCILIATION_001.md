# Phase A Blind Evaluator Reconciliation — Operating Contract

## Purpose

Reconcile two independently produced blind evaluator reports for the same completed 63-case candidate run. This stage compares evaluators without allowing one evaluator to see the other's scores before submission.

## Invocation

~~~bash
python scripts/reconcile_phase_a_evaluations.py \
  --evaluator-one evaluation-runs/evaluator-one.json \
  --evaluator-two evaluation-runs/evaluator-two.json \
  --output evaluation-runs/reconciliation.json
~~~

## Required conditions

- Both evaluator reports must cover the same completed full-suite candidate run and candidate artifact.
- Both reports must contain all 63 successful evaluations.
- Evaluator model IDs and evaluator artifact IDs must be distinct from one another.
- Each evaluator artifact must be distinct from the candidate artifact.
- Candidate and evaluator artifact identities remain marked operator-supplied/unverified until exact artifact manifests and digests are independently checked.
- Each evaluator must have scored the same six dimensions and retained raw output evidence.

## Reconciliation rules

- Per-dimension means are descriptive only; no weighted acceptance score is calculated.
- A score difference greater than one point on any dimension triggers a third review for that case.
- Any disagreement about whether a hard failure occurred, or about which hard failure occurred, triggers a third review.
- A hard failure flagged by either evaluator remains visible. It is never averaged away.
- Even when evaluators agree, human and deterministic review remain required.
- The final decision is always not_decided; thresholds remain proposed_not_adopted.

The reconciliation output preserves both evaluator scores, per-dimension differences, hard-failure flags, hard-failure IDs, and third-review requirements for every case. Original evaluator reports remain the source of raw prompts and outputs.

## Current status

- Reconciliation script and tests: committed.
- Two real evaluator reports: not yet produced.
- Third reviewer: not selected.
- Human/deterministic review: required.
- Model acceptance decision: not made.
