package org.yggdrasil.scheduler;

import java.time.ZonedDateTime;

/** Pure wall-clock eligibility rules for scheduled and manual checks. */
public final class SchedulePolicy {
    private SchedulePolicy() {}

    public static boolean shouldRun(String days, int hour, int minute,
                                    String lastDate, ZonedDateTime now,
                                    boolean forced) {
        if (forced) return true;
        if (days == null || now == null) return false;

        String selectedDays = "," + days + ",";
        int today = now.getDayOfWeek().getValue();
        if (!selectedDays.contains("," + today + ",")) return false;

        if (now.getHour() < hour ||
                (now.getHour() == hour && now.getMinute() < minute)) {
            return false;
        }

        return !now.toLocalDate().toString().equals(lastDate);
    }
}
