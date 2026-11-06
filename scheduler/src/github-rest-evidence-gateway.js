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

/**
 * Create a read-only GitHub REST evidence gateway.
 *
 * The host supplies readSecret(secretRef); the token is held only in this
 * closure. All API operations are GET requests and accept AbortSignal.
 */
export async function createEvidenceGateway({
  readSecret,
  secretRef,
  fetchImpl = globalThis.fetch
} = {}) {
  if (typeof readSecret !== "function") throw new TypeError("readSecret(secretRef) is required");
  const reference = required(secretRef, "evidence gateway secretRef", 256);
  if (typeof fetchImpl !== "function") throw new TypeError("A Fetch-compatible transport is required");
  const token = await readSecret(reference);
  if (typeof token !== "string" || !token.trim()) {
    throw new Error("Evidence gateway secret resolver returned no token");
  }

  async function getJson(path, signal) {
    const response = await fetchImpl(API_ROOT + path, {
      method: "GET",
      headers: {
        Accept: "application/vnd.github+json",
        Authorization: "Bearer " + token.trim(),
        "X-GitHub-Api-Version": "2022-11-28"
      },
      signal,
      redirect: "error"
    });
    if (!response || typeof response.status !== "number" || !response.ok) {
      const error = new Error("GitHub evidence API request failed");
      error.name = "GitHubEvidenceHttpError";
      throw error;
    }
    const body = await response.text();
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
