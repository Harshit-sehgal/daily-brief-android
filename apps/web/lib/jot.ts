/*
 * Reading a jotted line the way a person writes one in a margin:
 *
 *     Send the invoice 30m fri #north
 *
 * is a task "Send the invoice", 30 minutes, due by the end of Friday, filed under the
 * project whose name starts with "north". Only the *end* of the line is read for
 * effort and dates — "Plan Monday's stand-up" is a title, not a task due on Monday —
 * while a #tag is explicit enough to be read anywhere. Whatever is not understood
 * stays in the title, so nothing typed is ever lost.
 */

import { addDays, startOfDay } from "./format";

export interface JotProject {
  id: string;
  name: string;
}

export interface Jot {
  title: string;
  effortMinutes?: number;
  /** The last millisecond of the due day, local time: "due Friday" means by the end of it. */
  dueAt?: number;
  project?: JotProject;
  /** A #tag that matched no project; kept in the title and reported so the preview can say so. */
  unknownTag?: string;
}

const WEEKDAYS: Array<[RegExp, number]> = [
  [/^sun(day)?$/, 0],
  [/^mon(day)?$/, 1],
  [/^tue(s|sday)?$/, 2],
  [/^wed(nesday)?$/, 3],
  [/^thu(r|rs|rsday)?$/, 4],
  [/^fri(day)?$/, 5],
  [/^sat(urday)?$/, 6],
];

const MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];

/** "1h30m", "1h", "90m", "1.5h", "45min", "2hrs" — one word, no spaces. */
function effortWord(word: string): number | null {
  const w = word.toLowerCase();
  let m = w.match(/^(\d+(?:[.,]\d+)?)(?:h|hr|hrs|hour|hours)(?:(\d{1,2})(?:m|min|mins)?)?$/);
  if (m) {
    const hours = Number(m[1].replace(",", "."));
    const minutes = m[2] ? Number(m[2]) : 0;
    const total = Math.round(hours * 60 + minutes);
    return total > 0 && total <= 24 * 60 ? total : null;
  }
  m = w.match(/^(\d+)(?:m|min|mins|minutes)$/);
  if (m) {
    const total = Number(m[1]);
    return total > 0 && total <= 24 * 60 ? total : null;
  }
  return null;
}

/** "2 hours", "45 min" — a number and a unit written apart. */
function effortPair(num: string, unit: string): number | null {
  if (!/^\d+(?:[.,]\d+)?$/.test(num)) return null;
  return effortWord(`${num}${unit}`);
}

function monthIndex(word: string): number {
  const w = word.toLowerCase().replace(/\.$/, "");
  if (w.length < 3) return -1;
  return MONTHS.findIndex((m) => w.startsWith(m) && m.startsWith(w.slice(0, 3)));
}

/**
 * A weekday word means the soonest such day, today included: "fri" typed on a Friday
 * is today. Deadlines read early rather than late — a task placed too soon can wait,
 * one placed a week late has already been missed.
 */
function nextWeekday(now: number, weekday: number): number {
  const today = startOfDay(now);
  const ahead = (weekday - new Date(today).getDay() + 7) % 7;
  return addDays(today, ahead);
}

/** "12 oct" / "oct 12": the next time that date comes round, this year or next. */
function calendarDate(now: number, day: number, month: number): number | null {
  if (day < 1 || day > 31 || month < 0) return null;
  const today = startOfDay(now);
  const year = new Date(today).getFullYear();
  for (const y of [year, year + 1]) {
    const d = new Date(y, month, day);
    if (d.getMonth() !== month) return null; // 31 Feb and friends
    if (d.getTime() >= today) return d.getTime();
  }
  return null;
}

