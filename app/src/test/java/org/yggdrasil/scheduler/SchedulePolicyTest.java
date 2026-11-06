package org.yggdrasil.scheduler;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.Test;

public class SchedulePolicyTest {
    private static final String EVERY_DAY = "1,2,3,4,5,6,7";

    @Test public void manualCheckIgnoresDayTimeAndLastDate() {
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 4, 8, 0, 0, 0,
                ZoneId.of("America/New_York"));
        assertTrue(SchedulePolicy.shouldRun("1", 9, 0,
                "2026-11-04", now, true));
    }

    @Test public void scheduledCheckDoesNotRunBeforeChosenTime() {
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 4, 8, 59, 0, 0,
                ZoneId.of("America/New_York"));
        assertFalse(SchedulePolicy.shouldRun(EVERY_DAY, 9, 0,
                null, now, false));
    }

    @Test public void scheduledCheckRunsAtChosenTime() {
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 4, 9, 0, 0, 0,
                ZoneId.of("America/New_York"));
        assertTrue(SchedulePolicy.shouldRun(EVERY_DAY, 9, 0,
                null, now, false));
    }

    @Test public void scheduledCheckRunsAfterChosenTime() {
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 4, 9, 1, 0, 0,
                ZoneId.of("America/New_York"));
        assertTrue(SchedulePolicy.shouldRun(EVERY_DAY, 9, 0,
                null, now, false));
    }

    @Test public void scheduledCheckRunsOnlyOncePerLocalDate() {
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 4, 10, 0, 0, 0,
                ZoneId.of("America/New_York"));
        assertFalse(SchedulePolicy.shouldRun(EVERY_DAY, 9, 0,
                "2026-11-04", now, false));
    }

    @Test public void scheduledCheckRespectsSelectedWeekdays() {
        ZonedDateTime wednesday = ZonedDateTime.of(2026, 11, 4, 10, 0, 0, 0,
                ZoneId.of("America/New_York"));
        assertFalse(SchedulePolicy.shouldRun("1,2,4,5,6", 9, 0,
                null, wednesday, false));
    }

    @Test public void wallClockScheduleSurvivesSpringDstJump() {
        ZoneId zone = ZoneId.of("America/New_York");
        ZonedDateTime beforeJump = ZonedDateTime.of(2026, 3, 8, 1, 59, 0, 0, zone);
        ZonedDateTime afterJump = ZonedDateTime.of(2026, 3, 8, 3, 0, 0, 0, zone);
        assertFalse(SchedulePolicy.shouldRun(EVERY_DAY, 2, 30,
                null, beforeJump, false));
        assertTrue(SchedulePolicy.shouldRun(EVERY_DAY, 2, 30,
                null, afterJump, false));
    }
}
