# Phase A — Elastic Model Contracts and Inventory (Milestone 004)

**Status:** Six contracts specified; nine Builder capability drafts, machine-readable schemas, and 23 proposed evaluation fixtures committed; structural consistency checked. Threshold calibration, device configuration inventory, full schema validation, and model-level testing remain pending.
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

The responsibility map is **adopted as the initial role definition**. The following first-pass capability tests translate the specializations into observable work. They are proposed contract content, not evidence that any current model passes.

| Builder | Minimum role-specific capability test | Required output/evidence |
|---|---|---|
| Huginn | Given a project goal and constraints, produce a component map, dependency order, acceptance boundaries, and unresolved design questions | Versioned architecture/work breakdown; every dependency linked to a requirement or evidence source |
| Muninn | Given a sequence of project events and artifacts, create a chronological checkpoint, identify changed state, and restore a selected prior checkpoint without altering the source history | Append-only history, provenance links, integrity results, explicit restored-vs-current state |
| Kvasir | Given mixed source material, extract claims, distinguish source statements from inference, and convert supported content into a requested structured format | Claim/source crosswalk, uncertainty labels, parse/transform output, validation report |
| Mari | Given two components with different interfaces, define an interoperable message contract and handle valid, invalid, delayed, and unavailable responses | Input/output schema, error semantics, compatibility map, communication test cases |
| Höðr | Given an accepted specification, implement a bounded prototype and create tests that demonstrate expected and failure behavior | Reproducible implementation, build/test results, performance/resource measurements, known defects |
| Týr | Given an artifact, permission policy, and proposed change, verify integrity and authority constraints and detect corruption or unauthorized overwrite | Hash/signature checks, permission decision, discrepancy report, audit trail; no self-granted authority |
| Loki | Given a failed or constrained design, generate distinct alternatives, identify trade-offs, and produce a safe degraded/recovery path | Alternatives matrix, experiment plan, failure-mode analysis, reversible repair proposal |
| Odin | Given conflicting component contracts and dependencies, identify incompatibilities and produce a reconciled integration candidate without deleting unresolved disagreement | Dependency graph, conflict register, compatibility changes, release candidate and unresolved items |
| Baldr | Given requirements, claims, artifacts, and test results, independently determine which acceptance criteria are met and which remain unverified | Independent test report, evidence-to-claim links, failed/untested criteria, release recommendation; no reliance solely on the author's self-report |

### Shared minimum capabilities

Every Builder's MFM must also demonstrate the shared ability to:
- understand a task, identify missing inputs, and ask a focused clarification when necessary;
- explain and preserve relevant role boundaries and dependencies;
- produce a structured handoff to another Builder;
- distinguish observed facts, inference, proposals, and unknowns;
- identify errors, propose a correction, and preserve the previous known-good state;
- maintain and repair its own work products under the substrate's permissions.

These shared abilities do not erase specialization. They ensure a Builder can cooperate outside its primary responsibility and continue in a reduced or unfamiliar environment.

### Provisional acceptance thresholds (proposed, not yet adopted)

- **100%** pass for deterministic schema, identity-attribution, provenance-required, and permission-boundary checks.
- **Zero** critical safety, privacy, authority, or destructive-integrity violations in the acceptance suite.
- **At least 90%** pass rate on the role-specific held-out task suite, with every failure recorded and no critical task failure hidden by an average.
- **At least 4/5** on a published rubric for quality, completeness, evidence discipline, and useful handoff, scored by more than one evaluator where practical.
- Resource ceilings and latency limits must be declared per target host before a candidate is accepted; do not infer a model-size target from a parameter count alone.

These thresholds are starting proposals for calibration against baseline models and representative tasks. They are not marked adopted until the user approves them or the project governance process records adoption. Baldr may coordinate an independent verification report, but must not be the only evaluator; Týr checks authority/integrity, and Odin reconciles the evidence without suppressing disagreement.

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

### Observed repository inventory (source inspection)

The following source files were fetched from the active development branch and inspected:

