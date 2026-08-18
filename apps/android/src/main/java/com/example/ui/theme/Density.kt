package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Minimum hit area for every interactive workspace primitive. */
val MinimumTouchTarget = 48.dp

/**
 * An icon that sits beside a label, inside a button, field or row.
 *
 * These had drifted to 16, 18 and 20 dp for the same job, which makes rows of buttons look
 * misaligned without anyone being able to say why. An icon that *is* the control keeps the density
 * token instead, because that one scales with the chosen information density.
 */
val InlineIconSize = 18.dp

/**
 * How tightly the workspace packs.
 *
 * Every row, gap and type size in the app reads from here rather than hard-coding
 * a value, so one setting genuinely changes the information density instead of
 * only nudging a font. Compact is the Obsidian-ish default for people who want to
 * see the whole day at once; Relaxed exists for larger touch targets.
 */
enum class UiDensity(val key: String, val label: String, val blurb: String) {
  Compact("compact", "Compact", "Most on screen at once"),
  Cozy("cozy", "Cozy", "Balanced spacing"),
  Relaxed("relaxed", "Relaxed", "Larger touch targets");

  companion object {
    val Default = Cozy

    fun byKey(key: String?): UiDensity = entries.firstOrNull { it.key == key } ?: Default
  }
}

/** The saved-view codec's zoom vocabulary is older ("comfortable", "expanded"); keep one mapping. */
fun UiDensity.toViewZoom(): String =
  when (this) {
    UiDensity.Compact -> "compact"
    UiDensity.Cozy -> "comfortable"
    UiDensity.Relaxed -> "expanded"
  }

@Immutable
data class DensityTokens(
  /** Visual minimum height of a row; interactive rows also honor [MinimumTouchTarget]. */
  val rowHeight: Dp,
  val rowPaddingV: Dp,
  /** Left gutter that markers, times and icons align to. */
  val gutter: Dp,
  /** Space between stacked rows inside a section. */
  val rowGap: Dp,
  /** Space between one section and the next. */
  val sectionGap: Dp,
  val sectionHeaderHeight: Dp,
  val title: TextUnit,
  val body: TextUnit,
  val secondary: TextUnit,
  val label: TextUnit,
  val icon: Dp,
  /** Visual width of one date cell; interactive cells also honor [MinimumTouchTarget]. */
  val dayCell: Dp,
  /** Height of one hour on the day timeline. */
  val hourHeight: Dp,
)

private val CompactTokens =
  DensityTokens(
    rowHeight = 38.dp,
    rowPaddingV = 4.dp,
    gutter = 54.dp,
    rowGap = 0.dp,
    sectionGap = 8.dp,
    sectionHeaderHeight = 28.dp,
    title = 13.5.sp,
    body = 13.sp,
    secondary = 11.5.sp,
    label = 10.5.sp,
    icon = 15.dp,
    dayCell = 38.dp,
    hourHeight = 52.dp,
  )

private val CozyTokens =
  DensityTokens(
    rowHeight = 52.dp,
    rowPaddingV = 9.dp,
    gutter = 62.dp,
    rowGap = 0.dp,
    sectionGap = 20.dp,
    sectionHeaderHeight = 36.dp,
    title = 14.5.sp,
    body = 14.sp,
    secondary = 12.5.sp,
    label = 11.sp,
    icon = 16.dp,
    dayCell = 48.dp,
    hourHeight = 62.dp,
  )

private val RelaxedTokens =
  DensityTokens(
    rowHeight = 62.dp,
    rowPaddingV = 13.dp,
    gutter = 66.dp,
    rowGap = 2.dp,
    sectionGap = 26.dp,
    sectionHeaderHeight = 40.dp,
    title = 15.5.sp,
    body = 15.sp,
    secondary = 13.sp,
    label = 11.5.sp,
    icon = 18.dp,
    dayCell = 56.dp,
    hourHeight = 74.dp,
  )

fun tokensFor(density: UiDensity): DensityTokens =
  when (density) {
    UiDensity.Compact -> CompactTokens
    UiDensity.Cozy -> CozyTokens
    UiDensity.Relaxed -> RelaxedTokens
  }

val LocalUiDensity = staticCompositionLocalOf { UiDensity.Default }
val LocalDensityTokens = staticCompositionLocalOf { tokensFor(UiDensity.Default) }
