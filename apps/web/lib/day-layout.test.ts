import { describe, expect, it } from "vitest";
import { findClashes, layoutLanes, ruledHours } from "./day-layout";
import { addDays, hm, lapse, weekRange, weekStart } from "./format";

const H = 3_600_000;
const day = new Date(2026, 9, 3).getTime(); // a Saturday, local midnight
const at = (h: number) => day + h * H;

describe("layoutLanes", () => {
  it("gives a lone entry the whole width", () => {
    const [only] = layoutLanes([{ id: "a", start: at(9), end: at(10) }]);
    expect(only).toMatchObject({ lane: 0, lanes: 1 });
  });

  it("sets two overlapping entries side by side", () => {
    const out = layoutLanes([
      { id: "check-in", start: at(9.5), end: at(10.25) },
      { id: "accountant", start: at(10), end: at(10.75) },
    ]);
    expect(out.map((e) => [e.id, e.lane, e.lanes])).toEqual([
      ["check-in", 0, 2],
      ["accountant", 1, 2],
    ]);
  });

  it("does not treat back-to-back entries as a clash", () => {
    const out = layoutLanes([
      { id: "a", start: at(9), end: at(10) },
      { id: "b", start: at(10), end: at(11) },
    ]);
    expect(out.every((e) => e.lanes === 1)).toBe(true);
  });

  it("reuses a freed lane and keeps one lane count across a chained cluster", () => {
    // a overlaps b, b overlaps c, a and c do not touch: two lanes, not three.
    const out = layoutLanes([
      { id: "a", start: at(9), end: at(10) },
      { id: "b", start: at(9.5), end: at(11) },
      { id: "c", start: at(10.5), end: at(12) },
    ]);
    expect(out.map((e) => [e.id, e.lane, e.lanes])).toEqual([
      ["a", 0, 2],
      ["b", 1, 2],
      ["c", 0, 2],
    ]);
  });

  it("starts a fresh cluster after a gap", () => {
    const out = layoutLanes([
      { id: "a", start: at(9), end: at(10) },
      { id: "b", start: at(9), end: at(10) },
      { id: "c", start: at(14), end: at(15) },
    ]);
    expect(out.find((e) => e.id === "c")).toMatchObject({ lane: 0, lanes: 1 });
  });
});

describe("ruledHours", () => {
  it("rules the working window when nothing falls outside it", () => {
    expect(ruledHours(day, [{ start: at(10), end: at(11) }], { startMinute: 540, endMinute: 1050 })).toEqual({ from: 9, to: 18 });
  });

  it("widens to whole hours around an early or late entry", () => {
    expect(ruledHours(day, [{ start: at(7.5), end: at(8) }, { start: at(19), end: at(20.25) }])).toEqual({ from: 7, to: 21 });
  });

  it("clamps entries that spill over midnight to the page", () => {
    expect(ruledHours(day, [{ start: at(-2), end: at(1) }, { start: at(23), end: at(26) }])).toEqual({ from: 0, to: 24 });
  });
});

describe("local calendar arithmetic", () => {
  it("starts the week on the local Monday", () => {
    const monday = weekStart(at(15));
    expect(new Date(monday).getDay()).toBe(1);
    expect(new Date(monday).getHours()).toBe(0);
    expect(addDays(monday, 5)).toBe(day);
  });

  it("treats Sunday as the end of its week, not the start of the next", () => {
    const sunday = addDays(day, 1);
    expect(weekStart(sunday)).toBe(weekStart(day));
  });

  it("names a week that crosses a month and one that does not", () => {
    expect(weekRange(new Date(2026, 8, 28).getTime(), "en-GB")).toMatch(/^28 September\s–\s4 October$/);
    expect(weekRange(new Date(2026, 9, 5).getTime(), "en-GB")).toMatch(/^5\s*–\s*11 October$/);
  });

  it("says minutes the way a person would", () => {
    expect([hm(30), hm(60), hm(285), hm(-5)]).toEqual(["30m", "1h", "4h 45m", "0m"]);
  });
});

describe("lapse", () => {
  it("switches to days past a day, in halves", () => {
    expect([lapse(45), lapse(210), lapse(2880), lapse(-2160), lapse(1440)]).toEqual(["45m", "3h 30m", "2 days", "1½ days", "1 day"]);
  });
});

describe("findClashes", () => {
  const at = (h: number, m = 0) => h * 60 + m;
  it("pairs entries that overlap and says for how long, and ignores ones that only touch", () => {
    const review = { id: "review", start: at(14), end: at(15) };
    const dentist = { id: "dentist", start: at(14, 30), end: at(15, 15) };
    const standup = { id: "standup", start: at(15, 15), end: at(15, 45) };
    expect(findClashes([standup, dentist, review])).toEqual([{ first: review, second: dentist, overlapMs: 30 }]);
  });

  it("finds every pair when three overlap at once", () => {
    const a = { start: 0, end: 10 };
    const b = { start: 2, end: 6 };
    const c = { start: 4, end: 12 };
    expect(findClashes([a, b, c]).map((x) => x.overlapMs)).toEqual([4, 6, 2]);
  });
});
