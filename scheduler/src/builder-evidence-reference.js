/**
 * Read-only, provider-neutral verification of typed GitHub evidence references.
 * No network client, credentials, or mutation methods are provided here; the host
 * must inject an authorized gateway implementing the four read-only methods below.
 */

function required(value, label, max = 2048) {
  if (typeof value !== "string" || !value.trim() || value.length > max) {
    throw new TypeError(label + " must be a non-empty string no longer than " + max + " characters");
  }
  return value.trim();
}

function uniqueQueryValue(url, key) {
  const values = url.searchParams.getAll(key);
  if (values.length !== 1 || !values[0].trim()) {
    throw new Error("Evidence reference requires exactly one non-empty " + key + " parameter");
  }
  return values[0].trim();
}

export function parseBuilderEvidenceReference(value) {
  const raw = required(value, "GitHub evidence reference", 2048);
  let url;
  try { url = new URL(raw); }
  catch { throw new Error("GitHub evidence reference is malformed"); }
  if (url.hostname !== "github.com" || url.username || url.password || url.hash) {
    throw new Error("Evidence host must be github.com and must not contain credentials or a fragment");
  }
  const path = url.pathname.split("/");
  if (path.length < 4 || path[0] !== "") throw new Error("Evidence repository path is malformed");
  const repository = path[1] + "/" + path[2];
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository)) {
    throw new Error("Evidence repository identity is malformed");
  }

  if (url.protocol === "github+commit:") {
    if (path.length !== 4 || !/^[0-9a-fA-F]{40}$/.test(path[3])) {
      throw new Error("Commit reference requires a full 40-character SHA");
    }
    return Object.freeze({
      kind: "COMMIT",
      repository,
      objectId: path[3].toLowerCase(),
      branch: uniqueQueryValue(url, "branch"),
      workflow: null,
      expectedSha: null
    });
  }

  if (url.protocol === "github+actions:") {
    if (path.length !== 5 || path[3] !== "runs" || !/^[1-9][0-9]*$/.test(path[4])) {
      throw new Error("Actions reference requires a positive numeric workflow run ID");
    }
    const runId = Number(path[4]);
    if (!Number.isSafeInteger(runId)) throw new Error("Actions run ID exceeds the safe integer range");
    const expectedSha = uniqueQueryValue(url, "sha").toLowerCase();
    if (!/^[0-9a-f]{40}$/.test(expectedSha)) {
      throw new Error("Workflow run reference requires a full 40-character tested SHA");
    }
    return Object.freeze({
      kind: "ACTIONS_RUN",
      repository,
      objectId: path[4],
      branch: null,
      workflow: uniqueQueryValue(url, "workflow"),
      expectedSha
    });
  }

  throw new Error("Unsupported GitHub evidence reference scheme");
}

function unavailable(reason) {
  return Object.freeze({ verified: false, status: "UNAVAILABLE", reason });
}

function unverified(reason, reference) {
  return Object.freeze({
    verified: false,
    status: "UNVERIFIED",
    reason,
    kind: reference.kind,
    repository: reference.repository,
    objectId: reference.objectId,
    branch: reference.branch,
    workflow: reference.workflow,
    expectedSha: reference.expectedSha,
    verificationMethod: "READ_ONLY_GITHUB_GATEWAY"
  });
}

function verified(reference, reason) {
  return Object.freeze({
    verified: true,
    status: "VERIFIED",
    reason,
    kind: reference.kind,
    repository: reference.repository,
    objectId: reference.objectId,
    branch: reference.branch,
    workflow: reference.workflow,
    expectedSha: reference.expectedSha,
    verificationMethod: "READ_ONLY_GITHUB_GATEWAY"
  });
}

/**
 * Verifies identity and source state using an injected read-only gateway.
 *
 * Required gateway methods:
 * - fetchCommit(repository, sha)
 * - branchContainsCommit(repository, branch, sha)
 * - fetchWorkflowRun(repository, runId)
 * - fetchWorkflowJobs(repository, runId)
 *
 * A missing gateway or transport failure is UNAVAILABLE, never a pass.
 */
