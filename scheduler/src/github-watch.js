import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";

async function loadCursor(path) {
  try {
    const state = JSON.parse(await readFile(path, "utf8"));
    if (!Array.isArray(state.seenCompletedRunIds)) throw new Error("Invalid GitHub watch cursor");
    return state;
  } catch (error) {
    if (error.code === "ENOENT") return null;
    throw error;
  }
}

async function saveCursor(path, state) {
  await mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.${process.pid}.tmp`;
  await writeFile(temporary, JSON.stringify(state, null, 2) + "\n", { encoding: "utf8", mode: 0o600 });
  await rename(temporary, path);
}

export function registerGithubWatch(scheduler, {
  fetchImpl = fetch,
  output = line => process.stdout.write(line + "\n"),
  cursorPath = resolve(process.env.SCHEDULER_GITHUB_WATCH_STATE || "./data/github-watch-state.json"),
  token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN
} = {}) {
  scheduler.registerHandler("github.workflow-check", async payload => {
    const repo = payload?.repository;
    const branch = payload?.branch;
    const workflowNames = payload?.workflowNames;
    if (typeof repo !== "string" || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo)) {
      throw new TypeError("GitHub watch requires repository in owner/name form");
    }
    if (typeof branch !== "string" || !branch.trim()) throw new TypeError("GitHub watch requires a branch");
    if (!Array.isArray(workflowNames) || workflowNames.length < 1 ||
        workflowNames.some(name => typeof name !== "string" || !name.trim())) {
      throw new TypeError("GitHub watch requires one or more exact workflow names");
    }

    const url = new URL(`https://api.github.com/repos/${repo}/actions/runs`);
    url.searchParams.set("branch", branch);
    url.searchParams.set("per_page", "100");
    const headers = {
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28"
    };
    if (token) headers.Authorization = `Bearer ${token}`;

    const response = await fetchImpl(url, {
      method: "GET",
      headers,
      signal: AbortSignal.timeout(30000)
    });
    if (!response.ok) throw new Error(`GitHub Actions API returned HTTP ${response.status}`);
    const body = await response.json();
    if (!Array.isArray(body.workflow_runs)) throw new Error("GitHub Actions response did not contain workflow_runs");

    const names = new Set(workflowNames);
    const completed = body.workflow_runs.filter(run =>
      names.has(run.name) && run.head_branch === branch && run.status === "completed" &&
      run.id !== undefined && run.id !== null
    );
    const prior = await loadCursor(cursorPath);
    const seen = new Set(prior?.seenCompletedRunIds || []);
    if (!prior) {
      await saveCursor(cursorPath, {
        repository: repo,
        branch,
        workflowNames: [...names],
        initializedAt: new Date().toISOString(),
        seenCompletedRunIds: completed.map(run => String(run.id)).slice(0, 500)
      });
      output(JSON.stringify({
        event: "github_watch_baseline_created",
        repository: repo,
        branch,
        completedRunsRecorded: completed.length,
        note: "Existing runs were recorded as baseline; only later completed runs can alert."
      }));
      return;
    }

    const fresh = completed.filter(run => !seen.has(String(run.id)));
    const failures = fresh.filter(run => run.conclusion !== "success");
    for (const run of failures) {
      output(JSON.stringify({
        event: "github_build_failure",
        repository: repo,
        branch,
        workflow: run.name,
        runId: run.id,
        conclusion: run.conclusion,
        commit: run.head_sha,
        url: run.html_url,
        createdAt: run.created_at,
        updatedAt: run.updated_at
      }));
    }
    if (failures.length === 0) {
      output(JSON.stringify({
        event: "github_watch_check",
        repository: repo,
        branch,
        newCompletedRuns: fresh.length,
        newFailures: 0
      }));
    }

    const merged = [...new Set([...seen, ...completed.map(run => String(run.id))])].slice(-500);
    await saveCursor(cursorPath, {
      repository: repo,
      branch,
      workflowNames: [...names],
      updatedAt: new Date().toISOString(),
      seenCompletedRunIds: merged
    });
  });
}
