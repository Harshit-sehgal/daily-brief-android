package com.example.ui.screens

import com.example.ui.theme.WindowWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarPanePolicyTest {
  @Test
  fun wideDayDeskEmphasizesTheSelectedPaneWithoutChangingPaneOrder() {
    listOf(WindowWidth.Expanded, WindowWidth.Large, WindowWidth.ExtraLarge).forEach { width ->
      val agendaPrimary = calendarPaneWeights(width, CalendarView.Agenda)
      val timelinePrimary = calendarPaneWeights(width, CalendarView.Timeline)

      assertTrue(agendaPrimary.agenda > agendaPrimary.timeline)
      assertTrue(timelinePrimary.timeline > timelinePrimary.agenda)
      assertTrue(agendaPrimary.timeline >= 0.8f)
      assertTrue(timelinePrimary.agenda >= 0.8f)
      assertEquals(2f, agendaPrimary.agenda + agendaPrimary.timeline, 0.001f)
      assertEquals(2f, timelinePrimary.agenda + timelinePrimary.timeline, 0.001f)
    }
  }

  @Test
  fun weekAndSinglePaneWidthsDoNotInventAPrimarySplit() {
    WindowWidth.entries.forEach { width ->
      assertEquals(CalendarPaneWeights(1f, 1f), calendarPaneWeights(width, CalendarView.Week))
    }
    listOf(WindowWidth.Compact, WindowWidth.Medium).forEach { width ->
      assertEquals(CalendarPaneWeights(1f, 1f), calendarPaneWeights(width, CalendarView.Agenda))
      assertEquals(CalendarPaneWeights(1f, 1f), calendarPaneWeights(width, CalendarView.Timeline))
    }
  }
}
