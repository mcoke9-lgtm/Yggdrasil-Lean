const $ = selector => document.querySelector(selector);
let token = "";
let selectedTaskId = null;
const seenRunIds = new Set();
let runBaselineLoaded = false;

function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (options.body) headers["Content-Type"] = "application/json";
  return fetch(path, { ...options, headers }).then(async response => {
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.error || `Request failed (${response.status})`);
    return body;
  });
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function localDate(value) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

function makeButton(label, className, action) {
  const button = element("button", className, label);
  button.type = "button";
  button.addEventListener("click", action);
  return button;
}

async function loadTasks() {
  const tasks = await api("/api/tasks");
  const host = $("#tasks");
  host.replaceChildren();
  if (!tasks.length) {
    host.append(element("p", "muted", "No scheduled tasks yet."));
    return;
  }
  for (const task of tasks) {
    const card = element("article", "task");
    const heading = element("div", "task-title", task.name);
    const state = element("span", "status " + (task.enabled ? "good" : ""), task.enabled ? "Active" : "Paused");
    card.append(heading, state);
    card.append(element("div", "meta", `ID: ${task.id} · Handler: ${task.handlerType}`));
    card.append(element("div", "meta", `Next run: ${localDate(task.nextRunAt)}`));
    card.append(element("div", "meta", task.schedule?.kind === "calendar"
      ? `${task.schedule.frequency} · ${String(task.schedule.hour).padStart(2,"0")}:${String(task.schedule.minute).padStart(2,"0")} · ${task.schedule.timeZone}`
      : `Interval: ${task.schedule?.intervalMs ?? "—"} ms`));
    const actions = element("div", "actions");
    actions.append(makeButton(task.enabled ? "Pause" : "Resume", "secondary", async () => {
      await api(`/api/tasks/${encodeURIComponent(task.id)}`, {
        method: "PATCH", body: JSON.stringify({ enabled: !task.enabled })
      });
      await loadTasks();
    }));
    actions.append(makeButton("View runs", "secondary", async () => {
      selectedTaskId = task.id;
      await loadRuns();
    }));
    actions.append(makeButton("Delete", "danger", async () => {
      if (!confirm(`Delete scheduled task "${task.name}"? Its execution history will remain.`)) return;
      await api(`/api/tasks/${encodeURIComponent(task.id)}`, { method: "DELETE" });
      if (selectedTaskId === task.id) selectedTaskId = null;
      await Promise.all([loadTasks(), loadRuns()]);
    }));
    card.append(actions);
    host.append(card);
  }
}

async function loadRuns() {
  const path = selectedTaskId ? `/api/runs?taskId=${encodeURIComponent(selectedTaskId)}` : "/api/runs";
  const runs = await api(path);
  const host = $("#runs");
  host.replaceChildren();
  if (!runs.length) {
    host.append(element("p", "muted", "No execution history yet."));
    runBaselineLoaded = true;
    return;
  }
  for (const run of runs) {
    if (runBaselineLoaded && !seenRunIds.has(run.id) && run.status === "failed" && "Notification" in window && Notification.permission === "granted") {
      new Notification("Scheduled task failed", {
        body: `${run.taskId}: ${run.error || "Execution failed"}`,
        tag: run.id
      });
    }
    seenRunIds.add(run.id);
    const card = element("article", "run");
    card.append(element("div", "task-title", run.taskId));
    card.append(element("span", "status " + (run.status === "succeeded" ? "good" : "bad"), run.status));
    card.append(element("div", "meta", `Scheduled: ${localDate(run.scheduledFor)} · Started: ${localDate(run.startedAt)}`));
    if (run.error) card.append(element("div", "meta bad", run.error));
    host.append(card);
  }
  runBaselineLoaded = true;
}

async function refreshAll() {
  $("#connection").textContent = "Connecting…";
  try {
    await api("/api/health");
    await Promise.all([loadTasks(), loadRuns()]);
    $("#connection").textContent = "Connected to the local scheduler.";
  } catch (error) {
    $("#connection").textContent = error.message;
    throw error;
  }
}

$("#notifications").addEventListener("click", async () => {
  if (!("Notification" in window)) {
    $("#connection").textContent = "This browser does not support notifications.";
    return;
  }
  const permission = await Notification.requestPermission();
  $("#connection").textContent = permission === "granted"
    ? "Browser notifications enabled while this dashboard is open."
    : "Browser notifications were not enabled.";
});

$("#connect").addEventListener("click", async () => {
  token = $("#token").value;
  try { await refreshAll(); }
  catch { /* Status is shown above. */ }
});
$("#refresh").addEventListener("click", async () => {
  try { await loadTasks(); }
  catch (error) { $("#connection").textContent = error.message; }
});
$("#runs-refresh").addEventListener("click", async () => {
  try { await loadRuns(); }
  catch (error) { $("#connection").textContent = error.message; }
});

$("#add-task").addEventListener("submit", async event => {
  event.preventDefault();
  const formElement = event.currentTarget;
  const form = new FormData(formElement);
  const workflowNames = String(form.get("workflowNames")).split("\n").map(value => value.trim()).filter(Boolean);
  const body = {
    id: String(form.get("id")).trim(),
    name: String(form.get("name")).trim(),
    repository: String(form.get("repository")).trim(),
    branch: String(form.get("branch")).trim(),
    workflowNames,
    timeZone: String(form.get("timeZone")).trim(),
    frequency: String(form.get("frequency")),
    hour: Number(form.get("hour")),
    minute: Number(form.get("minute"))
  };
  if (body.frequency === "weekly") {
    body.daysOfWeek = String(form.get("daysOfWeek")).split(",").map(value => Number(value.trim()));
  }
  const status = $("#form-status");
  status.textContent = "Creating task…";
  try {
    await api("/api/tasks", { method: "POST", body: JSON.stringify(body) });
    status.textContent = "Task created.";
    await loadTasks();
    formElement.reset();
  } catch (error) {
    status.textContent = error.message;
  }
});

$("#add-task [name=frequency]").addEventListener("change", event => {
  $("#days-label").hidden = event.target.value !== "weekly";
});


setInterval(async () => {
  if (!token && $("#token").value) return;
  try {
    await Promise.all([loadTasks(), loadRuns()]);
  } catch (error) {
    $("#connection").textContent = error.message;
  }
}, 30000);
