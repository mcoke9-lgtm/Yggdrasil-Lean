import { createHash } from "node:crypto";
import {
  BUILDER_ROLES, BUILDER_ERROR_CODES, BuilderRunLedger, MAX_BUILDER_RUN_DURATION_MS
} from "./run-ledger.js";

const HANDLER_TYPE = "nine-builders.run";
const ERROR_CODE_SET = new Set(BUILDER_ERROR_CODES);

class BuilderRequestedInterruption extends Error {
  constructor(code) {
    super(code);
    this.name = "BuilderRequestedInterruption";
    this.code = code;
  }
}

function errorCode(error, fallback = "EXECUTOR_FAILURE") {
  const candidate = error?.errorCode ?? error?.code;
  return typeof candidate === "string" && ERROR_CODE_SET.has(candidate)
    ? candidate : fallback;
}

function outcomeMetadata(builder) {
  return {
    role: builder.role,
    status: builder.status,
    attempt: builder.attempt,
    errorCode: builder.errorCode ?? null,
    outcomeRef: builder.outcomeRef ?? null,
    outcomeType: builder.outcomeType ?? null,
    evidenceStatus: builder.evidenceStatus ?? null
  };
}

/**
 * Connects the scheduler to a replaceable role executor.
 * Provider execution and encrypted task-vault access remain host supplied.
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

    if (payload?.continueRunId) {
      const source = await ledger.getRun(payload.continueRunId);
      if (!source) throw new Error("Requested legacy Builder run does not exist");
      if (source.schemaVersion !== 1) {
        throw new Error("continueRunId is only for immutable version-1 legacy runs");
      }
      if (source.status !== "interrupted" && source.status !== "expired") {
        throw new Error("Only an interrupted or expired legacy Builder run can be continued");
      }
      if (typeof source.taskReference !== "string" || !source.taskReference.trim()) {
        throw new Error("MANUAL_RECONCILIATION_REQUIRED");
      }
      if (taskPayload.taskReference != null && taskPayload.taskReference !== source.taskReference) {
        throw new Error("MANUAL_RECONCILIATION_REQUIRED");
      }
      if (typeof executor.forkRunHistory !== "function") {
        throw new Error("TASK_VAULT_UNAVAILABLE");
      }
      taskPayload.taskReference = source.taskReference;
      const targetRunId = "builder:continuation:" +
        createHash("sha256").update(source.runId, "utf8").digest("hex").slice(0, 32);
      let fork;
      try {
        fork = await executor.forkRunHistory({
          taskReference: source.taskReference,
          sourceRunId: source.runId,
          targetRunId,
          sourceBuilders: structuredClone(source.builders)
        });
      } catch {
        throw new Error("MANUAL_RECONCILIATION_REQUIRED");
      }
      if (!fork || fork.durable !== true ||
          typeof fork.sourceHistorySha256 !== "string" ||
          !/^[a-f0-9]{64}$/.test(fork.sourceHistorySha256)) {
        throw new Error("MANUAL_RECONCILIATION_REQUIRED");
      }
      run = await ledger.getRun(targetRunId);
      if (run) {
        if (run.schemaVersion !== 2 || run.supersedesRunId !== source.runId ||
            run.sourceHistorySha256 !== fork.sourceHistorySha256 ||
            run.taskReference !== source.taskReference) {
          throw new Error("MANUAL_RECONCILIATION_REQUIRED");
        }
      } else {
        run = await ledger.createRun({
          runId: targetRunId,
          scheduledFor: context.scheduledFor,
          durationMs,
          taskReference: source.taskReference,
          supersedesRunId: source.runId,
          sourceHistorySha256: fork.sourceHistorySha256
        });
      }
    } else if (payload?.resumeRunId) {
      run = await ledger.getRun(payload.resumeRunId);
      if (!run) throw new Error("Requested Builder run checkpoint does not exist");
      if (run.schemaVersion !== 2) {
        throw new Error("Legacy Builder run is read-only; use continueRunId after vault reconciliation");
      }
      if (run.status !== "interrupted") {
        throw new Error("Only an interrupted Builder run can be resumed");
      }
      if (typeof run.taskReference !== "string" || !run.taskReference.trim()) {
        throw new Error("MANUAL_RECONCILIATION_REQUIRED");
      }
      if (taskPayload.taskReference != null && taskPayload.taskReference !== run.taskReference) {
        throw new Error("MANUAL_RECONCILIATION_REQUIRED");
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
    const interrupt = async (code = "CANCELLED", turnType = null) => {
      if (clock().getTime() >= new Date(startedRun.deadlineAt).getTime()) {
        await ledger.markDeadlineExpired(runId);
        code = "DEADLINE_EXPIRED";
      } else {
        await ledger.interruptRun(runId, code, activeRole);
      }
      output(JSON.stringify({
        event: "nine_builders_run_interrupted",
        runId,
        role: activeRole,
        turnType,
        errorCode: code,
        recordedAt: clock().toISOString()
      }));
      throw new BuilderRequestedInterruption(code);
    };

    try {
      for (const role of BUILDER_ROLES) {
        if (controller.signal.aborted) {
          await interrupt(clock().getTime() >= new Date(startedRun.deadlineAt).getTime()
            ? "DEADLINE_EXPIRED" : "CANCELLED");
        }

        const savedRun = await ledger.getRun(runId);
        const savedBuilder = savedRun.builders.find(item => item.role === role);
        if (["succeeded", "failed", "skipped"].includes(savedBuilder.status)) {
          outcomes.push(outcomeMetadata(savedBuilder));
          continue;
        }
        if (savedBuilder.status === "interrupted" && !savedBuilder.checkpoint?.safeToResume) {
          activeRole = role;
          if (typeof executor.recoverDurableResult !== "function") {
            await interrupt("MANUAL_RECONCILIATION_REQUIRED");
          }
          if (controller.signal.aborted) await interrupt("CANCELLED");
          let recovered;
          try {
            recovered = await executor.recoverDurableResult(role, {
              runId,
              scheduledFor: context.scheduledFor,
              task: structuredClone(taskPayload),
              outcomes: structuredClone(outcomes),
              signal: controller.signal
            });
          } catch {
            await interrupt("MANUAL_RECONCILIATION_REQUIRED");
          }
          if (controller.signal.aborted) await interrupt("CANCELLED");
          if (!recovered) await interrupt("VAULT_RESULT_MISSING");
          if (recovered.status === "interrupted") {
            await interrupt("BUILDER_REQUESTED_INTERRUPTION", recovered.turnType ?? null);
          }
          try {
            const recoveredRun = await ledger.recoverBuilder(runId, role, {
              checkpointId: recovered.checkpointId ?? recovered.outcomeRef,
              responseSha256: recovered.responseSha256,
              turnType: recovered.turnType,
              evidenceStatus: recovered.evidenceStatus
            });
            const recoveredBuilder = recoveredRun.builders.find(item => item.role === role);
            activeRole = null;
            outcomes.push(outcomeMetadata(recoveredBuilder));
            continue;
          } catch {
            await interrupt("MANUAL_RECONCILIATION_REQUIRED");
          }
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
          if (controller.signal.aborted) await interrupt("CANCELLED");
          const status = result?.status ?? "succeeded";
          const stopTurn = result?.turnType === "INTERRUPTION"
            || result?.turnType === "IMPROVEMENT_INCIDENT";
          if (status === "interrupted" || stopTurn) {
            await interrupt("BUILDER_REQUESTED_INTERRUPTION", result?.turnType ?? null);
          }
          if (status === "skipped" && role === "BALDR") {
            throw new Error("Baldr review cannot be skipped");
          }
          await ledger.finishBuilder(runId, role, status, {
            errorCode: status === "failed" ? errorCode(result, "EXECUTOR_FAILURE") : null,
            outcomeRef: result?.outcomeRef ?? result?.checkpointId ?? null,
            outcomeType: result?.turnType ?? null,
            evidenceStatus: result?.evidenceStatus ?? null,
            responseSha256: result?.responseSha256 ?? null
          });
          activeRole = null;
          const finished = await ledger.getRun(runId);
          outcomes.push(outcomeMetadata(finished.builders.find(item => item.role === role)));
        } catch (error) {
          if (controller.signal.aborted) await interrupt("CANCELLED");
          if (error instanceof BuilderRequestedInterruption) throw error;
          await ledger.finishBuilder(runId, role, "failed", {
            errorCode: errorCode(error, "EXECUTOR_FAILURE")
          });
          activeRole = null;
          const failed = await ledger.getRun(runId);
          outcomes.push(outcomeMetadata(failed.builders.find(item => item.role === role)));
        }
      }

      const completed = await ledger.completeRun(runId);
      output(JSON.stringify({
        event: "nine_builders_run_completed",
        runId,
        status: completed.status,
        deadlineAt: completed.deadlineAt,
        outcomes: completed.builders.map(outcomeMetadata)
      }));
      return completed;
    } finally {
      clearTimeout(deadlineTimer);
      context.signal?.removeEventListener("abort", abortFromScheduler);
    }
  });
}

export { HANDLER_TYPE as BUILDER_RUN_HANDLER_TYPE };
