package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * Current-window width buckets, mirroring Android's five adaptive breakpoints without pulling in
 * an extra artifact. Large and extra-large are explicit so desktop-class windows cannot silently
 * receive a stretched tablet composition without dedicated test coverage.
 *
 * - [Compact]: phone portrait
 * - [Medium]: phone landscape, small tablet, unfolded inner screens
 * - [Expanded]: foldable and tablet
 * - [Large]: large tablet and compact desktop window
 * - [ExtraLarge]: Chromebook and desktop-class window
 */
enum class WindowWidth {
  Compact,
  Medium,
  Expanded,
  Large,
  ExtraLarge;

  val isCompact: Boolean
    get() = this == Compact

  /** Side navigation replaces the bottom bar once there is room for it. */
  val usesSideNav: Boolean
    get() = this != Compact

  /** Space is available for task-equivalent panes rather than a stretched single column. */
  val supportsMultiPane: Boolean
    get() = this == Expanded || this == Large || this == ExtraLarge
}

val LocalWindowWidth = staticCompositionLocalOf { WindowWidth.Compact }

/**
 * The measured window width, so a token can respond *inside* a width class as well as across them.
 *
 * Every phone is [WindowWidth.Compact] — the class spans 320 dp to 599 dp — so a bucket alone
 * cannot tell a 308 dp screen (a large phone with Display size turned up) from a 430 dp one. Type
 * and gutters read this instead, which is the difference between a title that fits on one line and
 * one that wraps into the controls beside it.
 */
val LocalWindowWidthDp = staticCompositionLocalOf { 400.dp }

/**
 * The measured window height, for the decisions that are about how much *vertical* room there is.
 *
 * A 640 dp-tall phone and a 1,000 dp-tall one run the same width class, and the difference is
 * entirely in what a standing header costs: on the short one the Schedule map had roughly one row
 * of canvas left after its chrome.
 */
val LocalWindowHeightDp = staticCompositionLocalOf { 800.dp }

/** Short enough that a standing subtitle costs more than it says. */
fun isShortWindow(height: Dp): Boolean = height < 720.dp

@Composable
@ReadOnlyComposable
fun isShortWindow(): Boolean = isShortWindow(LocalWindowHeightDp.current)

@Composable
@ReadOnlyComposable
fun currentWindowHeightDp(): Dp =
  with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

/**
 * Root titles step down on narrow screens.
 *
 * A root title is the widest single word on its screen and shares its line with the search, edit
 * and settings actions. Held at one size it either wraps — which pushes every root's content down
 * and leaves the actions floating against a two-line title — or it truncates a greeting to
 * "Good mor…". The steps are small on purpose: this is the same title, sized for the room it has.
 */
fun rootTitleSizeFor(width: Dp): TextUnit =
  when {
    width < 340.dp -> 19.sp
    width < 380.dp -> 21.sp
    else -> 23.sp
  }

/** Narrow screens spend less on their margins, because the column left over is what holds text. */
fun gutterFor(windowWidth: WindowWidth, width: Dp): Dp =
  if (windowWidth == WindowWidth.Compact && width < 340.dp) Space.md else windowWidth.gutter

@Composable
@ReadOnlyComposable
fun rootTitleSize(): TextUnit = rootTitleSizeFor(LocalWindowWidthDp.current)

/** The gutter for the current window, narrowed on the tightest phones. */
@Composable
@ReadOnlyComposable
fun currentGutter(): Dp = gutterFor(LocalWindowWidth.current, LocalWindowWidthDp.current)

@Composable
@ReadOnlyComposable
fun currentWindowWidthDp(): Dp =
  with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

@Composable
@ReadOnlyComposable
fun currentWindowWidth(): WindowWidth {
  val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
  return windowWidthFor(width)
}

internal fun windowWidthFor(width: Dp): WindowWidth =
  when {
    width < 600.dp -> WindowWidth.Compact
    width < 840.dp -> WindowWidth.Medium
    width < 1_200.dp -> WindowWidth.Expanded
    width < 1_600.dp -> WindowWidth.Large
    else -> WindowWidth.ExtraLarge
  }

/** Screen gutters grow with the window so text never runs edge to edge on tablets. */
val WindowWidth.gutter: Dp
  get() =
    when (this) {
      WindowWidth.Compact -> 16.dp
      WindowWidth.Medium -> 24.dp
      WindowWidth.Expanded -> 32.dp
      WindowWidth.Large -> 40.dp
      WindowWidth.ExtraLarge -> 48.dp
    }

/** Caps line length on wide screens; content stays centred beyond this. */
val WindowWidth.readableMaxWidth: Dp
  get() =
    when (this) {
      WindowWidth.Compact -> Dp.Unspecified
      WindowWidth.Medium -> 680.dp
      WindowWidth.Expanded -> 840.dp
      WindowWidth.Large -> 960.dp
      WindowWidth.ExtraLarge -> 1120.dp
    }
