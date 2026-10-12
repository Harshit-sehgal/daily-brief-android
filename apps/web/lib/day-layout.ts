/*
 * Geometry for the ruled day page: which hours to rule, and how overlapping
 * entries share the width. Pure, so the arithmetic is tested rather than eyeballed.
 */

import { HOUR } from "./format";

export interface Span {
  start: number;
  end: number;
}

export type Laned<T> = T & { lane: number; lanes: number };

/**
 * Overlapping entries sit side by side, like two appointments written next to each
 * other in a paper diary. Entries are grouped into clusters of transitive overlap;
 * inside a cluster each takes the first lane that is free when it starts, and every
 * member shares the cluster's lane count so their widths match. An entry that merely
 * touches another (one ends as the next starts) is not an overlap and keeps full width.
 */
export function layoutLanes<T extends Span>(items: readonly T[]): Laned<T>[] {
  const sorted = [...items].sort((a, b) => a.start - b.start || b.end - a.end);
  const out: Laned<T>[] = [];
  let cluster: Laned<T>[] = [];
  let laneEnds: number[] = [];
  let clusterEnd = -Infinity;

  const close = () => {
    for (const item of cluster) item.lanes = laneEnds.length;
    out.push(...cluster);
    cluster = [];
    laneEnds = [];
  };

  for (const item of sorted) {
    if (cluster.length > 0 && item.start >= clusterEnd) close();
    let lane = laneEnds.findIndex((end) => end <= item.start);
    if (lane < 0) {
      lane = laneEnds.length;
      laneEnds.push(item.end);
    } else {
      laneEnds[lane] = item.end;
    }
    cluster.push({ ...item, lane, lanes: 1 });
    clusterEnd = cluster.length === 1 ? item.end : Math.max(clusterEnd, item.end);
  }
  if (cluster.length > 0) close();
  return out;
}

/**
 * The hours a day page rules: the working window when there is one (or 09–18 when
 * there is not), widened to whole hours until every entry fits. Hours are counted
 * from the day's local midnight, so the result is always within 0..24.
 */
export function ruledHours(
  dayStart: number,
  entries: readonly Span[],
  window?: { startMinute: number; endMinute: number } | null,
): { from: number; to: number } {
  let from = window ? Math.floor(window.startMinute / 60) : 9;
  let to = window ? Math.ceil(window.endMinute / 60) : 18;
  for (const e of entries) {
    const s = Math.max(0, (e.start - dayStart) / HOUR);
    const t = Math.min(24, (e.end - dayStart) / HOUR);
    if (t <= 0 || s >= 24) continue;
    from = Math.min(from, Math.floor(s));
    to = Math.max(to, Math.ceil(t));
  }
  from = Math.max(0, from);
  to = Math.min(24, Math.max(to, from + 1));
  return { from, to };
}

export interface Clash<T> {
  first: T;
  second: T;
  overlapMs: number;
}

/**
 * Every pair of entries that overlap, earliest first. Two that merely touch do not
 * clash. Read from the reader's own day: the server's count is for a UTC day, which in
 * India ends at 05:30 and misses the morning.
 */
export function findClashes<T extends Span>(items: readonly T[]): Clash<T>[] {
  const sorted = [...items].sort((a, b) => a.start - b.start || a.end - b.end);
  const out: Clash<T>[] = [];
  for (let i = 0; i < sorted.length; i++) {
    for (let j = i + 1; j < sorted.length && sorted[j].start < sorted[i].end; j++) {
      out.push({ first: sorted[i], second: sorted[j], overlapMs: Math.min(sorted[i].end, sorted[j].end) - sorted[j].start });
    }
  }
  return out;
}
