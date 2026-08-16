package com.example.ui.viewmodel

import com.example.data.prefs.SettingKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspacePreferencePolicyTest {
  @Test
  fun `legacy launch destinations follow the consolidated roots`() {
    assertEquals("Home", WorkspacePreferencePolicy.homeDestination(null))
    assertEquals("Calendar", WorkspacePreferencePolicy.homeDestination("Today"))
    assertEquals("Calendar", WorkspacePreferencePolicy.homeDestination("Week"))
    assertEquals("Plan", WorkspacePreferencePolicy.homeDestination("Board"))
    assertEquals("Home", WorkspacePreferencePolicy.homeDestination("retired"))
  }

  @Test
  fun `workspace subviews reject unknown stored values`() {
    assertEquals("Agenda", WorkspacePreferencePolicy.calendarView(null))
    assertEquals("Timeline", WorkspacePreferencePolicy.calendarView("Timeline"))
    assertEquals("Week", WorkspacePreferencePolicy.calendarView("Week"))
    assertEquals("Agenda", WorkspacePreferencePolicy.calendarView("Month"))
    assertEquals("Outline", WorkspacePreferencePolicy.planView("List"))
    assertEquals("Outline", WorkspacePreferencePolicy.planView("Outline"))
    assertEquals("Board", WorkspacePreferencePolicy.planView("Board"))
    assertEquals("Gantt", WorkspacePreferencePolicy.planView("Gantt"))
  }

  @Test
  fun `legacy Week launch selects Week when no Calendar view is stored`() {
    assertEquals("Week", WorkspacePreferencePolicy.calendarView(null, "Week"))
    assertEquals("Agenda", WorkspacePreferencePolicy.calendarView(null, "Today"))
    assertEquals("Agenda", WorkspacePreferencePolicy.calendarView(null, "Calendar"))
  }

  @Test
  fun `an explicit Calendar view wins over a legacy Week launch`() {
    assertEquals("Agenda", WorkspacePreferencePolicy.calendarView("Agenda", "Week"))
    assertEquals("Timeline", WorkspacePreferencePolicy.calendarView("Timeline", "Week"))
    assertEquals("Week", WorkspacePreferencePolicy.calendarView("Week", "Week"))
    assertEquals("Agenda", WorkspacePreferencePolicy.calendarView("Month", "Week"))
  }

  @Test
  fun `Today is agenda-only until the user opts into more sections`() {
    assertEquals(listOf(TodaySection.Agenda), TodaySection.Defaults)
    assertEquals(listOf(TodaySection.Agenda), WorkspacePreferencePolicy.todaySections(null))
  }

  @Test
  fun `an existing Today layout keeps its visibility and order`() {
    val stored =
      SettingKeys.encodeList(
        listOf(TodaySection.Brief.key, TodaySection.Summary.key, TodaySection.Conflicts.key)
      )

    assertEquals(
      listOf(TodaySection.Brief, TodaySection.Summary, TodaySection.Conflicts),
      WorkspacePreferencePolicy.todaySections(stored),
    )
  }

  @Test
  fun `the last Home card cannot be hidden`() {
    val stored = SettingKeys.encodeList(listOf(HomeCard.Board.key))

    assertEquals(
      listOf(HomeCard.Board),
      WorkspacePreferencePolicy.setHomeCardVisible(stored, HomeCard.Board, visible = false),
    )
  }

  @Test
  fun `hiding the final Today section falls back to Agenda`() {
    val stored = SettingKeys.encodeList(listOf(TodaySection.Brief.key))

    assertEquals(
      listOf(TodaySection.Agenda),
      WorkspacePreferencePolicy.setTodaySectionVisible(
        stored,
        TodaySection.Brief,
        visible = false,
      ),
    )
  }

  @Test
  fun `only Today collapse keys survive legacy Week preferences`() {
    val stored =
      SettingKeys.encodeList(
        listOf("week_1772928000000", TodaySection.Agenda.key, "unknown", TodaySection.Brief.key)
      )

    assertEquals(
      setOf(TodaySection.Agenda.key, TodaySection.Brief.key),
      WorkspacePreferencePolicy.collapsedTodaySections(stored),
    )
  }

  @Test
  fun `a stored layout of unrecognised keys falls back to the defaults`() {
    val stored = SettingKeys.encodeList(listOf("retired_block", ""))

    assertEquals(HomeCard.Defaults, WorkspacePreferencePolicy.homeCards(stored))
    assertEquals(TodaySection.Defaults, WorkspacePreferencePolicy.todaySections(stored))
  }

  @Test
  fun `moving a card past either end leaves the order alone`() {
    val stored =
      SettingKeys.encodeList(listOf(HomeCard.Focus.key, HomeCard.Board.key, HomeCard.UpNext.key))
    val order = listOf(HomeCard.Focus, HomeCard.Board, HomeCard.UpNext)

    assertEquals(order, WorkspacePreferencePolicy.moveHomeCard(stored, HomeCard.Focus, -1))
    assertEquals(order, WorkspacePreferencePolicy.moveHomeCard(stored, HomeCard.UpNext, 1))
    // A card that is currently hidden has no position to move from.
    assertEquals(order, WorkspacePreferencePolicy.moveHomeCard(stored, HomeCard.Progress, -1))
    assertEquals(
      listOf(HomeCard.Board, HomeCard.Focus, HomeCard.UpNext),
      WorkspacePreferencePolicy.moveHomeCard(stored, HomeCard.Focus, 1),
    )
  }

  @Test
  fun `moving a Today section reorders it without dropping the rest`() {
    val stored =
      SettingKeys.encodeList(
        listOf(TodaySection.Agenda.key, TodaySection.Summary.key, TodaySection.Brief.key)
      )

    assertEquals(
      listOf(TodaySection.Agenda, TodaySection.Brief, TodaySection.Summary),
      WorkspacePreferencePolicy.moveTodaySection(stored, TodaySection.Brief, -1),
    )
  }

  @Test
  fun `collapsing a section toggles it back off on the second call`() {
    val collapsed = WorkspacePreferencePolicy.toggleTodaySection(null, TodaySection.Agenda)
    assertEquals(setOf(TodaySection.Agenda.key), collapsed)

    assertEquals(
      emptySet<String>(),
      WorkspacePreferencePolicy.toggleTodaySection(
        SettingKeys.encodeList(collapsed.toList()),
        TodaySection.Agenda,
      ),
    )
  }

  @Test
  fun `revealing a section preserves existing order and appends it once`() {
    val stored = SettingKeys.encodeList(listOf(TodaySection.Agenda.key))

    assertEquals(
      listOf(TodaySection.Agenda, TodaySection.Brief),
      WorkspacePreferencePolicy.revealTodaySection(stored, TodaySection.Brief),
    )
    assertEquals(
      listOf(TodaySection.Agenda),
      WorkspacePreferencePolicy.revealTodaySection(stored, TodaySection.Agenda),
    )
  }
}
