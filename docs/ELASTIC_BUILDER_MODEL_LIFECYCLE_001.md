# Elastic Builder Model Lifecycle — Phase A Design Note

**Status:** Proposed design; not yet implemented or tested.  
**Parent specification:** `docs/PHASE_A_MODEL_CONTRACTS_MILESTONE_004.md`  
**Capability contracts:** `schemas/nine-builder-capability-contracts-v0.1.json`

## 1. Governing separation

The Nine Builders are durable identities and responsibilities. A model is a replaceable cognitive engine temporarily assigned to one or more Builder identities.

The model is not the Builder's identity, memory, authority, or continuity.

The non-model Builder runtime owns:
- identity and role contracts;
- canonical memory, provenance, and checkpoints;
- communication and message routing;
- coordination, task state, and dependency tracking;
- permissions, user authority, and audit records;
- model registry, assignment, health, resource accounting, and recovery;
- evaluation records and reconstruction manifests.

Models provide bounded cognitive capabilities: language understanding, reasoning, planning, code generation, research assistance, multimodal analysis, or other tested capabilities. Models receive only the context and tools granted through the runtime interfaces.

A Builder may be assigned:
- a compact dedicated model;
- one shared model instance with role-specific context and permissions;
- multiple specialist models for an ensemble task;
- a larger temporary model for an unusually demanding task;
- a fallback model or no model, with the runtime reporting degraded capability honestly.

Nine Builder identities do not imply nine simultaneously loaded model-weight sets.

## 2. Elastic lifecycle

Model assignments move through explicit states:

1. **UNASSIGNED** — no model is attached; Builder identity and non-model services remain available.
2. **COMPACT** — a small, tested model handles the minimum accepted capability set.
3. **STANDARD** — a general-purpose model handles normal workload.
4. **SPECIALIST** — a larger or more capable model is temporarily assigned for a named task.
5. **ENSEMBLE** — multiple models are assigned with explicit roles, inputs, outputs, and reconciliation.
6. **DEGRADED** — a fallback model or reduced capability set is active; unavailable capabilities are disclosed.
7. **SUSPENDED** — model execution is stopped while canonical state remains available.
8. **RECOVERY** — an artifact, configuration, or runtime is being restored and evaluated before assignment resumes.

Transitions require a reason, resource observations, capability impact, and an auditable assignment record. A model cannot promote itself, change permissions, or replace canonical memory.

## 3. Growth and shrink decisions

The non-model runtime observes demand and resources. It may recommend a transition; policy and user authority determine whether it is allowed.

**Grow** when:
- the current model fails a mandatory capability test for the requested task;
- a specialist capability is required and unavailable in the compact tier;
- measured latency, quality, or context limits make the current assignment unsuitable;
- an ensemble is justified by independent verification or complementary capabilities.

**Shrink** when:
- a smaller candidate passes the relevant capability contract;
- the workload no longer requires the larger model;
- host resource pressure requires a degraded mode;
- a task can be routed to a shared instance without violating isolation, privacy, or latency requirements.

Never choose a smaller model solely because its file is smaller. Never choose a larger model solely because it has more parameters. Compare tested capability, resource use, reliability, and legal availability.

## 4. What a compact seed can actually contain

The term **Reconstruction Seed** describes a portable, verifiable package, not a promise that a small model can recreate arbitrary large weights from nothing.

A seed may contain:

- a compact model artifact that itself runs and passes a defined minimum capability suite;
- portable Builder identity and capability contracts;
- prompt templates, policies, tool-interface descriptions, and runtime configuration;
- adapters or parameter deltas, if their base model and license are available;
- tokenizer and runtime metadata;
- source model and artifact digests;
- an explicit reconstruction recipe;
- a dependency manifest describing required weights, datasets, software, licenses, hardware, compute, and network access;
- evaluation fixtures, acceptance results, known losses, and recovery references.

There are three distinct reconstruction cases:

**A. Self-contained compact seed.** The included weights and runtime are enough to run the compact model on a compatible host. This is the preferred migration baseline.

**B. Expandable seed.** The compact model and manifest can identify, retrieve, configure, or assemble a larger model from separately available weights, adapters, and dependencies. The larger artifacts are not contained in the compact model unless explicitly included.

**C. Trainable recipe seed.** The package includes or references a lawful training or fine-tuning recipe, data provenance, configuration, and evaluation suite. Rebuilding requires the specified compute and source material. The compact model does not itself contain the missing training corpus or larger model weights.

Knowledge distillation and compression are generally lossy. A compact model cannot be assumed to reproduce the original larger model exactly or recover capabilities that were discarded. Any retained, degraded, or lost capabilities must be measured.

## 5. Model-to-model evaluation

One or more Builders may evaluate another model, but the authoring model is not its own sole judge.

Evaluation procedure:
1. Freeze the candidate model artifact, runtime, configuration, and test context.
2. Run the held-out capability suite against the candidate.
3. Preserve raw prompts, outputs, errors, model/artifact digests, and resource measurements.
4. Have at least two evaluators independently score outputs using the proposed rubric.
5. Use a third review for material scoring disagreement or any hard-failure dispute.
6. Run deterministic protocol, integrity, authority, and recovery checks separately from subjective quality scoring.
7. Record capability gains, losses, uncertainty, and resource trade-offs.
8. Only then recommend whether the candidate can replace, supplement, or be rejected relative to the current assignment.

A model's self-report is evidence of what it claims, not proof that it passed. An ensemble's consensus is not independent verification if all members share the same failure mode or source.

## 6. Migration and recovery invariant

The portable migration unit is not a raw model file. It is:

`Builder identity contracts + canonical state snapshot + manifests + required artifacts + runtime compatibility + evaluation evidence + recovery procedure`

The non-model runtime must remain recoverable independently of the model host. On a new host, the compact seed should be installed and checked first; canonical state should be restored into an isolated target; integrity and minimum capability suites should pass before routing work to it.

No migration should delete the source host's known-good state. No model should receive credentials or private memory embedded in its weights or seed package. Credentials remain in host-managed secure storage and are re-established through authorized configuration.

## 7. Phase A implementation boundary

Phase A defines contracts, schemas, fixtures, evaluation criteria, and a safe lifecycle design. It does not yet:
- download or run local model weights;
- compress, quantize, distill, or train a model;
- select a specific model family or license;
- create a production model scheduler;
- create a portable runtime for Linux/NUC;
- claim that any compact candidate can reconstruct a larger model;
- migrate or delete canonical data.

These are later phases gated by formal schema validation, calibrated evaluation, provider/artifact inventory, resource measurements, and user-approved host preparation.