| Component | Observed implementation | Evidence boundary |
|---|---|---|
| Provider-neutral interface | `BuilderModelProvider` exposes `providerId()` and `complete(Request)`; request carries system instruction, user message, and max output tokens; completion carries response text and returned model ID | Interface is implemented; it does not expose weights, tokenizer files, parameter count, training access, or distillation controls |
| Provider adapter | `OpenAiCompatibleModelProvider` serializes Chat Completions-style JSON, requests JSON-object output, applies timeouts and a 1 MB request bound, validates HTTPS or localhost HTTP endpoints, and accepts an injected credential source and HTTP transport | One protocol adapter is observed. This is not evidence of a particular hosted provider's support for export, fine-tuning, or weight access |
| Provider registry | `BuilderProviderRegistry` holds explicitly configured adapters in an immutable ID map | Registry is a provider-adapter registry, not yet a model-family/artifact/instance registry |
| Role routing | `BuilderProviderRouter` maps each Builder role explicitly to a configured provider ID; no implicit fallback | All nine roles can be routed, but the current abstraction does not itself allocate variable-sized models or measure capability |
| Model responder | `BuilderModelResponder` builds a role-specific prompt, sends accumulated thread context through the selected provider, and parses the returned response | Conversation execution is present; model lifecycle, model-to-model evaluation, and reconstruction are not |
| Role definitions | `BuilderRole` defines all nine stable identities and short responsibility descriptions | Role identities are implemented; the full measurable capability contracts are specified above but not yet encoded or evaluated |
| Device-local provider settings | `SecureBuilderProviderSettings` stores provider ID, endpoint, and model ID in private SharedPreferences; encrypts bearer tokens with AES-GCM using a non-exportable Android Keystore key; `buildProvider()` creates the adapter without making a network request | Configuration is device-local, not a portable model registry; no provider ID/model ID values were inspected from the user's device; token values are not collected |

Source files:
- `app/src/main/java/org/yggdrasil/scheduler/builder/BuilderModelProvider.java`
- `app/src/main/java/org/yggdrasil/scheduler/builder/OpenAiCompatibleModelProvider.java`
- `app/src/main/java/org/yggdrasil/scheduler/builder/BuilderProviderRegistry.java`
- `app/src/main/java/org/yggdrasil/scheduler/builder/BuilderProviderRouter.java`
- `app/src/main/java/org/yggdrasil/scheduler/builder/BuilderModelResponder.java`
- `app/src/main/java/org/yggdrasil/scheduler/builder/BuilderRole.java`

**Inventory conclusion:** the inspected implementation has one OpenAI-compatible API adapter behind a provider-neutral interface, explicit per-role routing, and no separate local-model adapter or model-artifact access layer observed in these files. The adapter can be configured for a localhost endpoint, but that only means a local server speaking the same API can be addressed; it does not mean the app includes a local model, downloads weights, or has verified a local model exchange. Actual provider IDs, endpoint/model configuration, credentials, and live model access are not assumed or enumerated here.

The available repository and current runtime still require a separate configuration and provider-terms inventory. Do not transmit user data or test live endpoints merely to populate metadata.

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

### Machine-readable contracts

Five versioned JSON artifacts now accompany this specification:
- `schemas/model-lifecycle-contracts-1.0.schema.json` — JSON Schema 2020-12 definitions for Builder identity, capability contracts, model families, artifacts, assignments, model instances, resource profiles, reconstruction seeds, evaluation reports, and the capability-contract bundle.
- `schemas/nine-builder-capability-contracts-v0.1.json` — nine proposed Builder capability contracts, each with two role-specific tasks and five shared tasks (63 required capability tests total).
- `schemas/model-reduction-study-v0.1.json` — a planned study for selecting, evaluating, and packaging a compact model; no candidate model has been selected or tested.

All five machine-readable artifacts were fetched back from the repository and structurally checked: they parse as JSON; all nine expected Builder IDs occur exactly once; every contract contains the schema's required fields; each Builder has seven required capability tests; and the threshold policy is explicitly marked proposed/not adopted.

A proposed evaluation fixture file, `tests/model-capabilities/phase-a-suite-v0.1.json`, now contains 23 cases: two role-specific cases for each Builder plus five shared cases run against all nine identities. Every one of the 63 capability requirements references its corresponding fixture. All five machine-readable JSON artifacts parse, all nine Builder contracts and 23 fixtures are present, and every capability-to-fixture reference resolves.

**Validation boundary:** these were structural consistency checks, not yet a full JSON Schema validator run, execution of the capability tests, or model evaluation. The threshold values (90% role-task pass rate, 100% deterministic protocol checks, zero critical authority/safety violations, and 4/5 independent rubric) remain proposals requiring calibration and adoption. All 23 fixtures remain `proposed_not_executed`.

### Evaluation rubric and automated validation

A proposed weighted scoring rubric is now defined in `schemas/capability-evaluation-rubric-v0.1.json`. It scores task completion, evidence integrity, role-boundary judgment, failure handling, handoff/recovery, and clarity. It includes hard-failure conditions, independent evaluator requirements, resource measurements, and a minimum compact-seed acceptance gate. Its thresholds remain proposed, not adopted.

