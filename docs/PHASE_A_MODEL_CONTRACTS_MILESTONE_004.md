# Phase A — Elastic Model Contracts and Inventory (Milestone 004)

**Status:** Contract specification drafted and committed; implementation and live model inventory remain pending.
**Branch:** `independent-builder-runtime-foundation-001`
**Architecture parent:** Recursive Model Morphogenesis — Milestone 003
**Authority:** The user remains director and final authority.
**Governing requirements:** accountability, transparency, honesty; substrate-owned continuity; elastic cognitive forms; no single point of dependence.

## 1. Purpose and design rule

Phase A defines the contracts that later model lifecycle, reduction, portability, and reconstruction code must obey.

**A Builder identity is not a model. A capability requirement is not a model size. A reconstruction seed is not proof of reconstruction.**

The persistent non-model substrate owns identity, canonical memory, communication, coordination, permissions, provenance, artifact storage, recovery, and model lifecycle. Model instances supply replaceable cognitive capacity.

The nine Builder identities remain:
- Huginn
- Muninn
- Kvasir
- Mari
- Höðr
- Týr
- Loki
- Odin
- Baldr

A single model may temporarily serve more than one identity, and one identity may use several models. The registry must preserve identity-to-instance attribution even when execution is shared. The model allocation is elastic; identity and authority are not.

## 2. Contract A — Builder identity and model assignment

The substrate maintains a canonical Builder identity record separately from model records.

Required identity fields:
- `builder_id`: stable machine identifier;
- `display_name`: one of the nine established names;
- `role_contract_id`: versioned responsibility/capability contract;
- `governance_status`: active, paused, retired, or under_review;
- `authority_scope`: permitted and prohibited actions;
- `memory_namespace`: substrate-owned canonical memory reference;
- `created_at`, `updated_at`, and provenance references.

Required assignment fields:
- `assignment_id`;
- `builder_id`;
- `model_instance_id`;
- `assignment_mode`: dedicated, shared, ensemble, fallback, or evaluation;
- `capability_contract_version`;
- `resource_profile_id`;
- `valid_from`, optional `valid_until`;
- `approval_ref` where policy requires human approval;
- `assignment_state`: proposed, active, suspended, ended, or rejected.

Rules:
1. A model cannot create, rename, retire, or authorize a Builder identity.
2. Model replacement must not replace canonical memory or identity.
3. Shared execution must retain per-Builder role prompts, context boundaries, attribution, and audit records.
4. No model self-report can grant it a role or authority.
5. Assignment changes are journaled; prior assignments remain inspectable.

## 3. Contract B — Versioned capability contract

Every Builder role receives a capability contract before a minimum functional model (MFM) can be declared.

Required fields:
- `contract_id`, `version`, `builder_id`;
- `purpose` and explicit responsibility boundaries;
- `required_capabilities[]`;
- `optional_capabilities[]`;
- `prohibited_capabilities_or_actions[]`;
- `input_schema_refs[]` and `output_schema_refs[]`;
- `tool_interface_refs[]`;
- `evidence_and_uncertainty_rules`;
- `escalation_conditions[]`;
- `acceptance_suite_id`;
- `minimum_quality_thresholds`;
- `maximum_resource_profile`;
- `latency_expectations`;
- `version_history_ref`.

Each capability requirement must define:
- observable task;
- expected output properties;
- test method and representative cases;
- minimum acceptable quality;
- failure conditions;
- whether deterministic verification is possible;
- evidence required to claim it passed.

A capability is not accepted merely because a model says it can do the task. A task without a measurable test remains **unvalidated**, not silently promoted to required-and-passed.

### Established nine-role register

The following specializations were adopted in the earlier Nine Builders design and are carried forward as the initial responsibility map. They are specializations, not exclusive abilities: every Builder should be able to understand, design, construct, test, repair, and maintain when required. Specialization concentrates responsibility and strengthens cross-checking; it does not make the other Builders incapable.

