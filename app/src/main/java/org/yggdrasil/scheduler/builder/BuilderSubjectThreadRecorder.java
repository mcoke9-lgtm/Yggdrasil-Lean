package org.yggdrasil.scheduler.builder;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Records a completed routing decision without copying or modifying conversation turns. */
public final class BuilderSubjectThreadRecorder {
    private BuilderSubjectThreadRecorder() { }

    /**
     * Atomically records the subject, matched subheadings, and participants selected
     * for one outgoing round. A null/empty subheading list represents a broad subject.
     */
    public static void record(Context context, String threadId, String subjectId,
                              List<String> subheadingIds, List<BuilderRole> participants) {
        if (context == null) throw new IllegalArgumentException("context is required");
        if (threadId == null || threadId.trim().isEmpty())
            throw new IllegalArgumentException("threadId is required");
        if (subjectId == null || subjectId.trim().isEmpty())
            throw new IllegalArgumentException("subjectId is required");
        if (participants == null || participants.isEmpty())
            throw new IllegalArgumentException("participants are required");

        long timestamp = System.currentTimeMillis();
        List<BuilderSubjectThreadIndex.Event> events = new ArrayList<>();
        events.add(new BuilderSubjectThreadIndex.Event(UUID.randomUUID().toString(),
                threadId, subjectId, BuilderSubjectThreadIndex.EventType.SUBJECT_ATTACHED,
                null, null, timestamp));
        if (subheadingIds != null) {
            for (String subheadingId : new LinkedHashSet<>(subheadingIds)) {
                if (subheadingId == null || subheadingId.trim().isEmpty())
                    throw new IllegalArgumentException("subheading IDs must not be blank");
                events.add(new BuilderSubjectThreadIndex.Event(UUID.randomUUID().toString(),
                        threadId, subjectId,
                        BuilderSubjectThreadIndex.EventType.SUBHEADING_ATTACHED,
                        subheadingId, null, timestamp));
            }
        }
        Set<BuilderRole> uniqueParticipants = new LinkedHashSet<>(participants);
        if (uniqueParticipants.contains(null))
            throw new IllegalArgumentException("participants must not contain null");
        for (BuilderRole participant : uniqueParticipants) {
            events.add(new BuilderSubjectThreadIndex.Event(UUID.randomUUID().toString(),
                    threadId, subjectId,
                    BuilderSubjectThreadIndex.EventType.PARTICIPANT_SELECTED,
                    null, participant.name(), timestamp));
        }

        Context app = context.getApplicationContext();
        BuilderRecoveryGenerationBarrier.withLock(app, () -> {
            BuilderRecoveryActivation.ActivePair active = BuilderRecoveryActivation.getActive(app);
            if (active == null) {
                BuilderSubjectThreadIndex index = new BuilderSubjectThreadIndex(app);
                try {
                    index.appendAll(events);
                } finally {
                    index.close();
                }
            } else {
                BuilderRecoveryOverlayAccess.withActive(app, session -> {
                    session.appendAll(events);
                    return null;
                });
            }
            return null;
        });
    }
}
