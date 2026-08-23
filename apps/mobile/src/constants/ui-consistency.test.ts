import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join, resolve } from "node:path";
import { describe, expect, it } from "vitest";

import { Radius } from "./palette";

/*
 * The mobile analogue of the app's UiConsistencyTest.
 *
 * The screens had drifted to stock Expo blue, four ad-hoc corner radii and
 * `opacity` standing in for muted ink — which over a warm ground washes the hue
 * out instead of using the neutral designed for it. This reads the source and
 * fails on a relapse, because none of it is visible to a type-checker and the
 * API 36 emulator cannot present this app's window to a rendered test.
 */

const here = dirname(fileURLToPath(import.meta.url));
const srcRoot = resolve(here, "..");

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return entry.isFile() && /\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) ? [path] : [];
  });
}

/** Everything except the two files that are allowed to name raw values. */
const styled = sourceFiles(srcRoot).filter(
  (p) => !p.endsWith(join("constants", "palette.ts")) && !p.endsWith(join("constants", "ui.ts")),
);

const read = (p: string) => readFileSync(p, "utf8");
const rel = (p: string) => p.slice(srcRoot.length + 1);

describe("colour comes from the palette, never from a literal", () => {
  it.each(styled.map(rel))("%s names no hex colour", (name) => {
    const source = read(join(srcRoot, name));
    const hits = source.match(/["'`]#[0-9a-fA-F]{3,8}["'`]/g) ?? [];
    expect(hits, `use a Palette token instead of ${hits.join(", ")}`).toEqual([]);
  });

  it("no style dims ink with opacity instead of naming the neutral", () => {
    // The neutrals carry a warm bias on purpose; opacity over the ground gives a
    // washed grey that is not any colour in the system. Press and disabled states
    // legitimately dim a whole control, so they are exempt by name.
    const offenders: string[] = [];
    for (const path of styled) {
      for (const [, key, body] of read(path).matchAll(/(\w+):\s*\{([^{}]*)\}/g)) {
        if (/press|disabl|ghost|scrim|overlay|backdrop/i.test(key)) continue;
        if (/\bopacity:\s*0?\.\d/.test(body)) offenders.push(`${rel(path)} → ${key}`);
      }
    }
    expect(offenders, "use Palette.onMuted / onFaint rather than opacity").toEqual([]);
  });
});

describe("four radii, and only four", () => {
  const allowed = new Set<number>(Object.values(Radius));

  it.each(styled.map(rel))("%s uses no corner radius outside the scale", (name) => {
    const source = read(join(srcRoot, name));
    const literals = [...source.matchAll(/border(?:Top|Bottom)?(?:Left|Right)?Radius:\s*(\d+)/g)]
      .map((m) => Number(m[1]))
      .filter((n) => n !== 0 && !allowed.has(n));
    expect(literals, `radii must be Radius.mark/control/block/container (${[...allowed].join("/")})`).toEqual([]);
  });

  it("the scale is the app's", () => {
    expect(allowed).toEqual(new Set([3, 6, 10, 16]));
  });
});

describe("a touch target is 48dp regardless of density", () => {
  it("no control declares a minHeight below the floor", () => {
    // Only styles that name a control: a plain row may be any height, but
    // anything a finger presses clears MinimumTouchTarget, because visual
    // density changes what fits and never the hit area.
    const offenders: string[] = [];
    for (const path of styled) {
      for (const [, key, body] of read(path).matchAll(/(\w+):\s*\{([^{}]*)\}/g)) {
        if (!/button|btn|chip|input|tap|action|toggle|swatch/i.test(key)) continue;
        const m = body.match(/minHeight:\s*(\d+)/);
        if (m && Number(m[1]) < 48) offenders.push(`${rel(path)} → ${key} (${m[1]})`);
      }
    }
    expect(offenders, "interactive styles must clear MinimumTouchTarget").toEqual([]);
  });
});
