package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

/** Corner radii. Two values carry the whole UI: 12 for controls, 18 for surfaces. */
val AppShapes =
  Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
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
