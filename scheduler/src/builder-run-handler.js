import { BUILDER_ROLES, BuilderRunLedger, MAX_BUILDER_RUN_DURATION_MS } from "./run-ledger.js";

const HANDLER_TYPE = "nine-builders.run";
class BuilderRequestedInterruption extends Error {
  constructor(message) {
    super(message);
    this.name = "BuilderRequestedInterruption";
  }
}


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
    let run;
    const taskPayload = structuredClone(payload ?? {});
    if (payload?.resumeRunId) {
      run = await ledger.getRun(payload.resumeRunId);
      if (!run) throw new Error("Requested Builder run checkpoint does not exist");
      if (run.status !== "interrupted") {
        throw new Error("Only an interrupted Builder run can be resumed");
      }
      if (typeof run.taskReference !== "string" || !run.taskReference.trim()) {
        throw new Error("Legacy Builder run has no persisted taskReference; manual reconciliation is required before resume");
      }
      if (taskPayload.taskReference != null && taskPayload.taskReference !== run.taskReference) {
        throw new Error("Resume taskReference does not match the original Builder run");
      }
      taskPayload.taskReference = run.taskReference;
    } else {
      if (typeof taskPayload.taskReference !== "string" ||
          !taskPayload.taskReference.trim() || taskPayload.taskReference.length > 256) {
        throw new TypeError("A persisted taskReference is required for every new Nine Builders run");
      }
      taskPayload.taskReference = taskPayload.taskReference.trim();
      run = await ledger.createRun({
        runId: "builder:" + context.runId,
        scheduledFor: context.scheduledFor,
        durationMs,
        taskReference: taskPayload.taskReference
      });
    }
    const runId = run.runId;
    const startedRun = await ledger.startRun(runId);

    const controller = new AbortController();
    const abortFromScheduler = () => controller.abort(
      context.signal?.reason ?? new Error("Scheduler cancellation requested")
    );
    if (context.signal?.aborted) abortFromScheduler();
    else context.signal?.addEventListener("abort", abortFromScheduler, { once: true });

    const remainingMs = Math.max(0, new Date(startedRun.deadlineAt).getTime() - clock().getTime());
    const deadlineTimer = setTimeout(
      () => controller.abort(new Error("Eight-hour maximum work window reached")),
      remainingMs
    );
    deadlineTimer.unref?.();

    const outcomes = [];
    let activeRole = null;
    const interrupt = async () => {
      if (clock().getTime() >= new Date(startedRun.deadlineAt).getTime()) {
        await ledger.markDeadlineExpired(runId);
      } else {
        await ledger.interruptRun(runId, "scheduler cancellation or host interruption", activeRole);
      }
      throw new Error("Nine Builders run interrupted; unfinished work was not replayed");
    };

    try {
      for (const role of BUILDER_ROLES) {
        if (controller.signal.aborted) await interrupt();

        const savedRun = await ledger.getRun(runId);
        const savedBuilder = savedRun.builders.find(item => item.role === role);
        if (["succeeded", "failed", "skipped"].includes(savedBuilder.status)) {
          outcomes.push({
            role,
            status: savedBuilder.status,
            summary: savedBuilder.summary ?? savedBuilder.error ?? "Previously completed checkpoint"
          });
          continue;
        }
        if (savedBuilder.status === "interrupted" && !savedBuilder.checkpoint?.safeToResume) {
          await ledger.interruptRun(runId,
            "Manual reconciliation required: interrupted Builder has no safe resume checkpoint",
            role);
          throw new Error("Cannot resume " + role + ": no explicitly safe checkpoint");
        }

        activeRole = role;
        await ledger.startBuilder(runId, role);
        try {
          const result = await executor(role, {
            runId,
            scheduledFor: context.scheduledFor,
            task: structuredClone(taskPayload),
            outcomes: structuredClone(outcomes),
            checkpoint: details => ledger.checkpointBuilder(runId, role, details),
            signal: controller.signal
          });
          if (controller.signal.aborted) await interrupt();
          const status = result?.status ?? "succeeded";
          const stopTurn = result?.turnType === "INTERRUPTION"
            || result?.turnType === "IMPROVEMENT_INCIDENT";
          if (status === "interrupted" || stopTurn) {
            const reason = String(result?.summary || (role + " issued " + (result?.turnType || "an interruption"))).slice(0, 1000);
            await ledger.interruptRun(runId, reason, role);
            output(JSON.stringify({
              event: "nine_builders_run_interrupted",
              runId,
              role,
              turnType: result?.turnType ?? null,
              reason
            }));
            throw new BuilderRequestedInterruption(reason);
          }
          if (status === "skipped" && role === "BALDR") {
            throw new Error("Baldr review cannot be skipped");
          }
          await ledger.finishBuilder(runId, role, status, result?.error ?? null, result?.summary ?? null);
          activeRole = null;
          outcomes.push({ role, status, summary: result?.summary ?? null });
        } catch (error) {
          if (controller.signal.aborted) await interrupt();
          if (error instanceof BuilderRequestedInterruption) throw error;
          await ledger.finishBuilder(runId, role, "failed", error?.message || error, null);
          activeRole = null;
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
