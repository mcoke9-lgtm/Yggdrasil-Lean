import { BUILDER_ROLES, BuilderRunLedger, MAX_BUILDER_RUN_DURATION_MS } from "./run-ledger.js";

const HANDLER_TYPE = "nine-builders.run";

/**
 * Connects the scheduler to a replaceable role executor.
 * Production model/provider execution must be supplied by the host; this module
 * never guesses credentials, endpoints, consent, or model identity.
 */
export function registerBuilderRunHandler(
  scheduler,
  { ledger, executor, output = line => process.stdout.write(line + "\n"), clock = () => new Date() }
) {
  if (!scheduler || typeof scheduler.registerHandler !== "function") {
    throw new TypeError("A scheduler with registerHandler is required");
  }
  if (!(ledger instanceof BuilderRunLedger)) throw new TypeError("A BuilderRunLedger is required");
  if (typeof executor !== "function") throw new TypeError("An explicitly configured Builder executor is required");

  scheduler.registerHandler(HANDLER_TYPE, async (payload, context) => {
    await ledger.recoverAbandonedRuns();
    const durationMs = payload?.durationMs ?? MAX_BUILDER_RUN_DURATION_MS;
    const runId = "builder:" + context.runId;
    const run = await ledger.createRun({
      runId,
      scheduledFor: context.scheduledFor,
      durationMs
    });
    await ledger.startRun(runId);

    const controller = new AbortController();
    const abortFromScheduler = () => controller.abort(
      context.signal?.reason ?? new Error("Scheduler cancellation requested")
    );
    if (context.signal?.aborted) abortFromScheduler();
    else context.signal?.addEventListener("abort", abortFromScheduler, { once: true });

    const remainingMs = Math.max(0, new Date(run.deadlineAt).getTime() - clock().getTime());
    const deadlineTimer = setTimeout(
      () => controller.abort(new Error("Eight-hour maximum work window reached")),
      remainingMs
    );
    deadlineTimer.unref?.();

    const outcomes = [];
    const interrupt = async () => {
      if (clock().getTime() >= new Date(run.deadlineAt).getTime()) {
        await ledger.markDeadlineExpired(runId);
      } else {
        await ledger.interruptRun(runId, "scheduler cancellation or host interruption");
      }
      throw new Error("Nine Builders run interrupted; unfinished work was not replayed");
    };

    try {
      for (const role of BUILDER_ROLES) {
        if (controller.signal.aborted) await interrupt();

        await ledger.startBuilder(runId, role);
        try {
          const result = await executor(role, {
            runId,
            scheduledFor: context.scheduledFor,
            outcomes: structuredClone(outcomes),
            checkpoint: details => ledger.checkpointBuilder(runId, role, details),
            signal: controller.signal
          });
          if (controller.signal.aborted) await interrupt();
          const status = result?.status ?? "succeeded";
          if (status === "skipped" && role === "BALDR") {
            throw new Error("Baldr review cannot be skipped");
          }
          await ledger.finishBuilder(runId, role, status, result?.error ?? null);
          outcomes.push({ role, status, summary: result?.summary ?? null });
        } catch (error) {
          if (controller.signal.aborted) await interrupt();
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
    } finally {
      clearTimeout(deadlineTimer);
      context.signal?.removeEventListener("abort", abortFromScheduler);
    }
  });
}

export { HANDLER_TYPE as BUILDER_RUN_HANDLER_TYPE };
