package org.yggdrasil.scheduler.builder;

import android.content.Context;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

/** Single WorkManager entry point for authorized Character assignments. */
public final class CharacterAssignmentWorkScheduler {
    private static final String UNIQUE_WORK = "yggdrasil-character-assignment-dispatch";

    private CharacterAssignmentWorkScheduler() {}

    /** Append one bounded dispatch invocation; duplicate queue notifications remain serialized. */
    public static void enqueue(Context context) {
        OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(CharacterAssignmentDispatchWorker.class).build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }
}
