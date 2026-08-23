/**
 * The design system's values, mirrored from the app.
 *
 * SOURCE OF TRUTH: apps/android/src/main/java/com/example/ui/theme/Color.kt,
 * Theme.kt (Radius) and Layout.kt (Space). Do not invent values here — change
 * Color.kt and mirror. `theme.test.ts` parses the Kotlin and fails on drift.
 *
 * This module holds nothing but data and imports nothing. Keeping it free of
 * react-native and the global stylesheet is what lets the drift test import it
 * directly; `theme.ts` re-exports everything here.
 *
 * The neutrals are not grey: each carries a low-chroma warm bias around 30-40
 * degrees, so light reads as unbleached paper rather than a lab coat, and dark
 * as warm charcoal rather than blue-black. The status colours come from the
 * same family — brick, honey, sage — so they never clash with the accent.
 */

/** Unbleached paper. The static StyleSheets read from here. */
export const Palette = {
  base: '#FDFBF8',
  surface: '#F9F5EF',
  surfaceHigh: '#F4EFE7',
  surfaceHighest: '#EDE6DB',
  outline: '#DED5C8',
  outlineSoft: '#EFE9DF',
  on: '#211C16',
  onSoft: '#453D33',
  onMuted: '#6E6255',
  onFaint: '#8A7F71',
  pure: '#FFFFFF',

  /** Status. Never used for identity, and never the only signal. */
  deadline: '#B33A2B',
  urgent: '#8A6100',
  positive: '#4A7340',
  deadlineWash: '#FBF1EE',
  deadlineEdge: '#E4B0A8',
  deadlineInk: '#7A2A1E',

  /** Terracotta — the default of the app's six accents. */
  accent: '#A8451F',
  onAccent: '#FFFFFF',
  accentWash: '#F6E4DC',
} as const;

/** Warm charcoal. */
export const PaletteDark = {
  base: '#14110E',
  surface: '#1A1713',
  surfaceHigh: '#221E18',
  surfaceHighest: '#2B251E',
  outline: '#3B342B',
  outlineSoft: '#241F1A',
  on: '#F2EBE1',
  onSoft: '#D6CCBE',
  onMuted: '#ABA093',
  onFaint: '#7F7568',
  pure: '#1A1713',

  deadline: '#F2A099',
  urgent: '#EFC170',
  positive: '#A6CB9E',
  deadlineWash: '#241713',
  deadlineEdge: '#6B3830',
  deadlineInk: '#F2A099',

  accent: '#F0A88C',
  onAccent: '#46200F',
  accentWash: '#2C1D16',
} as const;

/** The six accents, in three complementary pairs: earth and water, leaf and
 *  bloom, warm light and cool shade. A project keeps one across surfaces. */
export const Accents = {
  terracotta: { light: '#A8451F', dark: '#F0A88C' },
  teal: { light: '#0E6E68', dark: '#7FD1CB' },
  sage: { light: '#47713C', dark: '#A8C8A0' },
  plum: { light: '#7A3B78', dark: '#DDB0DC' },
  honey: { light: '#8A6100', dark: '#F2C879' },
  indigo: { light: '#3C5BA9', dark: '#A8BFF5' },
} as const;

/**
 * Four radii, and only four. A corner says what kind of thing it belongs to —
 * the app enforces this with UiConsistencyTest, and the same discipline holds
 * here so the two surfaces stay recognisably one product.
 */
export const Radius = {
  /** Drawn marks: progress dots, rules, milestone diamonds. */
  mark: 3,
  /** Anything a finger presses: chips, icon buttons, schedule bars. */
  control: 6,
  /** Blocks of information: cards, lanes, panels, grouped rows. */
  block: 10,
  /** Layered above the page: dialogs, sheets, the command palette. */
  container: 16,
} as const;

/** The app's spacing scale (Layout.kt). */
export const Space = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  xxl: 32,
} as const;

/** Interactive elements honour this regardless of the density setting: visual
 *  density changes what fits, never the hit area. */
export const MinimumTouchTarget = 48;
