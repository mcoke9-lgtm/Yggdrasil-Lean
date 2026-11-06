# Nine Builders Analysis Engine 0.4

A dependency-free JavaScript prototype. The calculation core is separate from its
command-line and local browser interfaces.

## Requirements and execution

- Node.js 20 or newer
- No third-party packages
- No external network connection required for local analysis

```sh
npm test
npm start
# Then open http://127.0.0.1:4173 on the same device.
```

The CLI is also available:

```sh
node src/cli.js samples/materials.csv reports/materials-report.json
node src/cli.js samples/materials_with_evidence.csv reports/evidence-report.json
```

## Local browser interface

Choose a CSV or JSON file, analyze it, inspect the summary, evidence counts,
material comparison, uncertainty status, quality findings and complete report,
then optionally save a JSON copy. The browser interface does not write uploaded
source files or reports to disk.

The server binds to the loopback interface only (127.0.0.1), accepts request bodies
up to 5 MB, serves only three fixed page assets and one analysis endpoint, checks
the local Host header, and does not enable cross-origin access. Analysis requests
remain on the same device. Stop the server with Ctrl+C. This is a local development
prototype, not an internet-facing service.

The CLI refuses to overwrite an existing report or manifest. Each CLI report
records accepted/rejected records, data-quality findings, calculations, evidence
metadata, source-record references, a second-pass arithmetic check and source
SHA-256. A separate manifest hashes the exact report and source text.

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
