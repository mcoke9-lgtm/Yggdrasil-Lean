package org.yggdrasil.scheduler.builder;

import java.net.URI;
import java.net.URLDecoder;
import java.util.List;

/**
 * Typed, provider-neutral GitHub evidence references and fail-closed verification.
 *
 * This class deliberately has no network client or credential access. A separately
 * authorized adapter must implement EvidenceGateway; missing or mismatched evidence
 * remains UNVERIFIED.
 */
public final class BuilderEvidenceReference {
    public enum Kind { COMMIT, ACTIONS_RUN }

    public static final class Reference {
        public final Kind kind;
        public final String repository;
        public final String objectId;
        public final String branch;
        public final String workflow;
        public final String expectedSha;

        private Reference(Kind kind, String repository, String objectId,
                          String branch, String workflow, String expectedSha) {
            this.kind = kind;
            this.repository = repository;
            this.objectId = objectId;
            this.branch = branch;
            this.workflow = workflow;
            this.expectedSha = expectedSha;
        }
    }

    public interface EvidenceGateway {
        CommitEvidence fetchCommit(String repository, String sha);
        boolean branchContainsCommit(String repository, String branch, String sha);
        WorkflowRunEvidence fetchWorkflowRun(String repository, long runId);
        List<JobEvidence> fetchWorkflowJobs(String repository, long runId);
    }

    public static final class CommitEvidence {
        public final String repository;
        public final String sha;
        public CommitEvidence(String repository, String sha) {
            this.repository = repository;
            this.sha = sha;
        }
    }

    public static final class WorkflowRunEvidence {
        public final String repository;
        public final long runId;
        public final String workflow;
        public final String headSha;
        public final String status;
        public final String conclusion;
        public WorkflowRunEvidence(String repository, long runId, String workflow,
                                   String headSha, String status, String conclusion) {
            this.repository = repository;
            this.runId = runId;
            this.workflow = workflow;
            this.headSha = headSha;
            this.status = status;
            this.conclusion = conclusion;
        }
    }

    public static final class JobEvidence {
        public final String status;
        public final String conclusion;
        public JobEvidence(String status, String conclusion) {
            this.status = status;
            this.conclusion = conclusion;
        }
    }

    public static final class Verification {
        public final boolean verified;
        public final String reason;
        private Verification(boolean verified, String reason) {
            this.verified = verified;
            this.reason = reason;
        }
        static Verification pass(String reason) { return new Verification(true, reason); }
        static Verification fail(String reason) { return new Verification(false, reason); }
    }

    private BuilderEvidenceReference() {}

    /**
     * Commit: github+commit://github.com/owner/repo/<40-char-sha>?branch=<branch>
     * Run:    github+actions://github.com/owner/repo/runs/<id>?workflow=<name>&sha=<40-char-sha>
     */
    public static Reference parse(String value) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException("Evidence reference is required");
        try {
            URI uri = URI.create(value.trim());
            if (!"github.com".equals(uri.getHost()))
                throw new IllegalArgumentException("Evidence host must be github.com");
            String[] path = uri.getPath().split("/");
            if (path.length < 4 || path[0].length() != 0)
                throw new IllegalArgumentException("Evidence path is malformed");
            String repository = path[1] + "/" + path[2];
            if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
                throw new IllegalArgumentException("Repository identity is malformed");
            java.util.Map<String, String> query = query(uri.getRawQuery());
            if ("github+commit".equals(uri.getScheme())) {
                if (path.length != 4 || !path[3].matches("[0-9a-fA-F]{40}"))
                    throw new IllegalArgumentException("Commit reference requires a full 40-character SHA");
                String branch = required(query.get("branch"), "branch");
                return new Reference(Kind.COMMIT, repository, path[3].toLowerCase(), branch, null, null);
            }
            if ("github+actions".equals(uri.getScheme())) {
                if (path.length != 5 || !"runs".equals(path[3]) || !path[4].matches("[1-9][0-9]*"))
                    throw new IllegalArgumentException("Actions reference requires a numeric workflow run ID");
                String workflow = required(query.get("workflow"), "workflow");
                String sha = required(query.get("sha"), "sha").toLowerCase();
                if (!sha.matches("[0-9a-f]{40}"))
                    throw new IllegalArgumentException("Workflow run reference requires a full tested SHA");
                return new Reference(Kind.ACTIONS_RUN, repository, path[4], null, workflow, sha);
            }
            throw new IllegalArgumentException("Unsupported GitHub evidence reference scheme");
        } catch (IllegalArgumentException malformed) {
            throw malformed;
        } catch (Exception malformed) {
            throw new IllegalArgumentException("Malformed GitHub evidence reference", malformed);
        }
    }

    public static Verification verify(Reference reference, EvidenceGateway gateway) {
        if (reference == null) return Verification.fail("Reference is missing");
        if (gateway == null) return Verification.fail("No authorized evidence gateway is configured");
        try {
            if (reference.kind == Kind.COMMIT) {
                CommitEvidence commit = gateway.fetchCommit(reference.repository, reference.objectId);
                if (commit == null) return Verification.fail("Commit was not found");
                if (!reference.repository.equals(commit.repository)
                        || !reference.objectId.equalsIgnoreCase(commit.sha))
                    return Verification.fail("Fetched commit identity does not match the reference");
                if (!gateway.branchContainsCommit(reference.repository, reference.branch, reference.objectId))
                    return Verification.fail("Commit is not confirmed on the referenced branch");
                return Verification.pass("Commit identity and branch membership confirmed");
            }
            long runId = Long.parseLong(reference.objectId);
            WorkflowRunEvidence run = gateway.fetchWorkflowRun(reference.repository, runId);
            if (run == null) return Verification.fail("Workflow run was not found");
            if (!reference.repository.equals(run.repository) || runId != run.runId)
                return Verification.fail("Workflow run identity does not match the reference");
            if (!reference.workflow.equals(run.workflow))
                return Verification.fail("Workflow name does not match the reference");
            if (!reference.expectedSha.equalsIgnoreCase(run.headSha))
                return Verification.fail("Workflow run tested a different commit SHA");
            if (!"completed".equalsIgnoreCase(run.status) || !"success".equalsIgnoreCase(run.conclusion))
                return Verification.fail("Workflow run did not complete successfully");
            List<JobEvidence> jobs = gateway.fetchWorkflowJobs(reference.repository, runId);
            if (jobs == null || jobs.isEmpty())
                return Verification.fail("Workflow run has no retrievable jobs");
            for (JobEvidence job : jobs) {
                if (job == null || !"completed".equalsIgnoreCase(job.status)
                        || !"success".equalsIgnoreCase(job.conclusion))
                    return Verification.fail("At least one workflow job is missing, incomplete, or unsuccessful");
            }
            return Verification.pass("Workflow, tested SHA, run conclusion, and all jobs confirmed");
        } catch (RuntimeException unavailable) {
            return Verification.fail("Evidence source unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    private static java.util.Map<String, String> query(String raw) {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        if (raw == null || raw.isEmpty()) return values;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            String key;
            String value;
            try {
                key = URLDecoder.decode(parts[0].replace("+", "%2B"), "UTF-8");
                value = parts.length == 2
                        ? URLDecoder.decode(parts[1].replace("+", "%2B"), "UTF-8") : "";
            } catch (java.io.UnsupportedEncodingException impossible) {
                throw new IllegalStateException("UTF-8 is unavailable", impossible);
            }
            if (values.put(key, value) != null)
                throw new IllegalArgumentException("Duplicate evidence query parameter: " + key);
        }
        return values;
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException("Evidence reference requires " + field);
        return value.trim();
    }
}
