package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.ui.viewmodel.BriefingViewModel
import com.example.ui.viewmodel.HomeCard
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeLayoutJourneyInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun inlineEditShowsReordersAndPersistsCardsAcrossRecreation() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val repository = BriefingRepository(context)
    val original = runBlocking { repository.readSetting(SettingKeys.HOME_CARDS) }
    try {
      runBlocking {
        repository.writeSetting(
          SettingKeys.HOME_CARDS,
          SettingKeys.encodeList(HomeCard.Defaults.map { it.key }),
        )
      }
      composeRule.onNodeWithTag("tab_Home").performClick()
      val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
      composeRule.waitUntil(timeoutMillis = 5_000) {
        viewModel.homeCards.value == HomeCard.Defaults
      }

      composeRule.onNodeWithTag("home_edit_layout").performClick()
      composeRule.onNodeWithTag("home_layout_editor").assertIsDisplayed()
      composeRule.onNodeWithTag("home_inline_card_actions").assertIsOff().performClick()
      composeRule.waitUntil(timeoutMillis = 5_000) {
        HomeCard.Actions in viewModel.homeCards.value
      }
      composeRule.onNodeWithTag("quick_timeline").performScrollTo().assertIsDisplayed()

      composeRule.onNodeWithContentDescription("Move Now down").performClick()
      composeRule.waitUntil(timeoutMillis = 5_000) {
        viewModel.homeCards.value.take(2) == listOf(HomeCard.UpNext, HomeCard.Focus)
      }

      composeRule.activityRule.scenario.recreate()
      composeRule.onNodeWithTag("home_layout_editor").assertIsDisplayed()
      composeRule.onNodeWithTag("home_inline_card_actions").assertIsOn()
      composeRule.onNodeWithTag("quick_timeline").performScrollTo().assertIsDisplayed()

      composeRule.onNodeWithTag("home_edit_layout").performClick()
      composeRule.onNodeWithTag("home_layout_editor").assertDoesNotExist()
    } finally {
      runBlocking {
        if (original == null) repository.deleteSetting(SettingKeys.HOME_CARDS)
        else repository.writeSetting(SettingKeys.HOME_CARDS, original)
      }
    }
  }
}
