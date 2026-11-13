# Portable Analysis Contract 001

**Version:** 0.1.0  
**Status:** Draft proposal; not adopted and not implemented as a standalone protocol  
**Project:** The Living Tree: Adaptive Filesystem  
**Initial operation:** `materials.recovery.summary`  
**Compatibility target:** Nine Builders Analysis Engine 0.4.0

## 1. Purpose and boundaries

This contract defines a portable request and result boundary for running the same analysis against the same source dataset in more than one execution environment.

The contract is intended to separate:
- the identity and meaning of an analysis request;
- the exact input bytes and their declared interpretation;
- the analysis operation and engine implementation;
- the required result fields and verification rules; and
- the execution receipt and evidence of what happened.

The contract does not require GitHub, ChatGPT, Node.js, a particular operating system, a cloud provider, or a particular storage backend.

This document is a specification draft. It does not establish that a contract parser, validator, transport, independent runner, or cross-environment comparison tool exists.

## 2. Normative language

The words **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** express requirements for implementations claiming conformance to this contract.

A conforming implementation must identify the contract version it supports. Unknown major versions must not be silently interpreted as a known version.

## 3. Request envelope

A request contains these logical sections:

| Section | Required fields | Purpose |
|---|---|---|
| Contract | `name`, `version` | Select the request contract |
| Request | `request_id`, `operation`, `operation_version` | Identify the requested work |
| Input | `format`, `encoding`, `filename`, `sha256`, `units` | Identify and interpret exact source bytes |
| Engine requirement | `name`, `minimum_version` or exact version policy | Declare implementation compatibility |
| Expected output | `schema`, `schema_version`, `verification_required` | Define the requested result |

Illustrative request:

```json
{
  "contract": {
    "name": "Portable Analysis Contract",
    "version": "0.1.0"
  },
  "request": {
    "request_id": "example-request-001",
    "operation": "materials.recovery.summary",
    "operation_version": "1.0"
  },
  "input": {
    "format": "csv",
    "encoding": "UTF-8",
    "filename": "materials.csv",
    "sha256": "<sha256-of-exact-input-bytes>",
    "units": {
      "quantity": "kg",
      "reusable": "kg"
    }
  },
  "engine_requirement": {
    "name": "Nine Builders Analysis Engine",
    "minimum_version": "0.4.0"
  },
  "expected_output": {
    "schema": "materials.recovery.report",
    "schema_version": "1.0",
    "verification_required": true
  }
}
```

The source bytes MUST be supplied to the execution environment through an agreed transport. A filename, path, or URL alone does not identify the input contents.

The SHA-256 value MUST be computed over the exact input bytes received, before text decoding or newline conversion. A runner MUST reject a mismatch rather than silently analyze different bytes.

The request ID identifies one logical request. Retry and idempotency semantics are not defined in this initial contract version.

## 4. Initial input profile

The initial operation accepts CSV or JSON records using the existing Analysis Engine field vocabulary.

| Field | Requirement | Meaning |
|---|---|---|
| `material` | Required | Nonblank material label |
| `quantity_kg` | Required | Non-negative inspected quantity |
| `reusable_kg` | Required | Non-negative reusable quantity, not greater than quantity |
| `evidence_type` | Optional | Evidence classification |
| `source_reference` | Optional | Supplied source reference |
| `quantity_min_kg` | Optional pair | Lower inspected-quantity bound |
| `quantity_max_kg` | Optional pair | Upper inspected-quantity bound |
| `reusable_min_kg` | Optional pair | Lower reusable-quantity bound |
| `reusable_max_kg` | Optional pair | Upper reusable-quantity bound |

The initial evidence vocabulary is:
- `directly_measured`
- `reported_by_source`
- `estimated`
- `modeled`
- `unknown`

If either member of a bound pair is supplied, both members are required. Bounds must be finite, non-negative, ordered, and contain the observed value. Invalid records and their reasons MUST be reported. Implementations MUST NOT silently substitute zero for missing values or bounds.

