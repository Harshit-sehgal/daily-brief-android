package com.example.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phones all share one width class, so the class alone cannot size anything.
 *
 * `Compact` spans 320 dp to 599 dp. A 308 dp screen — a large phone with Display size turned up —
 * and a 430 dp one are the same bucket and nothing like the same screen, which is how a root title
 * came to wrap into the actions beside it while the same code looked correct on a test device.
 */
class AdaptiveLayoutTest {
  @Test
  fun `a root title steps down as the screen narrows`() {
    assertEquals(19.sp, rootTitleSizeFor(308.dp))
    assertEquals(19.sp, rootTitleSizeFor(339.dp))
    assertEquals(21.sp, rootTitleSizeFor(360.dp))
    assertEquals(23.sp, rootTitleSizeFor(411.dp))
    assertEquals(23.sp, rootTitleSizeFor(448.dp))
  }

  @Test
  fun `the step is monotonic, so a wider screen never gets smaller type`() {
    val widths = listOf(280, 300, 320, 340, 360, 380, 400, 440, 600, 840).map { it.dp }
    val sizes = widths.map { rootTitleSizeFor(it).value }
    assertEquals(sizes.sorted(), sizes)
  }

  @Test
  fun `the tightest phones spend less on their margins`() {
    // Below the step the gutter narrows; at and above it the width class decides, as before.
    assertEquals(Space.md, gutterFor(WindowWidth.Compact, 308.dp))
    assertEquals(WindowWidth.Compact.gutter, gutterFor(WindowWidth.Compact, 360.dp))
    // A narrow *window* on a wide device is still that device: only phones take the tighter gutter.
    assertEquals(WindowWidth.Expanded.gutter, gutterFor(WindowWidth.Expanded, 320.dp))
  }

  @Test
  fun `a short window is the one that cannot afford a standing subtitle`() {
    assertTrue(isShortWindow(640.dp))
    assertTrue(isShortWindow(685.dp))
    assertFalse(isShortWindow(720.dp))
    assertFalse(isShortWindow(914.dp))
  }
}
