/*
 * Freehand ink, as plain geometry: turning the points of a stroke into a smooth path,
 * and deciding which strokes an eraser touches. Kept apart from the sheet that draws
 * them so both can be tested without a browser.
 */

import type { Stroke } from "./types";

/** The sheet's own width in drawing units; its height follows its shape on the page. */
export const SHEET_W = 1000;

/** Drops a point that has barely moved, so a slow hand does not store a thousand of them. */
export function addPoint(points: number[], x: number, y: number, minStep = 3): number[] {
  const n = points.length;
  if (n >= 2 && Math.hypot(x - points[n - 2], y - points[n - 1]) < minStep) return points;
  return [...points, Math.round(x * 10) / 10, Math.round(y * 10) / 10];
}

/**
 * An SVG path through the points, curved through the midpoints so a hand's line reads as
 * a line and not as its samples. One point is a dot: a zero-length line, which round
 * line caps draw as a full stop.
 */
export function strokePath(p: readonly number[]): string {
  if (p.length < 2) return "";
  if (p.length === 2) return `M${p[0]} ${p[1]}l0 0`;
  let d = `M${p[0]} ${p[1]}`;
  for (let i = 2; i < p.length - 2; i += 2) {
    const mx = (p[i] + p[i + 2]) / 2;
    const my = (p[i + 1] + p[i + 3]) / 2;
    d += `Q${p[i]} ${p[i + 1]} ${Math.round(mx * 10) / 10} ${Math.round(my * 10) / 10}`;
  }
  return `${d}L${p[p.length - 2]} ${p[p.length - 1]}`;
}

/** Distance from a point to the segment a–b. */
function toSegment(x: number, y: number, ax: number, ay: number, bx: number, by: number): number {
  const dx = bx - ax;
  const dy = by - ay;
  const len = dx * dx + dy * dy;
  const t = len === 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / len));
  return Math.hypot(x - (ax + t * dx), y - (ay + t * dy));
}

/** The strokes left after an eraser of `radius` has passed over (x, y): a touched stroke goes whole. */
export function erase(strokes: readonly Stroke[], x: number, y: number, radius: number): Stroke[] {
  return strokes.filter(({ p }) => {
    if (p.length === 2) return Math.hypot(x - p[0], y - p[1]) > radius;
    for (let i = 0; i < p.length - 2; i += 2) {
      if (toSegment(x, y, p[i], p[i + 1], p[i + 2], p[i + 3]) <= radius) return false;
    }
    return true;
  });
}
