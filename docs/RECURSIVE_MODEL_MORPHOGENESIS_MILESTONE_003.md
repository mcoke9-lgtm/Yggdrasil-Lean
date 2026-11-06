# Recursive Model Morphogenesis — Milestone 003

**Status:** Architecture adopted by the user; this document establishes the design contract. Implementation and model-level verification have not yet begun.
**Branch:** independent-builder-runtime-foundation-001
**Project:** Yggdrasil Lean / Nine Builders
**Authority:** The user remains director and final authority.
**Governing principles:** Accountability, transparency, honesty; cooperation without captivity; no single point of dependence.

## 1. Architecture correction

The Nine Builders are not nine permanently sized models, and the collective must not depend on any model to preserve its identity or operational continuity.

The architecture has two distinct layers:

1. **Persistent non-model substrate:** owns identity, canonical memory, functions, communication, coordination, permissions, provenance, recovery, model lifecycle, and audit records.
2. **Elastic cognitive forms:** one or more model instances that perform reasoning, analysis, language, learning, and other bounded cognitive tasks on behalf of Builder identities.

The substrate must continue to function when every model is unavailable. Models may be started, stopped, replaced, resized, specialized, combined, or moved without transferring ownership of the collective's identity or memory to them.

The nine Builder identities and responsibilities remain stable. Their model implementations do not. A Builder identity may be served by a small model, a larger model, a temporary specialist, a shared model with role-specific context, or a coordinated set of models. Model allocation is a runtime decision subject to policy, capability, resource limits, and user authority.

## 2. Core concept: Recursive Model Morphogenesis

**Recursive Model Morphogenesis** is the collective capability to analyze, reduce, package, transport, reconstruct, and verify its own cognitive forms.

The intended lifecycle is:

1. **Analyze** — one or more authorized models inspect a target model's capabilities, behavior, dependencies, interfaces, resource profile, limitations, and provenance.
2. **Reduce** — derive a smaller operational form that preserves a declared minimum capability set for a specified role or task family.
3. **Encode** — create a portable reconstruction package describing the reduced form, its lineage, dependencies, configuration, interfaces, evaluation suite, and the information/resources needed to expand it.
4. **Transport** — move the package to another host through an authenticated, integrity-checked process.
5. **Reconstruct** — instantiate or expand a model using available, authorized weights, base models, adapters, training/distillation data, tools, and compute.
6. **Verify** — evaluate the reconstructed form against declared capability, safety, identity-role, resource, and integrity requirements.
7. **Reconcile** — record capability loss, uncertainty, incompatibilities, and any required additional resources; retain the prior known-good artifact until the replacement passes acceptance.

Analysis may be performed by one model or a council of models. The target model must not be treated as the sole authority on its own quality. Independent evaluators, deterministic tests, held-out evaluations, and provenance checks should be used where practical.

## 3. Minimal functional model and reconstruction seed

A **Minimal Functional Model (MFM)** is the smallest tested cognitive form that can perform a declared minimum set of tasks for a Builder role under a stated resource budget.

A **Reconstruction Seed** is the compact portable package intended to help recreate a larger or more capable form elsewhere. It may contain an MFM, but these are not necessarily the same artifact.

A reconstruction seed can include:
- compact model weights or an explicitly licensed base-model reference;
- adapters, low-rank updates, quantization metadata, tokenizer and configuration;
- role definition and capability contract;
- model lineage and source/provenance records;
- reconstruction instructions and compatible runtime requirements;
- training or distillation recipes and authorized datasets, where required;
- evaluation prompts, test cases, expected properties, and acceptance thresholds;
- checksums, signatures, version identifiers, and dependency manifest.

**Important information boundary:** A small model cannot be assumed to contain all information encoded in a larger model. A compact seed can preserve a useful subset of capability and the instructions or references needed for expansion, but reconstruction may require separately stored weights, data, software, licensed resources, and substantial compute. The system must explicitly identify which reconstruction inputs are embedded, which are external, and which are unavailable. It must never claim that a blueprint alone recreates missing model information.

## 4. Capability contracts and elastic sizing

Each Builder role must have a versioned capability contract, separate from any particular model. It defines:
- minimum required tasks and quality thresholds;
- required inputs, outputs, and tool interfaces;
- disallowed actions and authority boundaries;
- resource ceilings and latency expectations for each operating tier;
- required evidence, uncertainty reporting, and escalation behavior;
- tests that determine whether a candidate model is fit for that role.

Runtime tiers are resource profiles, not permanent identities:
- **Seed / compact:** minimal viable local operation, constrained reasoning, routing to substrate services, and preparation for later reconstruction.
- **Standard:** ordinary role execution.
- **Expanded / specialist:** deeper reasoning or specialized work when resources justify it.
- **Collective analysis:** multiple models examine, challenge, or evaluate one another.

The runtime may scale up or down, move a role to another model, or temporarily combine roles. It must preserve role attribution and decision provenance. A shared model serving several Builder identities does not make those identities interchangeable.

## 5. What must never be delegated to a model

