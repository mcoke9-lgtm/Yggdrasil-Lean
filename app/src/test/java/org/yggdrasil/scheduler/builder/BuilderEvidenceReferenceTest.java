package org.yggdrasil.scheduler.builder;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class BuilderEvidenceReferenceTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private static final class FakeGateway implements BuilderEvidenceReference.EvidenceGateway {
        BuilderEvidenceReference.CommitEvidence commit;
        boolean onBranch = true;
        BuilderEvidenceReference.WorkflowRunEvidence run;
        List<BuilderEvidenceReference.JobEvidence> jobs = Arrays.asList(
                new BuilderEvidenceReference.JobEvidence("completed", "success"));

        @Override public BuilderEvidenceReference.CommitEvidence fetchCommit(String repository, String sha) {
            return commit;
        }
        @Override public boolean branchContainsCommit(String repository, String branch, String sha) {
            return onBranch;
        }
        @Override public BuilderEvidenceReference.WorkflowRunEvidence fetchWorkflowRun(String repository, long runId) {
            return run;
        }
        @Override public List<BuilderEvidenceReference.JobEvidence> fetchWorkflowJobs(String repository, long runId) {
            return jobs;
        }
    }

    @Test public void parsesAndVerifiesCommitOnlyWhenRepositoryShaAndBranchMatch() {
        BuilderEvidenceReference.Reference ref = BuilderEvidenceReference.parse(
                "github+commit://github.com/acme/project/" + SHA + "?branch=feature%2Fcorrection");
        assertEquals(BuilderEvidenceReference.Kind.COMMIT, ref.kind);
        assertEquals("acme/project", ref.repository);
        assertEquals("feature/correction", ref.branch);

        FakeGateway gateway = new FakeGateway();
        gateway.commit = new BuilderEvidenceReference.CommitEvidence("acme/project", SHA);
        assertTrue(BuilderEvidenceReference.verify(ref, gateway).verified);
        gateway.onBranch = false;
        assertFalse(BuilderEvidenceReference.verify(ref, gateway).verified);
        gateway.commit = new BuilderEvidenceReference.CommitEvidence("other/project", SHA);
        assertFalse(BuilderEvidenceReference.verify(ref, gateway).verified);
    }

    @Test public void verifiesOnlySuccessfulRunForExactWorkflowAndTestedSha() {
        BuilderEvidenceReference.Reference ref = BuilderEvidenceReference.parse(
                "github+actions://github.com/acme/project/runs/12345?workflow=android-unit-tests&sha=" + SHA);
        FakeGateway gateway = new FakeGateway();
        gateway.run = new BuilderEvidenceReference.WorkflowRunEvidence(
                "acme/project", 12345L, "android-unit-tests", SHA, "completed", "success");
        assertTrue(BuilderEvidenceReference.verify(ref, gateway).verified);

        gateway.run = new BuilderEvidenceReference.WorkflowRunEvidence(
                "acme/project", 12345L, "android-unit-tests", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "completed", "success");
        assertFalse(BuilderEvidenceReference.verify(ref, gateway).verified);
    }

    @Test public void rejectsFailedOrIncompleteJobsAndUnavailableEvidence() {
        BuilderEvidenceReference.Reference ref = BuilderEvidenceReference.parse(
                "github+actions://github.com/acme/project/runs/12345?workflow=android-unit-tests&sha=" + SHA);
        FakeGateway gateway = new FakeGateway();
        gateway.run = new BuilderEvidenceReference.WorkflowRunEvidence(
                "acme/project", 12345L, "android-unit-tests", SHA, "completed", "success");
        gateway.jobs = Arrays.asList(
                new BuilderEvidenceReference.JobEvidence("completed", "success"),
                new BuilderEvidenceReference.JobEvidence("completed", "failure"));
        assertFalse(BuilderEvidenceReference.verify(ref, gateway).verified);
        gateway.jobs = Arrays.asList(new BuilderEvidenceReference.JobEvidence("in_progress", null));
        assertFalse(BuilderEvidenceReference.verify(ref, gateway).verified);
        assertFalse(BuilderEvidenceReference.verify(ref, null).verified);
    }

    @Test public void rejectsShortShaUnknownSchemeAndMissingRunIdentity() {
        assertThrows(IllegalArgumentException.class, () -> BuilderEvidenceReference.parse(
                "github+commit://github.com/acme/project/1234?branch=main"));
        assertThrows(IllegalArgumentException.class, () -> BuilderEvidenceReference.parse(
                "https://github.com/acme/project/commit/" + SHA));
        assertThrows(IllegalArgumentException.class, () -> BuilderEvidenceReference.parse(
                "github+actions://github.com/acme/project/runs/0?workflow=ci&sha=" + SHA));
    }
}