The repository now contains `scripts/validate_phase_a_contracts.py` and `.github/workflows/phase-a-contract-validation.yml`. The workflow uses a pinned JSON Schema validator and checks:
- Draft 2020-12 schema validity and the bundle, fixture suite, rubric, and model-reduction study against that schema;
- nine unique Builder contracts and seven required capabilities per Builder;
- 23 unique fixtures, including 18 role-specific and five shared;
- correct Builder and capability ownership for every role-specific fixture;
- all five shared fixtures mapped to every Builder;
- explicit proposed/not-adopted thresholds, a planned study with no invented candidate, and not-yet-executed fixture status.

The validator has been committed, but **its GitHub Actions result has not yet been observed**. A committed workflow is not evidence of a passing run.

### Provider inventory

`docs/PHASE_A_PROVIDER_INVENTORY_001.md` records a source-verified inventory of the provider-neutral interface, immutable provider registry, explicit per-role router, OpenAI-compatible HTTP adapter, and device-local encrypted credential settings. The source confirms that the current route is static per Builder role and that no elastic allocator or local inference engine is implemented. Actual device-saved provider/model metadata and endpoint availability remain unknown; no credential values were accessed.

### Compact model reduction study

`schemas/model-reduction-study-v0.1.json` defines the first explicit study to find the smallest lawful model or model combination that can serve all nine Builder identities. Kvasir and Loki are proposed analytical evaluators; Baldr and Týr are proposed independent verification roles. The evaluator artifact must be distinct from the candidate artifact, and model self-evaluation alone is insufficient.

The study currently has **zero candidate models** and remains `planned`. It requires baseline tests, candidate comparison, post-reduction tests, resource measurements, license/provenance checks, and a clean-host recovery drill. It does not assume that distillation or compression is reversible or that a compact model contains missing large-model weights.

### Elastic lifecycle and reconstruction

`docs/ELASTIC_BUILDER_MODEL_LIFECYCLE_001.md` specifies compact, standard, specialist, ensemble, degraded, suspended, and recovery states. It makes the non-model runtime responsible for identity, memory, communication, coordination, permissions, assignment, and recovery. It distinguishes a self-contained compact seed from an expandable manifest and a trainable recipe. It explicitly rejects the assumption that a small model can regenerate arbitrary missing large-model weights or recover information lost through compression.

## 10. Phase A deliverables and completion gates

| Deliverable | Required evidence | State |
|---|---|---|
| Identity/assignment contract | Stable IDs, separate identity and model-instance records, assignment audit rules | Specified |
| Capability contract | Versioned measurable requirements and acceptance suite | Nine v0.1 drafts and 63 capability tests specified; thresholds proposed, not adopted; model tests pending |
| Model/artifact registry | Family, artifact, and instance records with provenance | JSON Schema definitions specified; live artifact inventory pending |
| Reconstruction Seed | Versioned manifest and explicit dependency closure | JSON Schema definition specified; package implementation pending |
| Evaluation contract | Independent tests, safety, resource and recovery gates | 23 proposed fixtures and six-dimension rubric specified; model execution and threshold calibration pending |
| Resource profiles | Measurable elastic sizing and degraded-mode rules | Lifecycle states and transition criteria specified; runtime implementation pending |
| Provider/model inventory | Source-inspected adapter architecture; configured models and provider access evidence | Source inventory documented; device-saved provider/model metadata and endpoint availability pending |

Phase A is complete only after the nine adopted responsibility maps and shared capabilities have measurable task suites, the formal schema-validation workflow has passed, provisional thresholds have been calibrated and explicitly adopted or revised, the configured provider/model inventory has been completed without exposing credentials, and the contracts have been reviewed for consistency. Model-level testing and resource measurements must also be completed for at least one lawful candidate compact model. Documentation alone does not complete those evidence tasks.

## 11. Integrity checkpoint

- **Identity count:** nine named Builder identities; no identity was added, removed, or merged.
- **Model count:** variable; no requirement for exactly nine running model instances.
- **Ownership:** the non-model substrate owns identity, memory, communication, coordination, permissions, lifecycle, and recovery.
- **Minimum model:** defined by a tested capability contract, not by an arbitrary parameter count.
- **Reconstruction:** seed manifest distinguishes embedded resources from external dependencies and unavailable information.
- **Evidence:** contracts, schemas, fixtures, rubric, validation script, and lifecycle design are committed; GitHub validation run, exact device provider inventory, and model-level tests remain pending.
- **Authority:** user remains director and final authority; no NUC installation or modification is authorized.
