package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale. Every gap in the app is one of these. */
object Space {
  val xs: Dp = 4.dp
  val sm: Dp = 8.dp
  val md: Dp = 12.dp
  val lg: Dp = 16.dp
  val xl: Dp = 24.dp
  val xxl: Dp = 32.dp
}

/**
 * Coarse width buckets, mirroring the Material window size classes without
 * pulling in the extra artifact.
 *
 * - [Compact]: phone portrait
 * - [Medium]: phone landscape, small tablet, unfolded inner screens
 * - [Expanded]: tablet landscape, desktop-class windows
 */
enum class WindowWidth {
  Compact,
  Medium,
  Expanded;

  val isCompact: Boolean
    get() = this == Compact

  /** Side navigation replaces the bottom bar once there is room for it. */
  val usesSideNav: Boolean
    get() = this != Compact
}

val LocalWindowWidth = staticCompositionLocalOf { WindowWidth.Compact }

@Composable
@ReadOnlyComposable
fun currentWindowWidth(): WindowWidth {
  val widthDp = LocalConfiguration.current.screenWidthDp
  return when {
    widthDp < 600 -> WindowWidth.Compact
    widthDp < 840 -> WindowWidth.Medium
    else -> WindowWidth.Expanded
  }
}

/** Screen gutters grow with the window so text never runs edge to edge on tablets. */
val WindowWidth.gutter: Dp
  get() =
    when (this) {
      WindowWidth.Compact -> 16.dp
      WindowWidth.Medium -> 24.dp
      WindowWidth.Expanded -> 32.dp
    }

/** Caps line length on wide screens; content stays centred beyond this. */
val WindowWidth.readableMaxWidth: Dp
  get() =
    when (this) {
      WindowWidth.Compact -> Dp.Unspecified
      WindowWidth.Medium -> 680.dp
      WindowWidth.Expanded -> 840.dp
    }
