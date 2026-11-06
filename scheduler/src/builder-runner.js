import { configureDedicatedBuilderRunner } from "./builder-runner-config.js";

// Fail before acquiring a lock, opening a dashboard, or loading host executor code.
const configuration = configureDedicatedBuilderRunner();
process.stdout.write(JSON.stringify({
  event: "nine_builders_dedicated_runner_configuration",
  ...configuration,
  generalSchedulerIsolation: "distinct state file, lock file, and dashboard port"
}) + "\n");

await import("./runner.js");
