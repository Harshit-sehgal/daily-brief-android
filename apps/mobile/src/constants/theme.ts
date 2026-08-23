/**
 * The design system, mirrored from the app.
 *
 * SOURCE OF TRUTH: apps/android/src/main/java/com/example/ui/theme/Color.kt,
 * Theme.kt (Radius) and Layout.kt (Space). Do not invent values here — change
 * Color.kt and mirror. `src/constants/palette.test.ts` parses the Kotlin and
 * fails when these drift.
 *
 * The neutrals are not grey: each carries a low-chroma warm bias around 30-40
 * degrees, so light reads as unbleached paper rather than a lab coat, and dark
 * as warm charcoal rather than blue-black. The status colours come from the
 * same family — brick, honey, sage — so they never clash with the accent.
 */

import '@/global.css';

import { Platform } from 'react-native';

import { Palette, PaletteDark } from './palette';

export { Palette, PaletteDark, Accents, Radius, Space, MinimumTouchTarget } from './palette';

export const Colors = {
  light: {
    text: Palette.on,
    background: Palette.base,
    backgroundElement: Palette.surfaceHigh,
    backgroundSelected: Palette.surfaceHighest,
    textSecondary: Palette.onMuted,
  },
  dark: {
    text: PaletteDark.on,
    background: PaletteDark.base,
    backgroundElement: PaletteDark.surfaceHigh,
    backgroundSelected: PaletteDark.surfaceHighest,
    textSecondary: PaletteDark.onMuted,
  },
} as const;

export type ThemeColor = keyof typeof Colors.light & keyof typeof Colors.dark;

export const Fonts = Platform.select({
  ios: {
    /** iOS `UIFontDescriptorSystemDesignDefault` */
    sans: 'system-ui',
    /** iOS `UIFontDescriptorSystemDesignSerif` */
    serif: 'ui-serif',
    /** iOS `UIFontDescriptorSystemDesignRounded` */
    rounded: 'ui-rounded',
    /** iOS `UIFontDescriptorSystemDesignMonospaced` */
    mono: 'ui-monospace',
  },
  default: {
    sans: 'normal',
    serif: 'serif',
    rounded: 'normal',
    mono: 'monospace',
  },
  web: {
    sans: 'var(--font-display)',
    serif: 'var(--font-serif)',
    rounded: 'var(--font-rounded)',
    mono: 'var(--font-mono)',
  },
});

export const Spacing = {
  half: 2,
  one: 4,
  two: 8,
  three: 16,
  four: 24,
  five: 32,
  six: 64,
} as const;

export const BottomTabInset = Platform.select({ ios: 50, android: 80 }) ?? 0;
export const MaxContentWidth = 800;
