import { resolve } from "node:path";

function required(value, name) {
  if (typeof value !== "string" || !value.trim()) {
    throw new TypeError(name + " is required for the dedicated Nine Builders runner");
  }
  return value.trim();
}

/**
 * Require explicit state and dashboard separation before starting the shared runner.
 * This prevents accidental reuse of the general scheduler's state file, lock, or port.
 */
export function configureDedicatedBuilderRunner(env = process.env, cwd = process.cwd()) {
  const builderStateFile = resolve(cwd, required(
    env.NINE_BUILDERS_SCHEDULER_STATE_FILE,
    "NINE_BUILDERS_SCHEDULER_STATE_FILE"
  ));
  const generalStateFile = resolve(cwd,
    env.NINE_BUILDERS_GENERAL_STATE_FILE || "./data/scheduler.json");
  if (builderStateFile === generalStateFile) {
    throw new Error("Dedicated Nine Builders state file must differ from the general scheduler state file");
  }

  const builderPortText = required(
    env.NINE_BUILDERS_DASHBOARD_PORT,
    "NINE_BUILDERS_DASHBOARD_PORT"
  );
  if (!/^\d+$/.test(builderPortText)) throw new TypeError("NINE_BUILDERS_DASHBOARD_PORT must be an integer port");
  const builderPort = Number(builderPortText);
  if (!Number.isInteger(builderPort) || builderPort < 1 || builderPort > 65535) {
    throw new RangeError("NINE_BUILDERS_DASHBOARD_PORT must be between 1 and 65535");
  }
  const generalPortText = env.NINE_BUILDERS_GENERAL_DASHBOARD_PORT || "4174";
  if (!/^\d+$/.test(generalPortText)) throw new TypeError("General scheduler dashboard port must be an integer");
  const generalPort = Number(generalPortText);
  if (builderPort === generalPort) {
    throw new Error("Dedicated Nine Builders dashboard port must differ from the general scheduler port");
  }

  const executorModule = required(env.BUILDER_EXECUTOR_MODULE, "BUILDER_EXECUTOR_MODULE");
  env.SCHEDULER_STATE_FILE = builderStateFile;
  env.SCHEDULER_DASHBOARD_PORT = String(builderPort);
  env.BUILDER_EXECUTOR_MODULE = resolve(cwd, executorModule);
  return {
    schedulerStateFile: builderStateFile,
    schedulerLockFile: builderStateFile + ".lock",
    dashboardPort: builderPort,
    executorModule: env.BUILDER_EXECUTOR_MODULE
  };
}
