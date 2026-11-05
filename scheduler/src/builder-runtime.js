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
  loadModule = url => import(url),
  output = line => process.stdout.write(line + "\n")
}) {
  if (!scheduler) throw new TypeError("A scheduler is required");
  if (!schedulerStateFile) throw new TypeError("A scheduler state file is required");
  if (!executorModulePath || !String(executorModulePath).trim()) {
    return { configured: false, reason: "BUILDER_EXECUTOR_MODULE is not configured" };
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

  // Keep BuilderRunLedger in a separate file so its load/modify/save operations
  // cannot overwrite concurrent scheduler task/run updates in scheduler.json.
  const builderStateFile = process.env.BUILDER_RUN_STATE_FILE
    ? resolve(process.env.BUILDER_RUN_STATE_FILE)
    : resolve(schedulerStateFile + ".builder-runs.json");
  const ledger = new BuilderRunLedger({ store: new JsonFileStore(builderStateFile) });
  registerBuilderRunHandler(scheduler, { ledger, executor, output });
  return { configured: true, builderStateFile };
}
