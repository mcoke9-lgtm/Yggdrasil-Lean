import { resolve } from "node:path";
import { JsonFileStore } from "./store.js";
import { Scheduler } from "./scheduler.js";
import { acquireProcessLock } from "./process-lock.js";

const stateFile = resolve(process.env.SCHEDULER_STATE_FILE || "./data/scheduler.json");
const scheduler = new Scheduler({ store: new JsonFileStore(stateFile) });
const [command, ...args] = process.argv.slice(2);

function usage() {
  process.stdout.write([
    "Scheduler commands:",
    "  list",
    "  runs [task-id]",
    "  pause <task-id>",
    "  resume <task-id>",
    "  add-daily-heartbeat <id> <name> <IANA-time-zone> <hour> <minute>",
    "  add-weekly-heartbeat <id> <name> <IANA-time-zone> <hour> <minute> <days:0,1,...,6>",
    "  add-github-watch <id> <name> <owner/repo> <branch> <IANA-time-zone> <hour> <minute> <workflow-name> [workflow-name...]",
    "  add-nine-builders-run <id> <name> <IANA-time-zone> <hour> <minute> <task-reference>",
    "  resume-nine-builders <task-id> <name> <builder-run-id>",
    ""
  ].join("\n"));
}

const releaseLock = await acquireProcessLock(stateFile + ".lock");
try {
  if (command === "list") {
    process.stdout.write(JSON.stringify(await scheduler.listTasks(), null, 2) + "\n");
  } else if (command === "runs") {
    process.stdout.write(JSON.stringify(await scheduler.listRuns({ taskId: args[0], limit: 100 }), null, 2) + "\n");
  } else if (command === "pause" || command === "resume") {
    if (!args[0]) throw new Error("A task id is required");
    const task = await scheduler.setEnabled(args[0], command === "resume");
    process.stdout.write(JSON.stringify(task, null, 2) + "\n");
  } else if (command === "add-daily-heartbeat" || command === "add-weekly-heartbeat") {
    const weekly = command === "add-weekly-heartbeat";
    const [id, name, timeZone, hourText, minuteText, daysText] = args;
    const hour = Number(hourText);
    const minute = Number(minuteText);
    const schedule = {
      kind: "calendar",
      frequency: weekly ? "weekly" : "daily",
      timeZone,
      hour,
      minute,
      ...(weekly ? { daysOfWeek: String(daysText || "").split(",").map(Number) } : {})
    };
    const task = await scheduler.addTask({
      id, name, handlerType: "system.heartbeat", payload: {},
      schedule
    });
    process.stdout.write(JSON.stringify(task, null, 2) + "\n");
  } else if (command === "add-nine-builders-run") {
    const [id, name, timeZone, hourText, minuteText, taskReference] = args;
    if (!taskReference || !/^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$/.test(taskReference)) {
      throw new Error("Supply an opaque task reference from the host executor's secure task store; do not put the task prompt or credentials in scheduler state");
    }
    const task = await scheduler.addTask({
      id, name, handlerType: "nine-builders.run",
      payload: { taskReference },
      schedule: {
        kind: "calendar", frequency: "daily", timeZone,
        hour: Number(hourText), minute: Number(minuteText)
      }
    });
    process.stdout.write(JSON.stringify(task, null, 2) + "\n");
  } else if (command === "resume-nine-builders") {
    const [id, name, builderRunId] = args;
    if (!builderRunId || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$/.test(builderRunId)) {
      throw new Error("Supply the exact persisted Builder run ID to resume");
    }
    const task = await scheduler.addTask({
      id,
      name,
      handlerType: "nine-builders.run",
      payload: { resumeRunId: builderRunId },
      schedule: { kind: "once", startAt: new Date().toISOString() }
    });
    process.stdout.write(JSON.stringify(task, null, 2) + "\n");
  } else if (command === "add-github-watch") {
    const [id, name, repository, branch, timeZone, hourText, minuteText, ...workflowNames] = args;
    if (workflowNames.length < 1) throw new Error("Supply at least one exact workflow name");
    const task = await scheduler.addTask({
      id, name, handlerType: "github.workflow-check",
      payload: { repository, branch, workflowNames },
      schedule: {
        kind: "calendar", frequency: "daily", timeZone,
        hour: Number(hourText), minute: Number(minuteText)
      }
    });
    process.stdout.write(JSON.stringify(task, null, 2) + "\n");
  } else {
    usage();
    if (command) process.exitCode = 2;
  }
} catch (error) {
  process.stderr.write(`Scheduler command failed: ${error.message}\n`);
  process.exitCode = 1;
} finally {
  await releaseLock();
}
