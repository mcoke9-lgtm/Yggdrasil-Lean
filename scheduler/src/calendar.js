const formatterCache = new Map();

function formatter(timeZone) {
  if (!formatterCache.has(timeZone)) {
    formatterCache.set(timeZone, new Intl.DateTimeFormat("en-CA", {
      timeZone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
      weekday: "short"
    }));
  }
  return formatterCache.get(timeZone);
}

function localParts(date, timeZone) {
  const values = Object.fromEntries(formatter(timeZone).formatToParts(date)
    .filter(part => part.type !== "literal")
    .map(part => [part.type, part.value]));
  return {
    date: `${values.year}-${values.month}-${values.day}`,
    hour: Number(values.hour),
    minute: Number(values.minute),
    weekday: values.weekday
  };
}

const WEEKDAYS = { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 };

export function validateCalendarSchedule(schedule) {
  if (!schedule || schedule.kind !== "calendar") throw new TypeError("A calendar schedule is required");
  if (!["daily", "weekly"].includes(schedule.frequency)) throw new TypeError("Calendar frequency must be daily or weekly");
  if (!Number.isInteger(schedule.hour) || schedule.hour < 0 || schedule.hour > 23) throw new RangeError("hour must be 0–23");
  if (!Number.isInteger(schedule.minute) || schedule.minute < 0 || schedule.minute > 59) throw new RangeError("minute must be 0–59");
  if (typeof schedule.timeZone !== "string" || !schedule.timeZone) throw new TypeError("An IANA timeZone is required");
  try { formatter(schedule.timeZone).format(new Date()); }
  catch { throw new TypeError("timeZone must be a supported IANA timezone"); }
  if (schedule.frequency === "weekly") {
    if (!Array.isArray(schedule.daysOfWeek) || schedule.daysOfWeek.length < 1 ||
        schedule.daysOfWeek.some(day => !Number.isInteger(day) || day < 0 || day > 6) ||
        new Set(schedule.daysOfWeek).size !== schedule.daysOfWeek.length) {
      throw new TypeError("Weekly schedules require unique daysOfWeek values from 0 (Sunday) to 6 (Saturday)");
    }
  }
  return {
    kind: "calendar",
    frequency: schedule.frequency,
    hour: schedule.hour,
    minute: schedule.minute,
    timeZone: schedule.timeZone,
    ...(schedule.frequency === "weekly" ? { daysOfWeek: [...schedule.daysOfWeek].sort() } : {})
  };
}

// Minute-resolution wall-clock matching handles DST without assuming a fixed UTC offset.
// A nonexistent spring-forward time is skipped. If a fall-back time occurs twice, only
// the first occurrence is eligible for that local calendar date.
export function nextCalendarOccurrence(schedule, after) {
  const afterDate = new Date(after);
  if (!Number.isFinite(afterDate.getTime())) throw new TypeError("A valid reference date is required");
  const afterLocal = localParts(afterDate, schedule.timeZone);
  const alreadyAtScheduledTime = afterLocal.hour === schedule.hour && afterLocal.minute === schedule.minute;
  const firstMinute = Math.floor(afterDate.getTime() / 60000) * 60000 + 60000;
  const limit = firstMinute + 9 * 24 * 60 * 60 * 1000;
  for (let timestamp = firstMinute; timestamp <= limit; timestamp += 60000) {
    const candidate = new Date(timestamp);
    const parts = localParts(candidate, schedule.timeZone);
    if (parts.hour !== schedule.hour || parts.minute !== schedule.minute) continue;
    if (alreadyAtScheduledTime && parts.date === afterLocal.date) continue;
    if (schedule.frequency === "weekly" && !schedule.daysOfWeek.includes(WEEKDAYS[parts.weekday])) continue;
    return candidate.toISOString();
  }
  throw new Error("Could not find the next calendar occurrence within nine days");
}
