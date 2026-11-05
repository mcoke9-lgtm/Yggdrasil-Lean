# Nine Builders Analysis Engine 0.3

A dependency-free JavaScript prototype. The calculation core is separate from the
command-line interface so it can later be called by a browser UI or Android wrapper.

## Requirements and execution

- Node.js 20 or newer
- No third-party packages
- No network connection required for local execution

```sh
npm test
node src/cli.js samples/materials.csv reports/materials-report.json
node src/cli.js samples/materials_with_evidence.csv reports/evidence-report.json
```

The CLI refuses to overwrite an existing report or manifest. Each report records
accepted/rejected records, data-quality findings, calculations, evidence metadata,
source-record references, a second-pass arithmetic check and source SHA-256.
A separate manifest hashes the exact report and source text.

## Analysis capabilities

- Overall inspected and reusable mass.
- Mean, median, minimum, maximum and sample standard deviation.
- Per-material totals, record counts, recovery rates and share of reusable mass.
- Material comparison ranked by inspected mass.
- Original source record numbers retained in material comparisons.
- Evidence labels: `directly_measured`, `reported_by_source`, `estimated`,
  `modeled`, and `unknown`.
- Optional source references retained as supplied metadata.
- Optional per-record hard bounds using `quantity_min_kg`, `quantity_max_kg`,
  `reusable_min_kg`, and `reusable_max_kg`.
- Conservative aggregate intervals are reported only when every accepted record
  supplies all four bounds.

## Interpreting uncertainty

The interval output is a conservative arithmetic range based on supplied hard
minimum and maximum values. It is **not** a probability interval, confidence
interval, or claim about the likelihood of outcomes. Ratio bounds use conservative
interval arithmetic and do not model dependence between numerator and denominator.
If any accepted record lacks complete bounds, the engine withholds the aggregate
interval rather than silently treating missing uncertainty as zero.

Evidence labels and source references are user-supplied metadata. The engine
preserves them but does not authenticate a source or prove a measurement.

## Acceptance dataset

- Inspected: 230 kg
- Reusable: 162 kg
- Reusable fraction: approximately 70.4348%

## Boundaries

This is a prototype, not a general statistical package or an autonomous agent.
Statistics describe supplied records; they do not establish population
representativeness. Sample standard deviation is not a confidence interval.
Hashes establish byte identity, not source authenticity. The second calculation
pass checks arithmetic consistency, not measurement truth. Invalid records are
excluded and disclosed. No farm, personal, or operational data are included.
