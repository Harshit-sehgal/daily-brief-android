import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { describe, expect, it } from "vitest";

import { Accents, Palette, PaletteDark, Radius } from "./palette";

/*
 * The mobile palette is a mirror of the app's, not a second opinion.
 *
 * Color.kt is the source of truth. This parses it and compares, so changing one
 * without the other is caught here rather than discovered as two products that
 * no longer look related. If this fails: change Color.kt, then mirror.
 */

const here = dirname(fileURLToPath(import.meta.url));
const themeDir = resolve(here, "../../../android/src/main/java/com/example/ui/theme");
const kotlin = readFileSync(resolve(themeDir, "Color.kt"), "utf8");

function kotlinColor(name: string): string {
  const m = kotlin.match(new RegExp(`\\bval ${name} = Color\\(0x[fF][fF]([0-9a-fA-F]{6})\\)`));
  if (!m) throw new Error(`Color.kt has no val named ${name}`);
  return `#${m[1].toUpperCase()}`;
}

function kotlinAccent(key: string): { dark: string; onDark: string; light: string } {
  const m = kotlin.match(
    new RegExp(
      `Accent\\(\\s*"${key}",\\s*"[^"]+",\\s*` +
        `Color\\(0x[fF][fF]([0-9a-fA-F]{6})\\),\\s*` +
        `Color\\(0x[fF][fF]([0-9a-fA-F]{6})\\),\\s*` +
        `Color\\(0x[fF][fF]([0-9a-fA-F]{6})\\)`,
    ),
  );
  if (!m) throw new Error(`Color.kt has no Accent for "${key}"`);
  return { dark: `#${m[1].toUpperCase()}`, onDark: `#${m[2].toUpperCase()}`, light: `#${m[3].toUpperCase()}` };
}

const up = (hex: string) => hex.toUpperCase();

describe("the mobile palette mirrors Color.kt", () => {
  const neutrals: [keyof typeof Palette, string, string][] = [
    ["base", "PaperBase", "InkBase"],
    ["surface", "PaperSurface", "InkSurface"],
    ["surfaceHigh", "PaperSurfaceHigh", "InkSurfaceHigh"],
    ["surfaceHighest", "PaperSurfaceHighest", "InkSurfaceHighest"],
    ["outline", "PaperOutline", "InkOutline"],
    ["outlineSoft", "PaperOutlineSoft", "InkOutlineSoft"],
    ["on", "PaperOn", "InkOn"],
    ["onMuted", "PaperOnMuted", "InkOnMuted"],
  ];

  it.each(neutrals)("%s matches the Kotlin neutral in both themes", (key, light, dark) => {
    expect(up(Palette[key])).toBe(kotlinColor(light));
    expect(up(PaletteDark[key])).toBe(kotlinColor(dark));
  });

  const status: [keyof typeof Palette, string, string][] = [
    ["deadline", "BrickLight", "BrickDark"],
    ["urgent", "HoneyLight", "HoneyDark"],
    ["positive", "SageLight", "SageDark"],
  ];

  it.each(status)("%s matches the Kotlin status colour in both themes", (key, light, dark) => {
    expect(up(Palette[key])).toBe(kotlinColor(light));
    expect(up(PaletteDark[key])).toBe(kotlinColor(dark));
  });

  it("pure is white on paper and the card surface on ink", () => {
    // "Pure white" has no dark counterpart; a raised surface in dark is
    // InkSurface, which is what a card should sit on.
    expect(up(Palette.pure)).toBe(kotlinColor("PaperPure"));
    expect(up(PaletteDark.pure)).toBe(kotlinColor("InkSurface"));
  });

  it("the default accent is terracotta, as it is in Kotlin", () => {
    expect(kotlin).toMatch(/val Default = Terracotta/);
    expect(up(Palette.accent)).toBe(kotlinAccent("terracotta").light);
    expect(up(PaletteDark.accent)).toBe(kotlinAccent("terracotta").dark);
    expect(up(PaletteDark.onAccent)).toBe(kotlinAccent("terracotta").onDark);
  });

  it.each(Object.keys(Accents))("the %s accent matches Accents in Kotlin", (key) => {
    const k = kotlinAccent(key);
    const a = Accents[key as keyof typeof Accents];
    expect(up(a.light)).toBe(k.light);
    expect(up(a.dark)).toBe(k.dark);
  });

  it("carries all six accents, in the pair order Kotlin declares", () => {
    expect(Object.keys(Accents)).toEqual(["terracotta", "teal", "sage", "plum", "honey", "indigo"]);
  });
});

describe("the radii are the app's four, and only four", () => {
  const theme = readFileSync(resolve(themeDir, "Theme.kt"), "utf8");

  it.each(["mark", "control", "block", "container"] as const)("Radius.%s matches Theme.kt", (name) => {
    const m = theme.match(new RegExp(`val ${name} = (\\d+)\\.dp`));
    expect(m, `Theme.kt has no Radius.${name}`).toBeTruthy();
    expect(Radius[name]).toBe(Number(m![1]));
  });

  it("has exactly four", () => {
    expect(Object.keys(Radius)).toHaveLength(4);
  });
});
