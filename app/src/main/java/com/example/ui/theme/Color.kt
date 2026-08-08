package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * A single, flat palette. Neutrals carry the layout; one accent carries meaning.
 * Nothing here is mutated at runtime — the active accent is chosen when the
 * ColorScheme is built, so every colour reaching a composable is theme-scoped.
 */

// ---- Neutrals -------------------------------------------------------------

private val InkBlack = Color(0xFF0B0B0C)
private val InkSurface = Color(0xFF121214)
private val InkSurfaceHigh = Color(0xFF1A1A1D)
private val InkSurfaceHighest = Color(0xFF212125)
private val InkOutline = Color(0xFF2E2E33)
private val InkOutlineSoft = Color(0xFF1F1F23)
private val InkOn = Color(0xFFECECEF)
private val InkOnMuted = Color(0xFF97979F)

private val PaperWhite = Color(0xFFFFFFFF)
private val PaperSurface = Color(0xFFF7F7F8)
private val PaperSurfaceHigh = Color(0xFFF1F1F3)
private val PaperSurfaceHighest = Color(0xFFE9E9ED)
private val PaperOutline = Color(0xFFD9D9DE)
private val PaperOutlineSoft = Color(0xFFEDEDF0)
private val PaperOn = Color(0xFF0E0E10)
private val PaperOnMuted = Color(0xFF6A6A73)

// ---- Status ---------------------------------------------------------------

private val DangerDark = Color(0xFFFF8A80)
private val DangerLight = Color(0xFFC0392B)
private val WarnDark = Color(0xFFFFC46B)
private val WarnLight = Color(0xFFB26A00)
private val GoodDark = Color(0xFF7ED9A6)
private val GoodLight = Color(0xFF1E7A4B)

/** Colours that carry meaning rather than brand. Consumed via [LocalStatusColors]. */
@Immutable
data class StatusColors(
  val deadline: Color,
  val urgent: Color,
  val positive: Color,
)

val LocalStatusColors = staticCompositionLocalOf {
  StatusColors(deadline = DangerDark, urgent = WarnDark, positive = GoodDark)
}

internal fun statusColorsFor(dark: Boolean) =
  if (dark) StatusColors(deadline = DangerDark, urgent = WarnDark, positive = GoodDark)
  else StatusColors(deadline = DangerLight, urgent = WarnLight, positive = GoodLight)

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
  val Violet =
    Accent("purple", "Violet", Color(0xFFC5B3FF), Color(0xFF251A46), Color(0xFF5B45C7), PaperWhite)
  val Teal =
    Accent("teal", "Teal", Color(0xFF7FD8CB), Color(0xFF00332C), Color(0xFF10726A), PaperWhite)
  val Blue =
    Accent("cyan", "Blue", Color(0xFF8FCBFF), Color(0xFF00294A), Color(0xFF1160A8), PaperWhite)
  val Amber =
    Accent("orange", "Amber", Color(0xFFF5C377), Color(0xFF3A2600), Color(0xFF8A5A00), PaperWhite)
  val Rose =
    Accent("crimson", "Rose", Color(0xFFF9A9A2), Color(0xFF441512), Color(0xFFB03A34), PaperWhite)
  val Green =
    Accent("emerald", "Green", Color(0xFF9BD79B), Color(0xFF12330F), Color(0xFF2C6E2C), PaperWhite)

  val all = listOf(Violet, Teal, Blue, Amber, Rose, Green)

  /** Falls back to [Violet] so an unknown persisted key can never break theming. */
  fun byKey(key: String?): Accent = all.firstOrNull { it.key == key } ?: Violet
}

// ---- Schemes --------------------------------------------------------------

internal fun darkSchemeFor(accent: Accent): ColorScheme =
  darkColorScheme(
    primary = accent.darkPrimary,
    onPrimary = accent.darkOnPrimary,
    primaryContainer = accent.darkPrimary.copy(alpha = 0.16f).compositeOverOpaque(InkSurfaceHigh),
    onPrimaryContainer = accent.darkPrimary,
    secondary = InkOnMuted,
    onSecondary = InkBlack,
    secondaryContainer = InkSurfaceHighest,
    onSecondaryContainer = InkOn,
    tertiary = accent.darkPrimary,
    onTertiary = accent.darkOnPrimary,
    background = InkBlack,
    onBackground = InkOn,
    surface = InkBlack,
    onSurface = InkOn,
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = InkOnMuted,
    surfaceTint = accent.darkPrimary,
    surfaceContainerLowest = InkBlack,
    surfaceContainerLow = InkSurface,
    surfaceContainer = InkSurface,
    surfaceContainerHigh = InkSurfaceHigh,
    surfaceContainerHighest = InkSurfaceHighest,
    inverseSurface = InkOn,
    inverseOnSurface = InkBlack,
    error = DangerDark,
    onError = Color(0xFF3B0906),
    errorContainer = Color(0xFF2A100E),
    onErrorContainer = DangerDark,
    outline = InkOutline,
    outlineVariant = InkOutlineSoft,
    scrim = Color(0xFF000000),
  )

internal fun lightSchemeFor(accent: Accent): ColorScheme =
  lightColorScheme(
    primary = accent.lightPrimary,
    onPrimary = accent.lightOnPrimary,
    primaryContainer = accent.lightPrimary.copy(alpha = 0.12f).compositeOverOpaque(PaperWhite),
    onPrimaryContainer = accent.lightPrimary,
    secondary = PaperOnMuted,
    onSecondary = PaperWhite,
    secondaryContainer = PaperSurfaceHighest,
    onSecondaryContainer = PaperOn,
    tertiary = accent.lightPrimary,
    onTertiary = accent.lightOnPrimary,
    background = PaperWhite,
    onBackground = PaperOn,
    surface = PaperWhite,
    onSurface = PaperOn,
    surfaceVariant = PaperSurfaceHigh,
    onSurfaceVariant = PaperOnMuted,
    surfaceTint = accent.lightPrimary,
    surfaceContainerLowest = PaperWhite,
    surfaceContainerLow = PaperSurface,
    surfaceContainer = PaperSurface,
    surfaceContainerHigh = PaperSurfaceHigh,
    surfaceContainerHighest = PaperSurfaceHighest,
    inverseSurface = PaperOn,
    inverseOnSurface = PaperWhite,
    error = DangerLight,
    onError = PaperWhite,
    errorContainer = Color(0xFFFDECEA),
    onErrorContainer = DangerLight,
    outline = PaperOutline,
    outlineVariant = PaperOutlineSoft,
    scrim = Color(0xFF000000),
  )

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
