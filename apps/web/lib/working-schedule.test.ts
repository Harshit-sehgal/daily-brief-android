import { describe, expect, it } from "vitest";
import {
  minutesToTime,
  replaceWeeklyWindow,
  timeToMinutes,
  weeklyWindows,
} from "./working-schedule";
import type { WorkSchedule } from "./types";

const schedule: WorkSchedule = {
  id: "schedule-1",
  name: "Default working week",
  timeZoneId: "Asia/Kolkata",
  isDefault: true,
  minimumChunkMinutes: 30,
  maximumChunkMinutes: 120,
  bufferMinutes: 0,
  rank: 0,
  windows: [
    { id: "monday", kind: "weekly", dayOfWeek: 2, startMinute: 540, endMinute: 1020, isClosed: false, rank: 0 },
    { id: "exception", kind: "date_override", localDate: "2026-08-20", startMinute: 0, endMinute: 0, isClosed: true, rank: 1 },
  ],
};

describe("working schedule editor helpers", () => {
  it("formats and parses wall-clock minutes", () => {
    expect(minutesToTime(540)).toBe("09:00");
    expect(minutesToTime(undefined)).toBe("");
    expect(timeToMinutes("13:45")).toBe(825);
    expect(timeToMinutes("24:00")).toBeNull();
    expect(timeToMinutes("noon")).toBeNull();
  });

  it("selects only the weekly windows for a requested calendar day", () => {
    expect(weeklyWindows(schedule, 2).map((window) => window.id)).toEqual(["monday"]);
    expect(weeklyWindows(schedule, 5)).toEqual([]);
  });

  it("replaces one day without discarding date exceptions or other days", () => {
    const updated = replaceWeeklyWindow(schedule, 2, 780, 1080);
    expect(updated.windows).toEqual([
      schedule.windows[1],
      { ...schedule.windows[0], localDate: null, startMinute: 780, endMinute: 1080 },
    ]);
  });

  it("creates a new weekly window and removes a closed day", () => {
    const added = replaceWeeklyWindow(schedule, 5, 600, 900);
    expect(weeklyWindows(added, 5)).toHaveLength(1);
    const removed = replaceWeeklyWindow(added, 5, null, 900);
    expect(weeklyWindows(removed, 5)).toEqual([]);
    expect(removed.windows.some((window) => window.kind === "date_override")).toBe(true);
  });
});
