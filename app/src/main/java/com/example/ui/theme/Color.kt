package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Warm palette.
 *
 * The neutrals are not grey. Every one carries a low-chroma warm bias around
 * 30-40° hue, so light mode reads as unbleached paper rather than a lab coat,
 * and dark mode as warm charcoal rather than blue-black. That single decision
 * does most of the work; the accents then sit on it without fighting.
 *
 * The six accents are three complementary pairs — terracotta/teal,
 * sage/plum, honey/indigo. Any accent therefore has a natural counterpart, and
 * because the status colours are drawn from the same family (brick, honey,
 * sage) they never clash with whichever accent the user picked.
 *
 * Every foreground here clears WCAG AA (4.5:1) against the surface it sits on.
 */

// ---- Warm neutrals --------------------------------------------------------

/** Warm charcoal, not blue-black. */
private val InkBase = Color(0xFF14110E)
private val InkSurface = Color(0xFF1A1713)
private val InkSurfaceHigh = Color(0xFF221E18)
private val InkSurfaceHighest = Color(0xFF2B251E)
private val InkOutline = Color(0xFF3B342B)
private val InkOutlineSoft = Color(0xFF241F1A)
private val InkOn = Color(0xFFF2EBE1)
private val InkOnMuted = Color(0xFFABA093)

/** Unbleached paper, not white. */
private val PaperBase = Color(0xFFFDFBF8)
private val PaperSurface = Color(0xFFF9F5EF)
private val PaperSurfaceHigh = Color(0xFFF4EFE7)
private val PaperSurfaceHighest = Color(0xFFEDE6DB)
private val PaperOutline = Color(0xFFDED5C8)
private val PaperOutlineSoft = Color(0xFFEFE9DF)
private val PaperOn = Color(0xFF211C16)
private val PaperOnMuted = Color(0xFF6E6255)
private val PaperPure = Color(0xFFFFFFFF)

// ---- Status ---------------------------------------------------------------
// Brick rather than fire-engine red, honey rather than traffic-cone orange,
// sage rather than emerald — so warnings read as part of the same palette.

private val BrickDark = Color(0xFFF2A099)
private val BrickLight = Color(0xFFB33A2B)
private val HoneyDark = Color(0xFFEFC170)
private val HoneyLight = Color(0xFF8A6100)
private val SageDark = Color(0xFFA6CB9E)
private val SageLight = Color(0xFF4A7340)

/** Colours that carry meaning rather than brand. Consumed via [LocalStatusColors]. */
@Immutable
data class StatusColors(val deadline: Color, val urgent: Color, val positive: Color)

val LocalStatusColors = staticCompositionLocalOf {
  StatusColors(deadline = BrickDark, urgent = HoneyDark, positive = SageDark)
}

internal fun statusColorsFor(dark: Boolean) =
  if (dark) StatusColors(deadline = BrickDark, urgent = HoneyDark, positive = SageDark)
  else StatusColors(deadline = BrickLight, urgent = HoneyLight, positive = SageLight)

// ---- Accents --------------------------------------------------------------

/** One user-selectable accent, defined once for each of the two themes. */
@Immutable
data class Accent(
  val key: String,
  val label: String,
  val darkPrimary: Color,
  val darkOnPrimary: Color,
  val lightPrimary: Color,
  val lightOnPrimary: Color,
) {
  /** The swatch shown in the picker, for the theme currently in use. */
  fun swatch(dark: Boolean) = if (dark) darkPrimary else lightPrimary

  /** Readable colour to draw on top of [swatch]. */
  fun onSwatch(dark: Boolean) = if (dark) darkOnPrimary else lightOnPrimary
}

object Accents {
  // Pair one: earth and water.
  val Terracotta =
    Accent(
      "terracotta",
      "Terracotta",
      Color(0xFFF0A88C),
      Color(0xFF46200F),
      Color(0xFFA8451F),
      PaperPure,
    )
  val Teal =
    Accent("teal", "Teal", Color(0xFF7FD1CB), Color(0xFF00332F), Color(0xFF0E6E68), PaperPure)

  // Pair two: leaf and bloom.
  val Sage =
    Accent("sage", "Sage", Color(0xFFA8C8A0), Color(0xFF1B3517), Color(0xFF47713C), PaperPure)
  val Plum =
    Accent("plum", "Plum", Color(0xFFDDB0DC), Color(0xFF3E1B3D), Color(0xFF7A3B78), PaperPure)

  // Pair three: warm light and cool shade.
  val Honey =
    Accent("honey", "Honey", Color(0xFFF2C879), Color(0xFF40300A), Color(0xFF8A6100), PaperPure)
  val Indigo =
    Accent("indigo", "Indigo", Color(0xFFA8BFF5), Color(0xFF17264C), Color(0xFF3C5BA9), PaperPure)

  /** Ordered so the picker reads as pairs. */
  val all = listOf(Terracotta, Teal, Sage, Plum, Honey, Indigo)

  val Default = Terracotta

