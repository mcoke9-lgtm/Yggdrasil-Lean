import { resolve } from "node:path";
import { JsonFileStore } from "./store.js";
import { Scheduler } from "./scheduler.js";

const scheduler = new Scheduler({
  store: new JsonFileStore(resolve(process.env.SCHEDULER_STATE_FILE || "./data/scheduler.json"))
});
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
    ""
  ].join("\n"));
}

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
}
