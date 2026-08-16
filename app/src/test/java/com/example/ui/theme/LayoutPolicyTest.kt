package com.example.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutPolicyTest {
  @Test
  fun exactAndroidWidthBoundariesSelectAllFiveClasses() {
    assertEquals(WindowWidth.Compact, windowWidthFor(599.dp))
    assertEquals(WindowWidth.Medium, windowWidthFor(600.dp))
    assertEquals(WindowWidth.Medium, windowWidthFor(839.dp))
    assertEquals(WindowWidth.Expanded, windowWidthFor(840.dp))
    assertEquals(WindowWidth.Expanded, windowWidthFor(1_199.dp))
    assertEquals(WindowWidth.Large, windowWidthFor(1_200.dp))
    assertEquals(WindowWidth.Large, windowWidthFor(1_599.dp))
    assertEquals(WindowWidth.ExtraLarge, windowWidthFor(1_600.dp))
  }

  @Test
  fun navigationAndMultiPanePoliciesRemainTaskEquivalent() {
    assertFalse(WindowWidth.Compact.usesSideNav)
    assertTrue(WindowWidth.Medium.usesSideNav)
    assertFalse(WindowWidth.Medium.supportsMultiPane)
    assertTrue(WindowWidth.Expanded.supportsMultiPane)
    assertTrue(WindowWidth.Large.supportsMultiPane)
    assertTrue(WindowWidth.ExtraLarge.supportsMultiPane)
  }
}