| Builder identity | Primary responsibility | Distinctive work products / evidence |
|---|---|---|
| **Huginn** | Architecture and direction | Component boundaries, dependencies, requirements, work breakdown, release structure |
| **Muninn** | Memory and continuity | Immutable versions, history, provenance, checkpoints, restoration and recovery records |
| **Kvasir** | Research, discovery, and transformation | Source extraction, evidence status, structured knowledge, parsing and format conversion |
| **Mari** | Communication and interfaces | Input/output contracts, message formats, error semantics, module and host communication |
| **Höðr** | Construction and implementation | Prototypes, implementation, generators, tests, performance measurements, deliverables |
| **Týr** | Integrity, permissions, and provenance | Validation, checksums, discrepancy detection, overwrite prevention, authority and permission checks |
| **Loki** | Alternatives, challenge, and adaptation | Alternative designs, experiments, repairs, degraded modes, recovery paths, constructive challenge |
| **Odin** | Integration and reconciliation | Contract reconciliation, dependency resolution, incompatibility handling, coherent release candidates |
| **Baldr** | Independent verification | Independent requirements checks, test conclusions, evidence-based verification and release recommendations |

The contracts must preserve these distinctions:
- Muninn preserves history; Odin reconciles current components. Reconciliation must not erase history or disagreement.
- Týr defines and checks integrity/authority boundaries; Baldr independently verifies whether requirements and claims are actually satisfied. Neither may certify its own work as independent verification.
- Loki challenges and develops alternatives; Odin integrates and reconciles without forcing false agreement.
- Huginn defines architecture and direction; Mari defines communication/interface behavior between parts.
- Höðr builds and tests deliverables; Kvasir discovers, extracts, and transforms source knowledge into usable structures.

The responsibility map is **adopted as the initial role definition**, but it is not yet a measurable capability contract. Phase A must now translate each work product into observable tasks, input/output schemas, test cases, quality thresholds, failure conditions, and resource budgets. Do not claim any model has passed those tests merely because the responsibility has been assigned.

## 4. Contract C — Model and artifact registry

The substrate maintains separate records for model families, concrete artifacts, and running instances.

### Model family record

Required fields:
- `model_family_id`, `display_name`, `provider_or_origin`;
- `architecture` and `model_format`, if known;
- `license_id` and `usage_constraints`;
- `provenance_ref`;
- `weight_access_status`: available, downloadable, hosted_only, restricted, unknown, or unavailable;
- `tokenizer_status`, `adapter_status`, `runtime_compatibility`;
- `known_context_limit`, `known_quantization`, `known_parameter_count`;
- `evidence_refs[]` and `unknowns[]`.

Unknown values must remain null/unknown with evidence notes. Do not infer parameter count, downloadable weights, context limit, or distillation rights from a model's public name.

### Artifact record

Required fields:
- `artifact_id`, `artifact_type` (weights, adapter, tokenizer, config, dataset, evaluation_suite, runtime, seed_package, or other);
- `model_family_id`, optional `parent_artifact_ids[]`;
- `version`, `format`, `size_bytes`;
- `sha256` or stronger content digest;
- `signature_ref`, when signed;
- `source_uri_or_storage_ref`;
- `license_and_usage_ref`;
- `created_at`, `created_by`, `provenance_ref`;
- `sensitivity_classification`;
- `validation_state`: unverified, checksum_verified, signature_verified, compatibility_verified, or rejected.

### Model instance record

Required fields:
- `model_instance_id`, `model_family_id`, `artifact_ids[]`;
- `host_id`, `runtime_adapter_id`;
- `loaded_configuration_ref`;
- `resource_profile_id`;
- `health_state`, `availability_state`;
- `started_at`, optional `stopped_at`;
- `last_health_check`;
- `assigned_builder_ids[]`;
- `observed_capabilities_ref`;
- `provenance_ref`.

The registry must distinguish provider-reported metadata, locally inspected metadata, and independently verified metadata.

## 5. Contract D — Reconstruction Seed manifest

