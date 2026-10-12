/*
 * Words and dates as the pages say them.
 *
 * Every day boundary here is the browser's *local* midnight. The planner used to
 * cut its week at UTC midnight while printing local times, so in India an all-day
 * event that starts at local midnight (18:30 UTC the day before) drew on two days
 * and a 02:00 block landed in yesterday's column. Day arithmetic goes through
 * Date's local setters, never `+ n * 86_400_000`, so a DST change cannot shift a
 * day by an hour either.
 */

export const MINUTE = 60_000;
export const HOUR = 60 * MINUTE;

/** Local midnight at the start of the day containing `ms`. */
export function startOfDay(ms: number): number {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** Local midnight `n` calendar days after the day containing `ms`. */
export function addDays(ms: number, n: number): number {
  const d = new Date(startOfDay(ms));
  d.setDate(d.getDate() + n);
  return d.getTime();
}

/** Monday 00:00, local, of the week containing `ms`. */
export function weekStart(ms: number): number {
  const day = new Date(ms).getDay(); // 0 = Sunday
  return addDays(ms, -((day + 6) % 7));
}

export function sameDay(a: number, b: number): boolean {
  return startOfDay(a) === startOfDay(b);
}

/** Minutes as a person would say them: 30m, 1h, 4h 45m. */
export function hm(minutes: number): string {
  const m = Math.max(0, Math.round(minutes));
  const h = Math.floor(m / 60);
  if (h === 0) return `${m}m`;
  return m % 60 === 0 ? `${h}h` : `${h}h ${m % 60}m`;
}

/** 24-hour wall clock, so a column of times lines up and never reads "12:00 AM". */
export function clock(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false });
}

export function weekday(ms: number, style: "long" | "short" = "long"): string {
  return new Date(ms).toLocaleDateString([], { weekday: style });
}

/** "Saturday, 3 October" */
export function longDate(ms: number): string {
  return new Date(ms).toLocaleDateString([], { weekday: "long", day: "numeric", month: "long" });
}

/** "3 Oct" */
export function shortDate(ms: number): string {
  return new Date(ms).toLocaleDateString([], { day: "numeric", month: "short" });
}

/** "28 September – 4 October" (or "5–11 October" inside one month), in the reader's
 *  own date order — formatRange knows whether that is "5–11 October" or "October 5–11". */
export function weekRange(start: number, locale?: string, month: "long" | "short" = "long"): string {
  return new Intl.DateTimeFormat(locale, { day: "numeric", month }).formatRange(
    new Date(start),
    new Date(addDays(start, 6)),
  );
}

/** "1 block", "3 blocks". Pass the plural when adding an s is wrong. */
export function count(n: number, one: string, many = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** A sentence-leading count: "One clash", "Three tasks"; numerals past ten. */
export function countWords(n: number, one: string, many = `${one}s`): string {
  const words = ["No", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten"];
  return `${words[n] ?? n} ${n === 1 ? one : many}`;
}

/** A slip in time, in the unit a person would use: "45m", "3h 30m", "2 days", "1½ days". */
export function lapse(minutes: number): string {
  const m = Math.abs(minutes);
  if (m < 24 * 60) return hm(m);
  const halves = Math.round((m / (24 * 60)) * 2);
  const whole = Math.floor(halves / 2);
  const text = halves % 2 === 0 ? String(whole) : whole === 0 ? "½" : `${whole}½`;
  return `${text} ${halves === 2 ? "day" : "days"}`;
}

/** "2026-10-06": the local calendar date, as a key for things kept per day. */
export function dayKey(ms: number): string {
  const d = new Date(ms);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
