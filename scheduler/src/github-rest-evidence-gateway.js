const API_ROOT = "https://api.github.com";
const MAX_RESPONSE_BYTES = 1024 * 1024;
const PAGE_SIZE = 100;
const MAX_JOB_PAGES = 10;

function required(value, label, max = 256) {
  if (typeof value !== "string" || !value.trim() || value.length > max) {
    throw new TypeError(label + " must be a non-empty string no longer than " + max + " characters");
  }
  return value.trim();
}

function repositoryParts(repository) {
  const value = required(repository, "repository", 256);
  const parts = value.split("/");
  if (parts.length !== 2 || parts.some(part => !/^[A-Za-z0-9_.-]+$/.test(part))) {
    throw new TypeError("repository must use owner/name form");
  }
  return parts.map(encodeURIComponent);
}

function retryAfterMilliseconds(response) {
  const raw = response.headers?.get?.("retry-after");
  if (typeof raw !== "string" || !raw.trim()) return null;
  const value = raw.trim();
  if (/^\d+(?:\.\d+)?$/.test(value)) return Math.ceil(Number(value) * 1000);
  const date = Date.parse(value);
  return Number.isFinite(date) ? Math.max(0, date - Date.now()) : null;
}

function abortableDelay(milliseconds, signal) {
  if (signal?.aborted) return Promise.reject(signal.reason || new Error("Evidence request cancelled"));
  if (milliseconds <= 0) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", abort);
      resolve();
    }, milliseconds);
    const abort = () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", abort);
      reject(signal.reason || new Error("Evidence request cancelled"));
    };
    signal?.addEventListener("abort", abort, { once: true });
    if (signal?.aborted) abort();
  });
}

function retryableHttpStatus(response) {
  if ([408, 429].includes(response.status) ||
      (response.status >= 500 && response.status <= 599)) return true;
  return response.status === 403 &&
    (retryAfterMilliseconds(response) != null ||
      response.headers?.get?.("x-ratelimit-remaining") === "0");
}

/**
 * Create a read-only GitHub REST evidence gateway.
 *
 * The host supplies readSecret(secretRef); the token is held only in this
 * closure. All API operations are GET requests and accept AbortSignal.
 */