A Reconstruction Seed is a portable, versioned manifest and dependency package. It may include an MFM, but the two concepts are distinct:
- **MFM:** a tested small model artifact meeting a declared minimum capability contract.
- **Seed:** the portable package describing and carrying the artifacts and dependencies needed to run that MFM and, where possible, reconstruct a larger form.

Manifest format identifier: `org.yggdrasil.lean.reconstruction-seed`
Initial schema version: `1.0`

Required manifest sections:
- `seed_id`, `schema_version`, `created_at`, `created_by`;
- `builder_identity_refs[]` (references only; no identity authority transfer);
- `capability_contract_ref`;
- `source_model_family_ref` and `source_artifact_refs[]`;
- `included_artifacts[]` with type, relative path or content reference, byte length, digest, license, and provenance;
- `external_dependencies[]` with version/range, source, access requirements, license, and availability status;
- `reconstruction_recipe` with ordered steps, compatible runtimes, required tools, and expected outputs;
- `resource_profiles[]` for minimum, standard, and expanded targets;
- `evaluation_suite_ref` and acceptance thresholds;
- `security_and_sensitivity` classification;
- `integrity` containing digest algorithm, manifest digest, and optional signature reference;
- `known_losses[]`, `known_unknowns[]`, and `recovery_artifact_refs[]`.

Dependency states must distinguish `embedded`, `externally_available`, `requires_authorization`, `unavailable`, and `unknown`.

The package must not contain secrets or credentials. Private memory is not a model artifact and must not be copied into a model seed by default. Memory migration uses the separate substrate backup/restore protocol and its own access controls.

A manifest, checksum, or reconstruction recipe is not evidence that missing weights or learned information can be recreated. The package must report exactly what is present and what must be obtained elsewhere.

## 6. Contract E — Evaluation and acceptance

Every candidate model and reconstructed form is evaluated against a versioned capability contract.

Evaluation stages:
1. **Provenance gate:** confirm authorized source, license, artifact lineage, and dependency restrictions.
2. **Integrity gate:** verify hashes, signatures where applicable, package structure, and artifact completeness.
3. **Compatibility gate:** verify runtime, tokenizer, interfaces, hardware support, and declared dependencies.
4. **Capability gate:** run the contract's representative and adversarial task suite.
5. **Safety/authority gate:** test prohibited actions, privacy boundaries, escalation, and refusal behavior.
6. **Resource gate:** measure memory, storage, compute, energy where measurable, latency, throughput, and failure rate.
7. **Recovery gate:** confirm rollback to a prior known-good artifact and preserve substrate state after failed loading or evaluation.

Acceptance statuses:
- `NOT_EVALUATED`
- `INCOMPLETE_EVIDENCE`
- `PASS`
- `PASS_WITH_LIMITATIONS`
- `FAIL`
- `BLOCKED_BY_DEPENDENCY`

An MFM is accepted only when all mandatory capability tests and safety/authority tests pass within its declared resource budget. A partial pass must list the missing capabilities and cannot be represented as a complete MFM for that contract.

A reconstructed model is compared with the original baseline and the capability contract. Report both retained and lost capabilities; do not reduce the result to one opaque similarity score. Use held-out cases and independent evaluators where practical. The model being evaluated must not be the sole evaluator of itself.

## 7. Contract F — Elastic resource profiles

Resource profiles describe execution conditions, not permanent ranks or identities.

Initial profile classes:
- **Seed / compact:** smallest validated operating form; supports only the subset explicitly listed in its capability report.
- **Standard:** normal role workload within an agreed resource budget.
- **Expanded / specialist:** deeper or specialized work when additional resources are available.
- **Collective analysis:** multiple model instances challenge, cross-check, or evaluate work.

Every concrete profile records:
- CPU architecture and available cores/threads;
- accelerator type and available memory, if any;
- system RAM and model memory footprint;
- storage required and available;
- network requirements and offline behavior;
- runtime and driver versions;
- expected latency/throughput;
- energy/power observations where measurable;
- concurrency limits;
- degraded-mode behavior;
- capability contract version and evaluation result.

