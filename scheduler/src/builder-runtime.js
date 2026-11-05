import { pathToFileURL } from "node:url";
import { resolve } from "node:path";
import { JsonFileStore } from "./store.js";
import { BuilderRunLedger } from "./run-ledger.js";
import { registerBuilderRunHandler } from "./builder-run-handler.js";

/**
 * Optionally connects the persistent scheduler to a host-owned model executor.
 * The executor module is deliberately external: provider credentials and routes
 * must never be committed to this repository or copied into scheduler state.
 */
export async function registerOptionalBuilderRuntime({
  scheduler,
  schedulerStateFile,
  executorModulePath = process.env.BUILDER_EXECUTOR_MODULE,
  builderStateFilePath = process.env.BUILDER_RUN_STATE_FILE,
  loadModule = url => import(url),
  output = line => process.stdout.write(line + "\n")
}) {
  if (!scheduler) throw new TypeError("A scheduler is required");
  if (!schedulerStateFile) throw new TypeError("A scheduler state file is required");
  if (!executorModulePath || !String(executorModulePath).trim()) {
    return { configured: false, reason: "BUILDER_EXECUTOR_MODULE is not configured" };
  }

  // Validate state separation before loading any operator-supplied module.
  // Ledger writes must not race with scheduler task/run writes to the same JSON file.
  const resolvedSchedulerStateFile = resolve(schedulerStateFile);
  const builderStateFile = builderStateFilePath
    ? resolve(builderStateFilePath)
    : resolve(schedulerStateFile + ".builder-runs.json");
  if (builderStateFile === resolvedSchedulerStateFile) {
    throw new Error("Builder run ledger must use a separate state file from scheduler tasks and runs");
  }

  const moduleUrl = pathToFileURL(resolve(executorModulePath)).href;
  const hostModule = await loadModule(moduleUrl);
  if (typeof hostModule.createBuilderExecutor !== "function") {
    throw new TypeError("Host executor module must export createBuilderExecutor()");
  }
  const executor = await hostModule.createBuilderExecutor();
  if (typeof executor !== "function") {
    throw new TypeError("createBuilderExecutor() must return an async role executor");
  }

  const ledger = new BuilderRunLedger({ store: new JsonFileStore(builderStateFile) });
  registerBuilderRunHandler(scheduler, { ledger, executor, output });
  return { configured: true, builderStateFile };
}
