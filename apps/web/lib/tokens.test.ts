import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { describe, expect, it } from "vitest";

/*
 * The web palette is a mirror of the app's, not a second opinion.
 *
 * Color.kt is the source of truth. This parses it and the CSS side by side and
 * fails on any drift, so changing one without the other is caught here rather
 * than discovered as two products that no longer look related.
 *
 * If this fails: change Color.kt first, then mirror into app/tokens.css.
 */

const here = dirname(fileURLToPath(import.meta.url));
const kotlin = readFileSync(
  resolve(here, "../../android/src/main/java/com/example/ui/theme/Color.kt"),
  "utf8",
);
const css = readFileSync(resolve(here, "../app/tokens.css"), "utf8");

/** `private val PaperBase = Color(0xFFFDFBF8)` → `#FDFBF8` */
function kotlinColor(name: string): string {
  const m = kotlin.match(new RegExp(`\\bval ${name} = Color\\(0x[fF][fF]([0-9a-fA-F]{6})\\)`));
  if (!m) throw new Error(`Color.kt has no simple val named ${name}`);
  return `#${m[1].toUpperCase()}`;
}

/** The Accent(...) constructor: key, label, darkPrimary, darkOnPrimary, lightPrimary. */
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

/** Reads a custom property out of one CSS block, matched by its selector text. */
function cssVar(blockStartsWith: string, prop: string): string {
  const start = css.indexOf(blockStartsWith);
  if (start < 0) throw new Error(`tokens.css has no block starting "${blockStartsWith}"`);
  const open = css.indexOf("{", start);
  const close = css.indexOf("}", open);
  const block = css.slice(open, close);
  const m = block.match(new RegExp(`${prop}\\s*:\\s*(#[0-9a-fA-F]{6})`));
  if (!m) throw new Error(`"${blockStartsWith}" does not set ${prop}`);
  return m[1].toUpperCase();
}

const LIGHT = ":root {";
const DARK = ':root[data-theme="dark"] {';

describe("the web palette mirrors Color.kt", () => {
  const neutrals: Array<[string, string, string]> = [
    // [css property, light Kotlin val, dark Kotlin val]
    ["--base", "PaperBase", "InkBase"],
    ["--surface", "PaperSurface", "InkSurface"],
    ["--surface-high", "PaperSurfaceHigh", "InkSurfaceHigh"],
    ["--surface-highest", "PaperSurfaceHighest", "InkSurfaceHighest"],
    ["--outline", "PaperOutline", "InkOutline"],
    ["--outline-soft", "PaperOutlineSoft", "InkOutlineSoft"],
    ["--on", "PaperOn", "InkOn"],
    ["--on-muted", "PaperOnMuted", "InkOnMuted"],
  ];

  it.each(neutrals)("%s matches the Kotlin neutrals in both themes", (prop, light, dark) => {
    expect(cssVar(LIGHT, prop)).toBe(kotlinColor(light));
    expect(cssVar(DARK, prop)).toBe(kotlinColor(dark));
  });

  it("--pure is white on paper and the card surface on ink", () => {
    // Deliberate asymmetry: "pure white" has no dark counterpart. A raised
    // surface in dark is InkSurface, which is what a card should sit on.
    expect(cssVar(LIGHT, "--pure")).toBe(kotlinColor("PaperPure"));
    expect(cssVar(DARK, "--pure")).toBe(kotlinColor("InkSurface"));
  });

  const status: Array<[string, string, string]> = [
    ["--deadline", "BrickLight", "BrickDark"],
    ["--urgent", "HoneyLight", "HoneyDark"],
    ["--positive", "SageLight", "SageDark"],
  ];

  it.each(status)("%s matches the Kotlin status colour in both themes", (prop, light, dark) => {
    expect(cssVar(LIGHT, prop)).toBe(kotlinColor(light));
    expect(cssVar(DARK, prop)).toBe(kotlinColor(dark));
  });

  const ACCENTS = ["terracotta", "teal", "sage", "plum", "honey", "indigo"] as const;

  it.each(ACCENTS)("the %s accent matches Accents.%s", (key) => {
    const k = kotlinAccent(key);
    expect(cssVar(`:root[data-accent="${key}"]`, "--accent")).toBe(k.light);
    expect(cssVar(`:root[data-theme="dark"][data-accent="${key}"]`, "--accent")).toBe(k.dark);
    expect(cssVar(`:root[data-theme="dark"][data-accent="${key}"]`, "--on-accent")).toBe(k.onDark);
  });

  it.each(ACCENTS)("the %s project hue is that accent's primary", (key) => {
    const k = kotlinAccent(key);
    expect(cssVar(LIGHT, `--hue-${key}`)).toBe(k.light);
    expect(cssVar(DARK, `--hue-${key}`)).toBe(k.dark);
  });

  it("terracotta is the default accent, as it is in Kotlin", () => {
    expect(kotlin).toMatch(/val Default = Terracotta/);
    expect(cssVar(LIGHT, "--accent")).toBe(kotlinAccent("terracotta").light);
    expect(cssVar(DARK, "--accent")).toBe(kotlinAccent("terracotta").dark);
  });
});

describe("the radii are the app's four, and only four", () => {
  it("matches Theme.kt", () => {
    const theme = readFileSync(
      resolve(here, "../../android/src/main/java/com/example/ui/theme/Theme.kt"),
      "utf8",
    );
    for (const [prop, name] of [
      ["--r-mark", "mark"],
      ["--r-control", "control"],
      ["--r-block", "block"],
      ["--r-container", "container"],
    ] as const) {
      const m = theme.match(new RegExp(`val ${name} = (\\d+)\\.dp`));
      expect(m, `Theme.kt has no Radius.${name}`).toBeTruthy();
      const block = css.slice(css.indexOf(LIGHT), css.indexOf("}", css.indexOf(LIGHT)));
      expect(block).toMatch(new RegExp(`${prop}\\s*:\\s*${m![1]}px`));
    }
  });
});
