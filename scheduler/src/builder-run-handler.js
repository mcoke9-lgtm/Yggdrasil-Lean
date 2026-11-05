import { BUILDER_ROLES, BuilderRunLedger } from "./run-ledger.js";

const HANDLER_TYPE = "nine-builders.run";

/**
 * Connects the scheduler to a replaceable role executor.
 * Production model/provider execution must be supplied by the host; this module
 * never guesses credentials, endpoints, consent, or model identity.
 */
export function registerBuilderRunHandler(scheduler, { ledger, executor, output = line => process.stdout.write(line + "\n") }) {
  if (!scheduler || typeof scheduler.registerHandler !== "function") {
    throw new TypeError("A scheduler with registerHandler is required");
  }
  if (!(ledger instanceof BuilderRunLedger)) throw new TypeError("A BuilderRunLedger is required");
  if (typeof executor !== "function") throw new TypeError("An explicitly configured Builder executor is required");

  scheduler.registerHandler(HANDLER_TYPE, async (payload, context) => {
    const runId = "builder:" + context.runId;
    const run = await ledger.createRun({
      runId,
      scheduledFor: context.scheduledFor,
      durationMs: payload?.durationMs
    });
    await ledger.startRun(runId);
    const outcomes = [];

    for (const role of BUILDER_ROLES) {
      if (context.signal?.aborted) {
        await ledger.interruptRun(runId, "scheduler cancellation requested");
        throw new Error("Nine Builders run interrupted by cancellation");
      }

      await ledger.startBuilder(runId, role);
      try {
        const result = await executor(role, {
          runId,
          scheduledFor: context.scheduledFor,
          outcomes: structuredClone(outcomes),
          checkpoint: details => ledger.checkpointBuilder(runId, role, details),
          signal: context.signal
        });
        const status = result?.status ?? "succeeded";
        if (status === "skipped" && role === "BALDR") {
          throw new Error("Baldr review cannot be skipped");
        }
        await ledger.finishBuilder(runId, role, status, result?.error ?? null);
        outcomes.push({ role, status, summary: result?.summary ?? null });
      } catch (error) {
        if (context.signal?.aborted) {
          await ledger.interruptRun(runId, "scheduler cancellation requested");
          throw new Error("Nine Builders run interrupted by cancellation");
        }
        await ledger.finishBuilder(runId, role, "failed", error?.message || error);
        outcomes.push({ role, status: "failed", summary: String(error?.message || error).slice(0, 1000) });
      }
    }

    const completed = await ledger.completeRun(runId);
    output(JSON.stringify({
      event: "nine_builders_run_completed",
      runId,
      status: completed.status,
      deadlineAt: completed.deadlineAt,
      outcomes
    }));
    return completed;
  });
}

export { HANDLER_TYPE as BUILDER_RUN_HANDLER_TYPE };
