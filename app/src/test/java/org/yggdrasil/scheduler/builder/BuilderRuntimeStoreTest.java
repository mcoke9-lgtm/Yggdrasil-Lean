package org.yggdrasil.scheduler.builder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import android.content.Context;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BuilderRuntimeStoreTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(BuilderRuntimeStore.DATABASE_NAME);
    }

    @After public void tearDown() {
        context.deleteDatabase(BuilderRuntimeStore.DATABASE_NAME);
    }

    @Test public void runtimeIdentityIsCreatedAndPersistedAcrossStoreInstances() {
        BuilderRuntimeStore firstStore = new BuilderRuntimeStore(context);
        BuilderRuntimeStore.NodeIdentity first = firstStore.getOrCreateIdentity();
        firstStore.close();

        BuilderRuntimeStore afterRestart = new BuilderRuntimeStore(context);
        BuilderRuntimeStore.NodeIdentity restored = afterRestart.getOrCreateIdentity();
        afterRestart.close();

        assertNotNull(first.runtimeId);
        assertNotEquals("", first.runtimeId);
        assertEquals(first.runtimeId, restored.runtimeId);
        assertEquals(first.createdAtEpochMillis, restored.createdAtEpochMillis);
    }

    @Test public void runtimeIdentityIsUuidShaped() {
        BuilderRuntimeStore store = new BuilderRuntimeStore(context);
        BuilderRuntimeStore.NodeIdentity identity = store.getOrCreateIdentity();
        store.close();
        assertEquals(36, identity.runtimeId.length());
    }
}
