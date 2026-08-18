package com.example.ui.screens

import androidx.compose.ui.unit.dp
import com.example.ui.theme.WindowWidth
import com.example.ui.theme.windowWidthFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanWorkspaceLayoutPolicyTest {
  @Test
  fun ledgerStartsExactlyAtExpandedAndScalesDeliberately() {
    assertNull(planLedgerWidthFor(windowWidthFor(599.dp)))
    assertNull(planLedgerWidthFor(windowWidthFor(600.dp)))
    assertNull(planLedgerWidthFor(windowWidthFor(839.dp)))
    assertEquals(300.dp, planLedgerWidthFor(windowWidthFor(840.dp)))
    assertEquals(300.dp, planLedgerWidthFor(windowWidthFor(1_199.dp)))
    assertEquals(320.dp, planLedgerWidthFor(windowWidthFor(1_200.dp)))
    assertEquals(320.dp, planLedgerWidthFor(windowWidthFor(1_599.dp)))
    assertEquals(336.dp, planLedgerWidthFor(windowWidthFor(1_600.dp)))
  }

  @Test
  fun onlyMultiPaneWindowClassesReceiveTheLedger() {
    WindowWidth.entries.forEach { width ->
      assertEquals(width.supportsMultiPane, planLedgerWidthFor(width) != null)
    }
  }
}
