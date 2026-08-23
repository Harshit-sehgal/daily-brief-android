import type { WorkSchedule, WorkWindow } from "./types";

export const WEEK_DAYS = [
  { dayOfWeek: 1, label: "Sunday" },
  { dayOfWeek: 2, label: "Monday" },
  { dayOfWeek: 3, label: "Tuesday" },
  { dayOfWeek: 4, label: "Wednesday" },
  { dayOfWeek: 5, label: "Thursday" },
  { dayOfWeek: 6, label: "Friday" },
  { dayOfWeek: 7, label: "Saturday" },
] as const;

export function minutesToTime(minutes: number | undefined): string {
  if (minutes == null) return "";
  return `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;
}

export function timeToMinutes(value: string): number | null {
  if (!/^\d{2}:\d{2}$/.test(value)) return null;
  const [hours, minutes] = value.split(":").map(Number);
  return hours <= 23 && minutes <= 59 ? hours * 60 + minutes : null;
}

export function weeklyWindows(schedule: WorkSchedule, dayOfWeek: number): WorkWindow[] {
  return schedule.windows.filter((window) => (window.kind ?? "weekly") === "weekly" && window.dayOfWeek === dayOfWeek);
}

export function replaceWeeklyWindow(
  schedule: WorkSchedule,
  dayOfWeek: number,
  startMinute: number | null,
  endMinute: number | null,
): WorkSchedule {
  const existing = weeklyWindows(schedule, dayOfWeek);
  const other = schedule.windows.filter(
    (window) => !((window.kind ?? "weekly") === "weekly" && window.dayOfWeek === dayOfWeek),
  );
  if (startMinute == null || endMinute == null) return { ...schedule, windows: other };
  const first = existing[0] ?? {
    id: `${schedule.id}-weekly-${dayOfWeek}-1`,
    kind: "weekly" as const,
    dayOfWeek,
    localDate: null,
    startMinute,
    endMinute,
    isClosed: false,
    rank: dayOfWeek,
  };
  return {
    ...schedule,
    windows: [
      ...other,
      { ...first, kind: "weekly", dayOfWeek, localDate: null, startMinute, endMinute, isClosed: false },
      ...existing.slice(1),
    ],
  };
}
