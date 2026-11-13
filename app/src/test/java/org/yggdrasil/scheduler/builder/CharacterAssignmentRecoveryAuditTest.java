package org.yggdrasil.scheduler.builder;

import org.junit.Test;
import static org.junit.Assert.*;

public class CharacterAssignmentRecoveryAuditTest {
    @Test public void canonicalFingerprintIgnoresObjectKeyOrder() {
        assertEquals(
                CharacterAssignmentRecoveryAudit.fingerprintJson("{\"b\":2,\"a\":1}"),
                CharacterAssignmentRecoveryAudit.fingerprintJson("{\"a\":1,\"b\":2}"));
    }

    @Test public void canonicalFingerprintNormalizesEquivalentNumberForms() {
        assertEquals(
                CharacterAssignmentRecoveryAudit.fingerprintJson("{\"value\":1.0}"),
                CharacterAssignmentRecoveryAudit.fingerprintJson("{\"value\":1}"));
    }

    @Test public void canonicalFingerprintPreservesArrayOrder() {
        assertNotEquals(
                CharacterAssignmentRecoveryAudit.fingerprintJson("[1,2]"),
                CharacterAssignmentRecoveryAudit.fingerprintJson("[2,1]"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedPayloadIsRejected() {
        CharacterAssignmentRecoveryAudit.fingerprintJson("{not-json}");
    }
}
