package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

/**
 * Every corner in the app, in four values.
 *
 * The screens had drifted to fourteen different radii — 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 18
 * and 24 dp — which reads as carelessness long before anyone can name why. Radius is not decoration
 * here: it says what kind of thing you are looking at. A control you press, a block of information,
 * something layered above the page, or a mark too small to round at all.
 */
object Radius {
  /** Marks that are drawn rather than pressed: progress dots, milestone diamonds, rules. */
  val mark = 3.dp

  /** Anything a finger presses: chips, icon buttons, schedule bars, small clipped hit areas. */
  val control = 6.dp

  /** Blocks of information: cards, board lanes, panels, grouped rows. */
  val block = 10.dp

  /** Things layered above the page: dialogs, sheets, the command palette. */
  val container = 16.dp
}

/** Material's own scale, expressed in this app's four radii so built-in components match. */
val AppShapes =
  Shapes(
    extraSmall = RoundedCornerShape(Radius.control),
    small = RoundedCornerShape(Radius.block),
    medium = RoundedCornerShape(Radius.block),
    large = RoundedCornerShape(Radius.container),
    extraLarge = RoundedCornerShape(Radius.container),
  )

/**
 * Theme entry point.
 *
 * The accent is passed in as a persisted key rather than read from a global, so
 * the whole tree recomposes correctly when the user picks a different one and
 * previews/tests can render any accent without touching app state.
 */
@Composable
fun DailyBriefTheme(
  accentKey: String = Accents.Default.key,
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val accent = remember(accentKey) { Accents.byKey(accentKey) }
  val colorScheme =
    remember(accent, darkTheme) { if (darkTheme) darkSchemeFor(accent) else lightSchemeFor(accent) }
  val statusColors = remember(darkTheme) { statusColorsFor(darkTheme) }

  CompositionLocalProvider(LocalStatusColors provides statusColors) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      shapes = AppShapes,
      content = content,
    )
  }
}