  /** Accent keys written by earlier versions, mapped onto the warm palette. */
  private val legacy =
    mapOf(
      "crimson" to Terracotta,
      "orange" to Honey,
      "emerald" to Sage,
      "purple" to Plum,
      "cyan" to Indigo,
    )

  /** Never throws: an unknown or dropped key falls back to the default accent. */
  fun byKey(key: String?): Accent =
    all.firstOrNull { it.key == key } ?: legacy[key] ?: Default
}

// ---- Schemes --------------------------------------------------------------

internal fun darkSchemeFor(accent: Accent): ColorScheme =
  darkColorScheme(
    primary = accent.darkPrimary,
    onPrimary = accent.darkOnPrimary,
    primaryContainer = accent.darkPrimary.copy(alpha = 0.18f).compositeOverOpaque(InkSurfaceHigh),
    onPrimaryContainer = accent.darkPrimary,
    inversePrimary = accent.lightPrimary,
    secondary = InkOnMuted,
    onSecondary = InkBase,
    secondaryContainer = InkSurfaceHighest,
    onSecondaryContainer = InkOn,
    tertiary = accent.darkPrimary,
    onTertiary = accent.darkOnPrimary,
    // Unset, these fall back to baseline Material pink — visible in the time
    // picker's AM/PM selector, which is drawn from tertiaryContainer.
    tertiaryContainer = accent.darkPrimary.copy(alpha = 0.18f).compositeOverOpaque(InkSurfaceHigh),
    onTertiaryContainer = accent.darkPrimary,
    background = InkBase,
    onBackground = InkOn,
    surface = InkBase,
    onSurface = InkOn,
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = InkOnMuted,
    surfaceTint = accent.darkPrimary,
    surfaceBright = InkSurfaceHighest,
    surfaceDim = InkBase,
    surfaceContainerLowest = InkBase,
    surfaceContainerLow = InkSurface,
    surfaceContainer = InkSurface,
    surfaceContainerHigh = InkSurfaceHigh,
    surfaceContainerHighest = InkSurfaceHighest,
    inverseSurface = InkOn,
    inverseOnSurface = InkBase,
    error = BrickDark,
    onError = Color(0xFF48150F),
    errorContainer = Color(0xFF33150F),
    onErrorContainer = BrickDark,
    outline = InkOutline,
    outlineVariant = InkOutlineSoft,
    scrim = Color(0xFF000000),
  )

internal fun lightSchemeFor(accent: Accent): ColorScheme =
  lightColorScheme(
    primary = accent.lightPrimary,
    onPrimary = accent.lightOnPrimary,
    // 0.12 rather than 0.13: Material draws onPrimaryContainer — the accent
    // itself — on this tint, and at 0.13 the honey accent lands on exactly
    // 4.50:1. A shade paler is invisible and clears AA for all six.
    primaryContainer = accent.lightPrimary.copy(alpha = LIGHT_CONTAINER_TINT).compositeOverOpaque(PaperBase),
    onPrimaryContainer = accent.lightPrimary,
    inversePrimary = accent.darkPrimary,
    secondary = PaperOnMuted,
    onSecondary = PaperPure,
    secondaryContainer = PaperSurfaceHighest,
    onSecondaryContainer = PaperOn,
    tertiary = accent.lightPrimary,
    onTertiary = accent.lightOnPrimary,
    tertiaryContainer = accent.lightPrimary.copy(alpha = LIGHT_CONTAINER_TINT).compositeOverOpaque(PaperBase),
    onTertiaryContainer = accent.lightPrimary,
    background = PaperBase,
    onBackground = PaperOn,
    surface = PaperBase,
    onSurface = PaperOn,
    surfaceVariant = PaperSurfaceHigh,
    onSurfaceVariant = PaperOnMuted,
    surfaceTint = accent.lightPrimary,
    surfaceBright = PaperBase,
    surfaceDim = PaperSurfaceHighest,
    surfaceContainerLowest = PaperPure,
    surfaceContainerLow = PaperSurface,
    surfaceContainer = PaperSurfaceHigh,
    surfaceContainerHigh = PaperSurfaceHigh,
    surfaceContainerHighest = PaperSurfaceHighest,
    inverseSurface = PaperOn,
    inverseOnSurface = PaperBase,
    error = BrickLight,
    onError = PaperPure,
    errorContainer = Color(0xFFFBEDEA),
    onErrorContainer = BrickLight,
    outline = PaperOutline,
    outlineVariant = PaperOutlineSoft,
    scrim = Color(0xFF000000),
  )

/** How strongly a light-theme container carries its accent. See [lightSchemeFor]. */
private const val LIGHT_CONTAINER_TINT = 0.12f

/**
 * Flattens a translucent colour onto an opaque one. Container colours have to be
 * opaque so that they read identically whatever is drawn behind them.
 */
private fun Color.compositeOverOpaque(background: Color): Color =
  Color(
    red = red * alpha + background.red * (1f - alpha),
    green = green * alpha + background.green * (1f - alpha),
    blue = blue * alpha + background.blue * (1f - alpha),
    alpha = 1f,
  )
