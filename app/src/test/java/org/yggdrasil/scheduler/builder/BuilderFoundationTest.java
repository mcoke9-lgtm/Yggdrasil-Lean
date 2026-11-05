package org.yggdrasil.scheduler.builder;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class BuilderFoundationTest {
    @Test public void registryContainsNineUniqueRoles() {
        assertEquals(9, BuilderRegistry.allRoles().size());
        Set<String> names = new HashSet<>();
        for (BuilderRole role : BuilderRegistry.allRoles()) {
            assertTrue(names.add(role.name()));
            assertFalse(role.getResponsibility().trim().isEmpty());
        }
    }

    @Test public void registryFindsDisplayNameAndStableId() {
        assertEquals(BuilderRole.MARI, BuilderRegistry.find("Mari"));
        assertEquals(BuilderRole.MARI, BuilderRegistry.find("MARI"));
        assertEquals(BuilderRole.HODR, BuilderRegistry.find("hodr"));
        assertNull(BuilderRegistry.find("unknown"));
        assertNull(BuilderRegistry.find(null));
    }

    @Test public void messagePreservesEnvelopeFields() {
        BuilderMessage message = new BuilderMessage(1, "m-001", "MARI", "MUNINN",
                1793850000000L, "task-7", "text/plain", "record this decision");
        assertEquals(1, message.getSchemaVersion());
        assertEquals("m-001", message.getMessageId());
        assertEquals("MARI", message.getSender());
        assertEquals("MUNINN", message.getRecipient());
        assertEquals("task-7", message.getCorrelationId());
        assertEquals("text/plain", message.getContentType());
        assertEquals("record this decision", message.getPayload());
    }

    @Test public void messageRejectsMissingIdentityAndInvalidTime() {
        assertThrows(IllegalArgumentException.class, () -> new BuilderMessage(
                1, " ", "MARI", "MUNINN", 1, "task", "text/plain", "hello"));
        assertThrows(IllegalArgumentException.class, () -> new BuilderMessage(
                1, "m", "MARI", "MUNINN", 0, "task", "text/plain", "hello"));
    }

    @Test public void registryCannotBeModified() {
        try {
            BuilderRegistry.allRoles().clear();
            fail("Registry list should be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}