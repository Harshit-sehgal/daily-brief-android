package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanRepository
import com.example.ui.viewmodel.BriefingViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanPaletteInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val repository by lazy { PlanRepository(context) }
  private val title = "Palette milestone ${System.nanoTime()}"
  private var previousBoardId: String? = null
  private var taskId: String? = null

  @After
  fun cleanUp() {
    runBlocking {
      taskId?.let { repository.deleteItem(it) }
      previousBoardId?.let { repository.setActiveBoard(it) }
    }
  }

  @Test
  fun stablePlanIdFindsAndOpensMilestoneWithBoardContext() {
    val (board, task) =
      runBlocking {
        repository.ensureCatalog()
        val boards = repository.observeBoards().first()
        previousBoardId = repository.observeActiveBoardId().first()
        val active = boards.firstOrNull { it.id == previousBoardId } ?: boards.first()
        repository.setActiveBoard(active.id)
        val createdTask =
          repository.saveItem(
            PlanItemInput(
              boardId = active.id,
              title = title,
              isMilestone = true,
            )
          )
        taskId = createdTask.id
        active to createdTask
      }

    val viewModel = ViewModelProvider(composeRule.activity)[BriefingViewModel::class.java]
    composeRule.waitUntil(timeoutMillis = 5_000) {
      viewModel.activePlanBoardId.value == board.id &&
        viewModel.planItems.value.any { it.id == task.id }
    }

    composeRule.onNodeWithTag("tab_Home").performClick()
    composeRule.onNodeWithTag("global_search").performClick()
    composeRule.onNodeWithTag("palette_query").performTextInput(task.id)
    composeRule.waitUntil(timeoutMillis = 5_000) {
      composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText("MILESTONE").assertIsDisplayed()
    composeRule.onNodeWithText("${board.name} · Inbox").assertIsDisplayed()
    composeRule.onNodeWithText(title).performClick()
    composeRule.onNodeWithTag("task_editor").assertIsDisplayed()
    composeRule.onNodeWithTag("task_title").assertTextContains(title)
    composeRule.onNodeWithTag("task_milestone").assertIsSelected()
  }
}
