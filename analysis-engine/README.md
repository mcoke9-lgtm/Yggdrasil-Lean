# Nine Builders Analysis Engine 0.2

A dependency-free JavaScript prototype. The calculation core is separate from the
command-line interface so it can later be called by a browser UI or Android wrapper.

## Requirements

- Node.js 20 or newer
- No third-party packages
- No network connection required for local execution

## Run

From this directory:

```sh
npm test
node src/cli.js samples/materials.csv reports/materials-report.json
```

The CLI refuses to overwrite an existing report or manifest. The report includes
accepted/rejected records, data-quality findings, totals, reusable fraction,
descriptive statistics, per-material comparisons, source-record lineage, a
second-pass arithmetic check, and source SHA-256. A separate manifest hashes
the exact report and source text.

## Analysis capabilities

- Overall inspected and reusable mass.
- Mean, median, minimum, maximum and sample standard deviation for observed quantities.
- Per-material totals, record counts, recovery rates and share of reusable mass.
- Material comparison ranked by inspected mass.
- Source record numbers retained in each material comparison row.
- Explicit null values where a statistic is undefined (for example, sample standard
  deviation with only one observation).

## Acceptance dataset

- Inspected: 230 kg
- Reusable: 162 kg
- Reusable fraction: approximately 70.4348%

## Boundaries

This is a prototype, not a general statistical package or an autonomous agent.
It supports only `material`, `quantity_kg`, and `reusable_kg`. Statistics describe
the supplied records; they do not establish that the records represent a larger
population. Sample standard deviation is not a confidence interval. Hashes
establish byte identity, not source authenticity. The second calculation pass
checks arithmetic consistency, not measurement truth. Invalid records are
excluded and disclosed. No farm, personal, or operational data are included.