The input profile preserves the current prototype's units of kilograms. Unit conversion is outside this initial operation and must not be inferred.

## 5. Operation and engine identity

The initial operation identifier is `materials.recovery.summary`, operation version `1.0`.

The current implementation identity is:
- Engine name: `Nine Builders Analysis Engine`
- Package version: `0.4.0`
- Runtime baseline: Node.js 20 or newer for the current JavaScript implementation.

An execution receipt MUST distinguish the engine's declared package version from the runtime and environment versions. Implementations in other languages may conform to the operation contract without claiming to be the same engine implementation.

A runner MUST report the actual engine version it executed. It MUST NOT report only the requested minimum version as though that were the actual version.

## 6. Required result profile

The initial result schema is `materials.recovery.report`, schema version `1.0`. It requires the following logical sections:

| Section | Required information |
|---|---|
| `input_summary` / `quality` | Input, accepted and rejected record counts; validation findings |
| `evidence` | Evidence classification counts and supplied source references |
| `results` | Inspected and reusable totals, reusable fraction and percent, descriptive statistics |
| `comparisons` | Per-material totals, record counts, recovery rates, reusable shares, source-record lineage |
| `uncertainty` | Bounds completeness, aggregate bounds when supported, interpretation and assumptions |
| `verification` | Arithmetic or other checks actually performed and their outcomes |
| `limitations` | Material limitations of the analysis |

A runner MAY include additional fields. It MUST preserve the meaning of required fields and MUST NOT represent unavailable or unsupported values as verified values.

The current engine's evidence classifications and source references are supplied metadata. Their presence does not authenticate a source or establish that a measurement is true.

Uncertainty bounds are conservative arithmetic bounds from supplied hard limits. They are not probability intervals or confidence intervals. If any accepted record lacks complete bounds, the aggregate interval must be reported as incomplete rather than treating missing bounds as zero.

## 7. Verification requirements

A conforming execution MUST perform and report, at minimum:

1. Input-byte SHA-256 verification against the request.
2. Input format and record validation.
3. Required-field and domain validation for the selected operation.
4. Output-schema validation against the requested schema version.
5. The operation's specified arithmetic consistency checks.

A verification result must distinguish:
- `passed`: all required supported checks passed;
- `failed`: one or more required checks failed;
- `incomplete`: a required check could not be completed;
- `unsupported`: the implementation does not support a required check or schema.

A runner MUST NOT report `passed` when a required check was skipped, unsupported, or unavailable.

## 8. Cross-environment comparison

Two executions are comparable only when they use:
- the same contract major and minor version, or an explicitly reviewed compatible version pair;
- the same operation and operation version;
- identical input bytes, confirmed by matching input SHA-256;
- equivalent declared units and input interpretation; and
- compatible output schema versions.

Comparison has two layers.

### 8.1 Exact identity fields

The following fields MUST match:
- input SHA-256;
- operation identifier and version;
- input record count;
- accepted and rejected record counts;
- categorical classifications and validation outcomes;
- material labels and source-record lineage;
- uncertainty completeness status; and
- verification status and check coverage.

### 8.2 Numerical result fields

Numerical fields MUST be compared using a documented tolerance policy. The initial proposed policy is:

`abs(a - b) <= max(1e-10, 1e-10 * max(abs(a), abs(b)))`

This is a provisional comparison tolerance, not a claim that every numeric algorithm is equivalent at that precision. The comparison report must identify any field outside tolerance.

Whole-report byte hashes are not a conformance requirement in version 0.1.0. JSON whitespace, key ordering, and number serialization can differ. A future contract version may define canonical output serialization and a digest scope after those rules are reviewed.

Execution IDs, timestamps, hostnames, runtime details, and other execution-specific receipt metadata are not expected to match between environments.

## 9. Execution receipt

Each runner must produce a receipt with at least:

