import { describe, expect, it } from "vitest";
import { addPoint, erase, strokePath } from "./ink";
import type { Stroke } from "./types";

describe("freehand ink", () => {
  it("keeps a point only once the hand has moved", () => {
    let p = addPoint([], 10, 10);
    p = addPoint(p, 11, 10.5);
    expect(p).toEqual([10, 10]);
    p = addPoint(p, 14.04, 10);
    expect(p).toEqual([10, 10, 14, 10]);
  });

  it("draws one point as a dot and several as a curve through their midpoints", () => {
    expect(strokePath([5, 5])).toBe("M5 5l0 0");
    expect(strokePath([0, 0, 10, 0])).toBe("M0 0L10 0");
    expect(strokePath([0, 0, 10, 0, 20, 10])).toBe("M0 0Q10 0 15 5L20 10");
    expect(strokePath([])).toBe("");
  });

  it("rubs out a whole stroke when the eraser touches any part of it, and only that one", () => {
    const line: Stroke = { t: "pen", p: [0, 0, 100, 0] };
    const far: Stroke = { t: "pencil", p: [0, 200, 100, 200] };
    const dot: Stroke = { t: "pen", p: [300, 300] };
    // Touching the middle of a long segment counts, not just its sampled ends.
    expect(erase([line, far, dot], 50, 6, 10)).toEqual([far, dot]);
    expect(erase([line, far, dot], 50, 60, 10)).toEqual([line, far, dot]);
    expect(erase([line, far, dot], 304, 303, 10)).toEqual([line, far]);
  });
});