export async function verifyBuilderEvidenceReference(value, gateway, {
  signal,
  timeoutMs = 15000
} = {}) {
  let reference;
  try { reference = parseBuilderEvidenceReference(value); }
  catch (error) {
    return Object.freeze({ verified: false, status: "UNVERIFIED", reason: error.message });
  }

  const methods = reference.kind === "COMMIT"
    ? ["fetchCommit", "branchContainsCommit"]
    : ["fetchWorkflowRun", "fetchWorkflowJobs"];
  if (!gateway || methods.some(method => typeof gateway[method] !== "function")) {
    return unavailable("No complete read-only GitHub evidence gateway is configured.");
  }
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1000 || timeoutMs > 300000) {
    return unavailable("Evidence gateway timeout must be between 1 second and 5 minutes.");
  }

  const controller = new AbortController();
  const abortFromCaller = () => controller.abort(signal?.reason || new Error("Evidence verification cancelled"));
  if (signal?.aborted) abortFromCaller();
  else signal?.addEventListener("abort", abortFromCaller, { once: true });
  const timeout = setTimeout(() => {
    const error = new Error("Evidence verification timed out");
    error.name = "TimeoutError";
    controller.abort(error);
  }, timeoutMs);
  timeout.unref?.();
  let rejectAbort;
  const aborted = new Promise((_, reject) => { rejectAbort = reject; });
  const onAbort = () => rejectAbort(controller.signal.reason || new Error("Evidence verification aborted"));
  controller.signal.addEventListener("abort", onAbort, { once: true });
  const call = (method, ...args) => {
    if (controller.signal.aborted) throw controller.signal.reason || new Error("Evidence verification aborted");
    return Promise.race([
      Promise.resolve().then(() => method(...args, controller.signal)),
      aborted
    ]);
  };

  try {
    if (reference.kind === "COMMIT") {
      const commit = await call(gateway.fetchCommit.bind(gateway), reference.repository, reference.objectId);
      if (!commit) return unverified("Commit was not found.", reference);
      if (commit.repository !== reference.repository ||
          typeof commit.sha !== "string" ||
          commit.sha.toLowerCase() !== reference.objectId) {
        return unverified("Fetched commit identity does not match the reference.", reference);
      }
      const onBranch = await call(
        gateway.branchContainsCommit.bind(gateway),
        reference.repository, reference.branch, reference.objectId
      );
      if (onBranch !== true) {
        return unverified("Commit is not confirmed on the referenced branch.", reference);
      }
      return verified(reference, "Commit identity and branch membership confirmed.");
    }

    const runId = Number(reference.objectId);
    const run = await call(gateway.fetchWorkflowRun.bind(gateway), reference.repository, runId);
    if (!run) return unverified("Workflow run was not found.", reference);
    if (run.repository !== reference.repository || run.runId !== runId) {
      return unverified("Workflow run identity does not match the reference.", reference);
    }
    if (run.workflow !== reference.workflow) {
      return unverified("Workflow name does not match the reference.", reference);
    }
    if (typeof run.headSha !== "string" ||
        run.headSha.toLowerCase() !== reference.expectedSha) {
      return unverified("Workflow run tested a different commit SHA.", reference);
    }
    if (run.status !== "completed" || run.conclusion !== "success") {
      return unverified("Workflow run did not complete successfully.", reference);
    }
    const jobs = await call(gateway.fetchWorkflowJobs.bind(gateway), reference.repository, runId);
    if (!Array.isArray(jobs) || jobs.length === 0) {
      return unverified("Workflow run has no retrievable jobs.", reference);
    }
    if (jobs.some(job => !job || job.status !== "completed" || job.conclusion !== "success")) {
      return unverified("At least one workflow job is missing, incomplete, or unsuccessful.", reference);
    }
    return verified(reference, "Workflow identity, tested SHA, run conclusion, and all jobs confirmed.");
  } catch (error) {
    if (controller.signal.aborted) {
      const reason = controller.signal.reason;
      return unavailable(reason?.name === "TimeoutError"
        ? "Evidence source verification timed out."
        : "Evidence source verification was cancelled.");
    }
    return unavailable("Evidence source unavailable: " +
      (error && typeof error.name === "string" ? error.name : "UnknownError"));
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener("abort", abortFromCaller);
    controller.signal.removeEventListener("abort", onAbort);
  }
}