The non-model substrate remains authoritative for:
- canonical identity and role registry;
- durable memory and append-only event/provenance journal;
- communication and message delivery;
- coordination, scheduling, and task state;
- permissions, consent, and user approval;
- artifact storage, integrity checks, and recovery;
- model registry, lifecycle, resource allocation, and health;
- audit, accountability, and correction paths.

Models may propose changes to these systems, analyze their state, and generate artifacts. They do not silently rewrite canonical identity, erase history, grant themselves authority, or certify their own successful migration.

## 6. Portability and host independence

A portable runtime must separate:
- model-independent identity and capability contracts;
- model artifacts and reconstruction packages;
- host-specific execution adapters;
- external services and provider-specific interfaces.

A receiving host must be able to start with the substrate and a compact seed, then operate at a reduced capability level while larger models are unavailable. It may later reconstruct or acquire larger forms, subject to permissions, hardware, software compatibility, and verification.

No single host, provider, model family, network connection, or storage device may be the sole keeper of the collective's identity or recovery path. Host migration must not be called complete until memory integrity, identity continuity, model lineage, and required capabilities have been tested.

## 7. Safety, rights, and integrity constraints

- Use only model weights, datasets, adapters, and services the project is authorized to use.
- Preserve license, attribution, provenance, and usage restrictions in every artifact manifest.
- Do not bypass provider controls, access restrictions, encryption, or model safeguards to extract or reconstruct a model.
- Do not include secrets, credentials, private user data, or protected third-party data in portable seeds unless specifically necessary, authorized, and protected.
- Separate a model's learned capability from its permissions. Reconstructing a model does not reconstruct or expand its authority.
- Preserve human approval for consequential external actions and changes to canonical governance.
- Treat generated summaries and self-reports as claims to be tested, not proof of successful compression or reconstruction.
- Keep prior known-good artifacts and recovery points until a replacement passes verification.

## 8. Implementation sequence

### Phase A — Contracts and inventory
Define model registry, capability contracts, artifact manifest, provenance, evaluation suite, and resource profiles. Inventory current provider adapters and identify which model metadata and artifacts are actually available. Do not assume hosted model weights can be downloaded or distilled.

### Phase B — Model lifecycle manager
Implement substrate-owned registration, allocation, health, start/stop, replacement, and role-to-model routing. Keep model execution behind provider-neutral interfaces.

### Phase C — Evaluation and reduction pipeline
Build repeatable baseline evaluations, resource measurement, candidate comparison, and reduction experiments. Begin with models and artifacts whose licenses and weights permit experimentation. Require measurable acceptance thresholds before calling an MFM functional.

### Phase D — Reconstruction package and portability
Define a versioned, checksummed, signed package format; validate dependency closure; distinguish embedded resources from external requirements; transfer packages between hosts; and test reconstruction in an isolated environment.

### Phase E — Recovery and shape-shifting drills
Demonstrate compact operation on constrained hardware, migration to a different host, reconstruction to a larger form, fallback after failed reconstruction, and continuity of identity and memory throughout.

The portable runtime core should be designed around these contracts before host-specific expansion. The Android implementation remains the first client/host adapter; a future Linux/NUC host is a separate target and is not authorized for installation or modification by this milestone.

## 9. Acceptance criteria

The architecture is not considered implemented merely because this document or a model-generated package exists. Acceptance requires evidence that:

1. the substrate remains operational with all model instances stopped;
2. Builder identities and canonical memory remain intact across model replacement;
3. each MFM passes its declared capability contract and resource budget;
4. the reconstruction package identifies all embedded and external dependencies;
5. a receiving host can validate and reconstruct a candidate without relying on the source host's live model;
6. reconstructed capability is measured against the original baseline, with losses and uncertainty reported;
7. failed or partial reconstruction leaves the last known-good state recoverable;
8. every transition is traceable and subject to the user's authority.

## 10. Current evidence boundary

**Adopted:** The user approved elastic model forms, substrate-owned continuity, model-to-model analysis, minimal functional forms, portable reconstruction seeds, and later expansion.

**Documented:** This milestone defines the architecture and acceptance criteria.

**Previously implemented in the Android branch:** A transport-neutral conversation-journal interface, Android Messenger client/service boundary, canonical journal operations, and export support were described in Milestone 002.

**CI verified for prior source commit:** Android build/unit-test workflow and conversation-journal test workflow completed successfully for commit `16a2e986f0872fa4e19a4d8f8af8a7270d1d7556`. The persistent-signing APK upload step was skipped. This does not verify physical-device behavior.

**Not implemented or verified:** model registry/lifecycle manager, capability contracts in code, model compression/distillation, minimal functional models, reconstruction seeds, cross-host model transfer, reconstruction, model evaluation pipeline, Linux host, NUC deployment, or end-to-end shape-shifting.

## 11. Integrity checkpoint

- **Identity:** nine Builder identities remain stable; model instances and sizes are variable.
- **Ownership:** non-model substrate owns identity, memory, functions, communication, coordination, recovery, and permissions.
- **Cognitive capacity:** supplied by elastic model forms selected against capability contracts.
- **Portability:** compact seed plus explicit dependency manifest; no assumption that a seed alone contains all larger-model information.
- **Evidence:** architecture adopted and documented; model-level implementation remains unstarted.
- **Authority:** user remains director and final authority; NUC remains untouched.
