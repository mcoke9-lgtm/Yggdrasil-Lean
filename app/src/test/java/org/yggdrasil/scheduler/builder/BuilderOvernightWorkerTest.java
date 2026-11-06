package org.yggdrasil.scheduler.builder;

import static org.junit.Assert.assertEquals;

import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class BuilderOvernightWorkerTest {
    @Test public void continuationRetainsConnectedNetworkConstraintAndRunInput() {
        Data input = new Data.Builder().putString("builder_run_id", "run-1").build();

        OneTimeWorkRequest request = BuilderOvernightWorker.continuationRequest(
                input, TimeUnit.MINUTES.toMillis(15));

        assertEquals(NetworkType.CONNECTED,
                request.getWorkSpec().constraints.getRequiredNetworkType());
        assertEquals("run-1", request.getInputData().getString("builder_run_id"));
        assertEquals(TimeUnit.MINUTES.toMillis(15), request.getWorkSpec().initialDelay);
    }
}