| Field | Meaning |
|---|---|
| `request_id` | Logical request identifier |
| `execution_id` | Unique identifier for this execution attempt |
| `contract_version` | Contract version used |
| `input_sha256` | Hash of exact input bytes received |
| `operation` | Operation identifier and version |
| `engine` | Actual engine name and version |
| `environment` | Runtime and version; platform details when available |
| `started_at`, `completed_at` | Execution timestamps with timezone |
| `outcome` | Completed, rejected, failed, or incomplete |
| `verification` | Status and checks actually performed |
| `output_reference` | Location or identifier of the output, if retained |
| `limitations` | Known execution or verification limitations |

A receipt is an execution record supplied by the runner. Unless separately authenticated, it is not cryptographic proof of authorship, trusted origin, or execution integrity.

## 10. Error and non-success outcomes

Implementations should distinguish at least:
- invalid request;
- unsupported contract or output schema version;
- input hash mismatch;
- unsupported input format or operation;
- invalid input records;
- execution failure;
- incomplete verification; and
- output unavailable.

An implementation must not collapse these states into a generic successful empty report.

## 11. Conformance and acceptance gates

The following gates are proposed for a first implementation:

1. A request validator accepts a valid version 0.1.0 request and rejects malformed or unsupported requests.
2. Input-byte hashing is verified before analysis.
3. The initial CSV and JSON input profile is tested.
4. Required output fields and types are validated.
5. Invalid records and uncertainty incompleteness are disclosed.
6. Execution receipts report actual engine and runtime versions.
7. Two separate execution environments process the same synthetic dataset.
8. A comparison tool confirms exact identity fields and tolerance-based numerical equivalence, or produces a clear discrepancy report.
9. Failure, unsupported, and incomplete states are tested rather than represented as success.

These are acceptance criteria, not completed test results.

## 12. Explicitly unresolved

- Request ID generation, retry, and idempotency semantics.
- Exact JSON Schema or equivalent machine-readable schema publication.
- Canonical output serialization and output digest rules.
- Authentication and trust model for execution receipts.
- Output transport and retention.
- Unit conversion and additional analysis operations.
- Cross-language numeric behavior and tolerance refinement.
- Whether a future contract should distinguish engine implementation conformance from operation-level conformance more formally.

## 13. Current implementation mapping

The existing Analysis Engine 0.4.0 provides CSV/JSON parsing, material recovery calculations, evidence metadata preservation, conservative bounds, source-record references, a second-pass arithmetic check, and source/report manifest hashing.

A first dependency-free request validator is implemented for the version 0.1.0 envelope. It checks the supported contract and operation identifiers, required fields, initial CSV/JSON format declaration, UTF-8 declaration, kilogram units, engine minimum-version syntax, expected output identifier/version, and verification flag. It also verifies the SHA-256 digest against the exact supplied input bytes. A separate report validator checks the declared materials recovery report structure, required fields, basic value domains, record-count reconciliation, evidence-count reconciliation, and supported schema identity. A separate execution-receipt validator checks the required identity, input fingerprint, operation and engine versions, environment, timezone-qualified timestamps, outcome, verification checks, output reference, and limitations. It prevents a completed outcome from claiming success when verification is incomplete or failed. A local contract runner now connects request validation, exact input-byte verification, UTF-8 decoding, analysis execution, output-report validation, and execution-receipt generation. It returns the report, receipt, verification state, and errors as one in-memory result. It does not persist outputs or transmit data. Receipts are not authenticated or signed. These are initial implementations, not a complete implementation of every normative requirement in this document. Cross-environment comparison is not yet implemented.

The Independent Scheduler is not part of the analysis result contract. It may later schedule or initiate a request through a separately authorized integration, but scheduling does not establish analysis correctness or execution independence.

---

**Change discipline:** This draft is versioned independently from the engine package. Changes to the meaning of required fields or comparison rules require a contract version change and compatibility review. No backend, network protocol, or autonomous execution authority is implied by this document.
