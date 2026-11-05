import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const pagePath = resolve(here, "../public/index.html");
const BODY_LIMIT = 16 * 1024;

function send(response, status, body, contentType = "application/json; charset=utf-8") {
  response.writeHead(status, {
    "Content-Type": contentType,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
  });
  response.end(contentType.startsWith("application/json") ? JSON.stringify(body) : body);
}

async function readJson(request) {
  let body = "";
  for await (const chunk of request) {
    body += chunk;
    if (Buffer.byteLength(body) > BODY_LIMIT) throw Object.assign(new Error("Request body too large"), { statusCode: 413 });
  }
  try { return JSON.parse(body || "{}"); }
  catch { throw Object.assign(new Error("Request body must be valid JSON"), { statusCode: 400 }); }
}

export function createDashboardServer({ scheduler, host = "127.0.0.1", port = 4174, token = process.env.SCHEDULER_DASHBOARD_TOKEN } = {}) {
  if (!scheduler) throw new TypeError("A scheduler instance is required");
  if (host !== "127.0.0.1" && host !== "::1" && host !== "localhost") {
    throw new Error("Dashboard may bind only to loopback; remote network access is disabled");
  }
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new RangeError("Dashboard port must be 1024–65535");

  const server = createServer(async (request, response) => {
    try {
      const expectedHost = host.includes(":") ? `[${host}]:${port}` : `${host}:${port}`;
      if (request.headers.host !== expectedHost) return send(response, 403, { error: "Host header rejected" });
      const origin = request.headers.origin;
      if (origin && origin !== `http://${expectedHost}`) return send(response, 403, { error: "Cross-origin request rejected" });
      if (token) {
        const supplied = request.headers.authorization || "";
        if (supplied !== `Bearer ${token}`) return send(response, 401, { error: "Dashboard token required" });
      }

      const url = new URL(request.url, `http://${expectedHost}`);
      if (request.method === "GET" && url.pathname === "/") {
        const page = await readFile(pagePath, "utf8");
        return send(response, 200, page, "text/html; charset=utf-8");
      }
      if (request.method === "GET" && url.pathname === "/api/health") {
        return send(response, 200, { status: "ok", observedAt: new Date().toISOString() });
      }
      if (request.method === "GET" && url.pathname === "/api/tasks") {
        return send(response, 200, await scheduler.listTasks());
      }
      if (request.method === "GET" && url.pathname === "/api/runs") {
        const taskId = url.searchParams.get("taskId") || undefined;
        return send(response, 200, await scheduler.listRuns({ taskId, limit: 100 }));
      }
      if (request.method === "POST" && url.pathname === "/api/tasks") {
        const body = await readJson(request);
        const task = await scheduler.addTask({
          id: body.id,
          name: body.name,
          handlerType: "github.workflow-check",
          payload: {
            repository: body.repository,
            branch: body.branch,
            workflowNames: body.workflowNames
          },
          schedule: {
            kind: "calendar",
            frequency: body.frequency === "weekly" ? "weekly" : "daily",
            timeZone: body.timeZone,
            hour: body.hour,
            minute: body.minute,
            ...(body.frequency === "weekly" ? { daysOfWeek: body.daysOfWeek } : {})
          }
        });
        return send(response, 201, task);
      }
      const taskMatch = url.pathname.match(/^\/api\/tasks\/([a-zA-Z0-9][a-zA-Z0-9._-]{0,79})$/);
      if (taskMatch && request.method === "PATCH") {
        const body = await readJson(request);
        if (typeof body.enabled !== "boolean") return send(response, 400, { error: "enabled must be boolean" });
        return send(response, 200, await scheduler.setEnabled(taskMatch[1], body.enabled));
      }
      if (taskMatch && request.method === "DELETE") {
        await scheduler.deleteTask(taskMatch[1]);
        return send(response, 200, { deleted: true, id: taskMatch[1] });
      }
      return send(response, 404, { error: "Not found" });
    } catch (error) {
      const status = error.statusCode || (error.message?.includes("not found") ? 404 : 400);
      return send(response, status, { error: String(error?.message || error).slice(0, 500) });
    }
  });

  return {
    server,
    listen() {
      return new Promise((resolveListen, reject) => {
        server.once("error", reject);
        server.listen(port, host, () => {
          server.removeListener("error", reject);
          resolveListen(server.address());
        });
      });
    },
    close() {
      return new Promise((resolveClose, reject) => server.close(error => error ? reject(error) : resolveClose()));
    }
  };
}
