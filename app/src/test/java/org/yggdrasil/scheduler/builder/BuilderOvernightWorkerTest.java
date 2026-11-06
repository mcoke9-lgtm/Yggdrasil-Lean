package org.yggdrasil.scheduler.builder;

import static org.junit.Assert.assertEquals;

import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import android.content.Context;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BuilderOvernightWorkerTest {
    @Test public void foregroundStartupFailureMarksOnlyActiveRunInterrupted() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("builder_overnight_runs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        BuilderOvernightRunStore store = new BuilderOvernightRunStore(context);
        store.beginOrRecover("startup-run", "thread", "task", 1800000000000L);

        BuilderOvernightWorker.markStartupFailureIfRunning(
                store, "startup-run", new IllegalStateException("notification setup failed"),
                1800000001000L);

        assertEquals("INTERRUPTED", store.read("startup-run").optString("status"));
    }

    @Test public void foregroundStartupFailureDoesNotOverwriteTerminalRun() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("builder_overnight_runs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        BuilderOvernightRunStore store = new BuilderOvernightRunStore(context);
        store.beginOrRecover("terminal-run", "thread", "task", 1800000000000L);
        store.setStatus("terminal-run", "CANCELLED", "user stopped", 1800000000500L);

        BuilderOvernightWorker.markStartupFailureIfRunning(
                store, "terminal-run", new IllegalStateException("late startup error"),
                1800000001000L);

        assertEquals("CANCELLED", store.read("terminal-run").optString("status"));
    }

    @Test public void continuationRetainsConnectedNetworkConstraintAndRunInput() {
        Data input = new Data.Builder().putString("builder_run_id", "run-1").build();

        OneTimeWorkRequest request = BuilderOvernightWorker.continuationRequest(
                input, TimeUnit.MINUTES.toMillis(15));

        assertEquals(NetworkType.CONNECTED,
                request.getWorkSpec().constraints.getRequiredNetworkType());
        assertEquals("run-1", request.getWorkSpec().input.getString("builder_run_id"));
        assertEquals(TimeUnit.MINUTES.toMillis(15), request.getWorkSpec().initialDelay);
    }
}
