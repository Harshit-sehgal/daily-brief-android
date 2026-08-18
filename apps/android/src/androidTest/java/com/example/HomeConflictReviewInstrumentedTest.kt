package com.example

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.ScheduleAnalysis
import com.example.data.database.AppDatabase
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.ui.viewmodel.BriefingViewModel
import com.example.ui.viewmodel.HomeCard
import com.example.ui.viewmodel.TodaySection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeConflictReviewInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val repository by lazy { BriefingRepository(context) }
  private val eventDao by lazy { AppDatabase.getDatabase(context).eventDao() }
  private val firstId = "home_conflict_a_${System.nanoTime()}"
  private val secondId = "home_conflict_b_${System.nanoTime()}"
  private val firstTitle = "Home conflict A ${System.nanoTime()}"
  private val secondTitle = "Home conflict B ${System.nanoTime()}"
  private var oldHomeCards: String? = null
  private var oldSections: String? = null
  private var oldCollapsed: String? = null
  private var capturedSettings = false

  @After
  fun cleanUp() {
    runBlocking {
      eventDao.deleteEventById(firstId)
      eventDao.deleteEventById(secondId)
      if (capturedSettings) {
        restoreSetting(SettingKeys.HOME_CARDS, oldHomeCards)
        restoreSetting(SettingKeys.TODAY_SECTIONS, oldSections)
        restoreSetting(SettingKeys.COLLAPSED_SECTIONS, oldCollapsed)
      }
    }
  }

  @Test
  fun reviewRevealsAndExpandsConflictsBeforeOpeningAgenda() {
    val day = ScheduleAnalysis.startOfDay(System.currentTimeMillis())
    runBlocking {
      oldHomeCards = repository.readSetting(SettingKeys.HOME_CARDS)
      oldSections = repository.readSetting(SettingKeys.TODAY_SECTIONS)
      oldCollapsed = repository.readSetting(SettingKeys.COLLAPSED_SECTIONS)
      capturedSettings = true
      repository.writeSettingsAtomically(
        mapOf(
          SettingKeys.HOME_CARDS to
            SettingKeys.encodeList(listOf(HomeCard.Focus.key, HomeCard.Attention.key)),
          SettingKeys.TODAY_SECTIONS to SettingKeys.encodeList(listOf(TodaySection.Agenda.key)),
          SettingKeys.COLLAPSED_SECTIONS to
            SettingKeys.encodeList(listOf(TodaySection.Conflicts.key)),
        )
      )
      eventDao.insertEvents(
        listOf(
          event(firstId, firstTitle, day + 10 * HOUR, day + 11 * HOUR),
          event(secondId, secondTitle, day + 10 * HOUR + 30 * MINUTE, day + 11 * HOUR + 30 * MINUTE),
        )
      )
    }

    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.runOnIdle { viewModel.selectToday() }
    composeRule.onNodeWithTag("tab_Home").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      viewModel.todayEvents.value.any { it.id == firstId } &&
        viewModel.todayEvents.value.any { it.id == secondId } &&
        composeRule.onAllNodesWithText("clash", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText("Review").performClick()

    val expandedConflicts =
      hasText("CONFLICTS") and
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodes(expandedConflicts).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("screen_today").assertIsDisplayed()
    composeRule.onNode(expandedConflicts).assertIsDisplayed()
  }

  private suspend fun restoreSetting(key: String, value: String?) {
    if (value == null) repository.deleteSetting(key) else repository.writeSetting(key, value)
  }

  private fun event(id: String, title: String, start: Long, end: Long) =
    BriefingEvent(
      id = id,
      title = title,
      startTime = start,
      endTime = end,
      source = EventSource.MANUAL,
      description = null,
      isDeadline = false,
      isUrgent = false,
    )

  private companion object {
    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
  }
}
