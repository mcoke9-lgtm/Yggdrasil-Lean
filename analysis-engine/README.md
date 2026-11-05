# Nine Builders Analysis Engine 0.1

A dependency-free JavaScript prototype. The calculation core is separate from the
command-line interface so it can later be called by a browser UI or Android wrapper.

## Requirements

- Node.js 20 or newer
- No third-party packages
- No network connection required

## Run

From this directory:

```sh
npm test
node src/cli.js samples/materials.csv reports/materials-report.json
```

The CLI refuses to overwrite an existing report or manifest. The report includes
accepted/rejected records, data-quality findings, totals, reusable fraction,
a second-pass arithmetic check, and source SHA-256. A separate manifest hashes
the exact report and source text.

## Acceptance dataset

- Inspected: 230 kg
- Reusable: 162 kg
- Reusable fraction: approximately 70.4348%

## Boundaries

This is a prototype, not a general statistical package or an autonomous agent.
It supports only `material`, `quantity_kg`, and `reusable_kg`. Hashes establish
byte identity, not source authenticity. The second calculation pass checks
arithmetic consistency, not the truth of measurements. Invalid records are
excluded and disclosed. No farm, personal, or operational data are included.