/** One date word (or two, for "12 oct") read from `words` ending at `end`. */
function dateAt(words: string[], end: number, now: number): { day: number; used: number } | null {
  const w = words[end].toLowerCase().replace(/[.,]$/, "");
  if (w === "today" || w === "tonight") return { day: startOfDay(now), used: 1 };
  if (w === "tomorrow" || w === "tmrw" || w === "tmr") return { day: addDays(now, 1), used: 1 };
  for (const [re, weekday] of WEEKDAYS) if (re.test(w)) return { day: nextWeekday(now, weekday), used: 1 };
  if (end >= 1) {
    const prev = words[end - 1].toLowerCase().replace(/[.,]$/, "");
    // "12 oct"
    if (/^\d{1,2}$/.test(prev) && monthIndex(w) >= 0) {
      const day = calendarDate(now, Number(prev), monthIndex(w));
      if (day !== null) return { day, used: 2 };
    }
    // "oct 12"
    if (/^\d{1,2}$/.test(w) && monthIndex(prev) >= 0) {
      const day = calendarDate(now, Number(w), monthIndex(prev));
      if (day !== null) return { day, used: 2 };
    }
  }
  return null;
}

const CONNECTORS = new Set(["by", "due", "on", "for", "before"]);

function normalise(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]/g, "");
}

export function parseJot(text: string, now: number, projects: readonly JotProject[] = []): Jot {
  const raw = text.replace(/\s+/g, " ").trim();
  let words = raw.split(" ").filter(Boolean);
  const jot: Jot = { title: raw };
  if (words.length === 0) return { title: "" };

  // #tags, anywhere: the first one that names a project files the task there.
  const kept: string[] = [];
  for (const word of words) {
    const tag = word.match(/^#([\p{L}\p{N}_-]+)$/u);
    if (tag && !jot.project) {
      const want = normalise(tag[1]);
      const match = want ? projects.find((p) => normalise(p.name).startsWith(want)) : undefined;
      if (match) {
        jot.project = match;
        continue;
      }
      jot.unknownTag ??= word;
    }
    kept.push(word);
  }
  words = kept;

  // Effort and dates, peeled off the end of the line, in any order.
  let end = words.length - 1;
  while (end >= 1) {
    const word = words[end];
    const lone = effortWord(word);
    if (lone !== null && jot.effortMinutes === undefined) {
      jot.effortMinutes = lone;
      end -= 1;
      continue;
    }
    const pair = end >= 2 ? effortPair(words[end - 1], word) : null;
    if (pair !== null && jot.effortMinutes === undefined) {
      jot.effortMinutes = pair;
      end -= 2;
      continue;
    }
    const date = jot.dueAt === undefined ? dateAt(words, end, now) : null;
    if (date && end - date.used >= 0) {
      jot.dueAt = addDays(date.day, 1) - 1;
      end -= date.used;
      if (end >= 1 && CONNECTORS.has(words[end].toLowerCase())) end -= 1;
      continue;
    }
    // A trailing connector left behind by an earlier token: "call mum by".
    if (CONNECTORS.has(word.toLowerCase()) && (jot.dueAt !== undefined || jot.effortMinutes !== undefined)) {
      end -= 1;
      continue;
    }
    break;
  }

  const title = words.slice(0, end + 1).join(" ").replace(/[\s,;:–—-]+$/, "").trim();
  // The first word always stays as the title, so a line that was nothing but tokens
  // ("2h fri") is left as typed rather than becoming a task called "2h".
  const onlyTokens = end === 0 && (effortWord(words[0]) !== null || dateAt(words, 0, now) !== null);
  if (!title || onlyTokens) {
    delete jot.effortMinutes;
    delete jot.dueAt;
    jot.title = jot.project ? raw.replace(/\s*#\S+/, "").trim() || raw : raw;
    return jot;
  }
  jot.title = title;
  return jot;
}

/** Several jotted lines at once — a pasted list, or text dropped on the inbox. */
export function jotLines(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((line) => line.replace(/^\s*(?:[-*•·◦▪]|\d+[.)]|\[[ xX]?\])\s*/, "").trim())
    .filter((line) => line.length > 0);
}