export async function createEvidenceGateway({
  readSecret,
  secretRef,
  fetchImpl = globalThis.fetch,
  maxAttempts = 3,
  baseDelayMs = 250,
  maxDelayMs = 5000
} = {}) {
  if (typeof readSecret !== "function") throw new TypeError("readSecret(secretRef) is required");
  const reference = required(secretRef, "evidence gateway secretRef", 256);
  if (typeof fetchImpl !== "function") throw new TypeError("A Fetch-compatible transport is required");
  if (!Number.isInteger(maxAttempts) || maxAttempts < 1 || maxAttempts > 5 ||
      !Number.isInteger(baseDelayMs) || baseDelayMs < 0 || baseDelayMs > 5000 ||
      !Number.isInteger(maxDelayMs) || maxDelayMs < 1 || maxDelayMs > 30000) {
    throw new RangeError("Evidence retry policy must use 1–5 attempts and bounded delays");
  }
  const token = await readSecret(reference);
  if (typeof token !== "string" || !token.trim()) {
    throw new Error("Evidence gateway secret resolver returned no token");
  }

  async function getJson(path, signal) {
    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      if (signal?.aborted) throw signal.reason || new Error("Evidence request cancelled");
      let response;
      try {
        response = await fetchImpl(API_ROOT + path, {
          method: "GET",
          headers: {
            Accept: "application/vnd.github+json",
            Authorization: "Bearer " + token.trim(),
            "X-GitHub-Api-Version": "2022-11-28"
          },
          signal,
          redirect: "error"
        });
      } catch (error) {
        if (signal?.aborted || error?.name === "AbortError") throw error;
        if (attempt >= maxAttempts || !(error instanceof TypeError)) throw error;
        await abortableDelay(Math.min(baseDelayMs * (2 ** (attempt - 1)), maxDelayMs), signal);
        continue;
      }

      if (!response || typeof response.status !== "number") {
        const error = new Error("GitHub evidence API returned an invalid response");
        error.name = "GitHubEvidenceTransportError";
        throw error;
      }
      if (!response.ok) {
        if (retryableHttpStatus(response) && attempt < maxAttempts) {
          const retryAfter = retryAfterMilliseconds(response);
          const delay = retryAfter == null
            ? Math.min(baseDelayMs * (2 ** (attempt - 1)), maxDelayMs)
            : retryAfter;
          if (delay > maxDelayMs) {
            const error = new Error("GitHub evidence Retry-After exceeded the configured delay limit");
            error.name = "GitHubEvidenceRetryDelayExceededError";
            throw error;
          }
          try { await response.body?.cancel(); } catch {}
          await abortableDelay(delay, signal);
          continue;
        }
        const error = new Error("GitHub evidence API request failed");
        error.name = "GitHubEvidenceHttpError";
        error.status = response.status;
        throw error;
      }

      let body;
      try { body = await response.text(); }
      catch (error) {
        if (signal?.aborted || error?.name === "AbortError") throw error;
        if (attempt >= maxAttempts || !(error instanceof TypeError)) throw error;
        await abortableDelay(Math.min(baseDelayMs * (2 ** (attempt - 1)), maxDelayMs), signal);
        continue;
      }
      if (Buffer.byteLength(body, "utf8") > MAX_RESPONSE_BYTES) {
        const error = new Error("GitHub evidence API response exceeded 1 MiB");
        error.name = "GitHubEvidenceResponseLimitError";
        throw error;
      }
      try { return JSON.parse(body); }
      catch {
        const error = new Error("GitHub evidence API returned invalid JSON");
        error.name = "GitHubEvidenceJsonError";
        throw error;
      }
    }
    throw new Error("GitHub evidence retry budget was exhausted");
  }

  async function fetchCommit(repository, sha, signal) {
    const [owner, name] = repositoryParts(repository);
    const data = await getJson("/repos/" + owner + "/" + name + "/commits/" + encodeURIComponent(sha), signal);
    if (typeof data.sha !== "string" || data.sha.toLowerCase() !== sha.toLowerCase()) return null;
    const expectedPrefix = "https://github.com/" + repository + "/commit/" + sha;
    if (typeof data.html_url !== "string" || !data.html_url.startsWith(expectedPrefix)) return null;
    return { repository, sha: data.sha };
  }

  async function branchContainsCommit(repository, branch, sha, signal) {
    const [owner, name] = repositoryParts(repository);
    const base = encodeURIComponent(sha);
    const head = encodeURIComponent(required(branch, "branch", 256));
    const data = await getJson(
      "/repos/" + owner + "/" + name + "/compare/" + base + "..." + head,
      signal
    );
    const mergeBase = data.merge_base_commit?.sha;
    return typeof mergeBase === "string" &&
      mergeBase.toLowerCase() === sha.toLowerCase() &&
      ["ahead", "identical"].includes(data.status);
  }

  async function fetchWorkflowRun(repository, runId, signal) {
    const [owner, name] = repositoryParts(repository);
    const data = await getJson(
      "/repos/" + owner + "/" + name + "/actions/runs/" + encodeURIComponent(String(runId)),
      signal
    );
    const fullName = data.repository?.full_name;
    if (typeof fullName !== "string") return null;
    return {
      repository: fullName,
      runId: data.id,
      workflow: data.name,
      headSha: data.head_sha,
      status: data.status,
      conclusion: data.conclusion
    };
  }

  async function fetchWorkflowJobs(repository, runId, signal) {
    const [owner, name] = repositoryParts(repository);
    const jobs = [];
    let totalCount = null;
    for (let page = 1; page <= MAX_JOB_PAGES; page += 1) {
      const data = await getJson(
        "/repos/" + owner + "/" + name + "/actions/runs/" +
        encodeURIComponent(String(runId)) + "/jobs?per_page=" + PAGE_SIZE + "&page=" + page,
        signal
      );
      if (!Array.isArray(data.jobs) || !Number.isSafeInteger(data.total_count) || data.total_count < 0) {
        throw new Error("GitHub evidence jobs response has an invalid shape");
      }
      totalCount = data.total_count;
      jobs.push(...data.jobs.map(job => ({
        status: job.status,
        conclusion: job.conclusion
      })));
      if (jobs.length >= totalCount) return jobs;
      if (data.jobs.length === 0) break;
    }
    if (totalCount === 0) return [];
    const error = new Error("GitHub evidence jobs pagination was incomplete or exceeded the safety limit");
    error.name = "GitHubEvidencePaginationError";
    throw error;
  }

  return Object.freeze({
    fetchCommit,
    branchContainsCommit,
    fetchWorkflowRun,
    fetchWorkflowJobs
  });
}
