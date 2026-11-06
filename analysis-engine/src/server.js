import { createServer as createHttpServer } from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join, extname, resolve } from "node:path";
import { analyze, parseInput } from "./engine.js";

const ROOT = dirname(fileURLToPath(import.meta.url));
const PUBLIC = join(ROOT, "public");
const MAX_BODY_BYTES = 5 * 1024 * 1024;
const ASSETS = new Map([
  ["/", ["index.html", "text/html; charset=utf-8"]],
  ["/app.js", ["app.js", "text/javascript; charset=utf-8"]],
  ["/styles.css", ["styles.css", "text/css; charset=utf-8"]]
]);

function send(response, status, body, contentType = "application/json; charset=utf-8") {
  response.writeHead(status, {
    "Content-Type": contentType,
    "X-Content-Type-Options": "nosniff",
    "Cache-Control": "no-store",
    "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
  });
  response.end(contentType.startsWith("application/json") ? JSON.stringify(body) : body);
}

async function readBody(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw Object.assign(new Error("Upload exceeds the 5 MB limit"), { statusCode: 413 });
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

export function createServer() {
  return createHttpServer(async (request, response) => {
    try {
      const host = request.headers.host || "";
      if (!/^(127\.0\.0\.1|localhost)(:\d+)?$/.test(host)) return send(response, 403, { error: "This local service accepts requests only through localhost" });
      const url = new URL(request.url, "http://127.0.0.1");
      if (request.method === "GET" && ASSETS.has(url.pathname)) {
        const [filename, type] = ASSETS.get(url.pathname);
        const body = await readFile(join(PUBLIC, filename), "utf8");
        return send(response, 200, body, type);
      }
      if (request.method === "POST" && url.pathname === "/api/analyze") {
        if (!request.headers["content-type"]?.includes("application/json")) {
          return send(response, 415, { error: "Send the file as application/json" });
        }
        const payload = JSON.parse(await readBody(request));
        if (!payload || typeof payload.filename !== "string" || typeof payload.content !== "string") {
          return send(response, 400, { error: "Expected filename and content strings" });
        }
        const extension = extname(payload.filename).toLowerCase().slice(1);
        if (!["csv", "json"].includes(extension)) {
          return send(response, 400, { error: "Choose a .csv or .json file" });
        }
        const records = parseInput(payload.content, extension);
        const report = analyze(records, { sourceName: payload.filename.split(/[\\/]/).pop(), sourceText: payload.content });
        return send(response, 200, { report });
      }
      if (url.pathname.startsWith("/api/")) return send(response, 404, { error: "API route not found" });
      return send(response, 404, { error: "Not found" });
    } catch (error) {
      return send(response, error.statusCode || 400, { error: error.message || "Request failed" });
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const port = Number(process.env.PORT || 4173);
  const server = createServer();
  server.listen(port, "127.0.0.1", () => {
    console.log(`Nine Builders Analysis Engine is available at http://127.0.0.1:${port}`);
    console.log("Local-only: the server listens on 127.0.0.1 and does not transmit files externally.");
  });
}
