# Phase A Model Evaluation Runner — Operating Contract

## Purpose

Run the Phase A capability suite against one explicitly selected OpenAI-compatible model endpoint. A single model can be assigned to all nine Builder roles; each role receives its own contract prompt and its own output record. The suite expands to 63 executions: two role-specific tasks and five shared tasks for each Builder identity.

## Invocation

From the repository root:

```bash
python scripts/run_phase_a_model_evaluation.py \
  --model MODEL_ID \
  --revision MODEL_REVISION \
  --output evaluation-runs/run-001.json
```

The default endpoint is `http://127.0.0.1:8000/v1/chat/completions`. Use `--limit 1` for a smoke test. A full run makes 63 sequential requests.

For a remote HTTPS endpoint, the operator must explicitly add `--allow-remote`. Optional bearer credentials are read from `BUILDER_MODEL_API_KEY`; the secret itself is never stored in the report. Never put credentials in the endpoint URL.

## Evidence and safety rules

- The runner does not download or install models.
- The runner does not send project memory or private user data; only the public Phase A fixtures and role contracts are sent.
- Loopback is the default. Remote calls require an explicit opt-in and HTTPS.
- The runner stores the full system/user prompts and raw outputs, plus per-call latency and any provider-reported token usage.
- Credentials are not written to the report. HTTP error response bodies are intentionally omitted.
- No automatic evaluator or pass/fail score is applied. The report remains `awaiting_independent_review`.
- A model's own claim about its quality is not independent evidence.
- The client does not collect memory, CPU, power, or thermal telemetry. Those measurements require a host-side monitor.
- Failed requests are retained as failures; successful outputs are not discarded if another call fails.

## CI behavior

The Phase A GitHub Actions workflow runs the schema validator and unit tests using a mocked endpoint. It does not connect to a real model. The test suite verifies 63-case role mapping, role-conflict injection, endpoint restrictions, OpenAI-compatible response parsing, and credential non-disclosure.

## Current status

- Runner source: committed.
- Mock endpoint unit tests: committed; CI pending.
- Live model calls: not run.
- Model quality scores: none.
- Resource measurements: none.
- Independent review: pending.

A completed transport run means only that responses were received and retained. It does not mean the candidate passed the Builder capability requirements.