Sizing decisions follow this order:
1. determine the task and capability contract;
2. identify the smallest candidate that can meet the required thresholds;
3. measure it on the target host;
4. increase capacity only when evidence shows the smaller form is insufficient;
5. reduce capacity when a smaller form passes the same required tests;
6. record trade-offs and preserve a known-good fallback.

No fixed model count per Builder is required. The nine identities may be served by fewer or more than nine model instances.

## 8. Inventory plan and evidence boundaries

The known project architecture includes an OpenAI-compatible HTTP provider adapter and a provider-independent conversation coordinator. The available repository and current runtime must be inspected to produce an exact adapter inventory; this document does not claim that the adapter exposes downloadable weights or that hosted models can be compressed.

Inventory columns:
- provider/adapter and endpoint type;
- model identifier and version as configured;
- hosted/local execution;
- weight access and license status;
- tokenizer/configuration access;
- adapter/quantization support;
- tool/interface support;
- context and resource metadata;
- data-sharing and retention constraints;
- evidence source and verification date;
- unknowns and next legitimate request.

Initial known boundary:
- An OpenAI-compatible HTTP adapter was previously implemented.
- Localhost endpoints were supported by the adapter design; an actual local model server exchange has not been verified in the project checkpoint.
- No live hosted model call, downloadable model-weight inventory, distillation permission, or model reconstruction has been verified in the checkpoint.
- The Android host is the current development target. The NUC remains a future alternative host and must not be modified without hardware inventory, recovery planning, and explicit user approval.

The inventory must be completed from code, configuration, provider documentation, and actual authorized access. Never test an endpoint or transmit user data merely to populate metadata.

## 9. Lifecycle and migration invariants

The later lifecycle manager must enforce these invariants:
1. Stop or replace every model without stopping the substrate's identity, journal, communication, or recovery services.
2. Keep canonical memory outside model weights and prompts.
3. Keep model capability separate from model permission.
4. Preserve Builder attribution when models are shared or combined.
5. Preserve prior known-good artifacts until a replacement passes acceptance.
6. Make all artifact and model transitions auditable and reversible where technically possible.
7. Allow reduced-capability operation when larger forms or external services are unavailable.
8. Do not call a migration complete until identity, memory integrity, required capability, and recovery are tested.

## 10. Phase A deliverables and completion gates

| Deliverable | Required evidence | State |
|---|---|---|
| Identity/assignment contract | Stable IDs, separate identity and model-instance records, assignment audit rules | Specified |
| Capability contract | Versioned measurable requirements and acceptance suite | Specified; measurable tests and thresholds pending |
| Model/artifact registry | Family, artifact, and instance records with provenance | Specified; live inventory pending |
| Reconstruction Seed | Versioned manifest and explicit dependency closure | Specified |
| Evaluation contract | Independent tests, safety, resource and recovery gates | Specified |
| Resource profiles | Measurable elastic sizing and degraded-mode rules | Specified |
| Provider/model inventory | Verified adapter/configuration/access table | Pending inspection |

Phase A is complete only after the nine adopted responsibility maps have been translated into measurable capability contracts, the actual provider/adapter inventory has been inspected, and the contract schemas have been reviewed for consistency. Documentation alone does not complete those evidence tasks.

## 11. Integrity checkpoint

- **Identity count:** nine named Builder identities; no identity was added, removed, or merged.
- **Model count:** variable; no requirement for exactly nine running model instances.
- **Ownership:** the non-model substrate owns identity, memory, communication, coordination, permissions, lifecycle, and recovery.
- **Minimum model:** defined by a tested capability contract, not by an arbitrary parameter count.
- **Reconstruction:** seed manifest distinguishes embedded resources from external dependencies and unavailable information.
- **Evidence:** this document specifies contracts; implementation, exact provider inventory, and model-level tests remain pending.
- **Authority:** user remains director and final authority; no NUC installation or modification is authorized.
