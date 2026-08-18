package com.example

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.database.AppDatabase
import com.example.data.model.PlanItem
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanItemInput
import com.example.data.repository.PlanMutationType
import com.example.data.repository.PlanRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class BoardSelectionJourneyInstrumentedTest {
  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun compactBoardStagesHierarchyMovesAndAppliesOneAtomicStep() {
    val repository = PlanRepository(context)
    val settings = BriefingRepository(context)
    val database = AppDatabase.getDatabase(context)
    val token = System.nanoTime().toString(16)
    val originalView = runBlocking { settings.readSetting(SettingKeys.PLAN_VIEW) }
    var createdItems = emptyList<PlanItem>()
    var targetColumn: Pair<String, String>? = null
    var moveMutationsBefore = 0

    try {
      runBlocking {
        repository.ensureCatalog()
        val boardId = requireNotNull(repository.observeActiveBoardId().first())
        val columns = repository.observeColumns(boardId).first()
        require(columns.size >= 2)
        val source = columns.first()
        val target = columns.last()
        targetColumn = target.id to target.name
        val root =
          repository.saveItem(
            PlanItemInput(boardId = boardId, columnId = source.id, title = "Batch root $token")
          )
        val child =
          repository.saveItem(
            PlanItemInput(
              boardId = boardId,
              columnId = source.id,
              parentId = root.id,
              title = "Batch child $token",
            )
          )
        val separate =
          repository.saveItem(
            PlanItemInput(boardId = boardId, columnId = source.id, title = "Batch solo $token")
          )
        createdItems = listOf(root, child, separate)
        moveMutationsBefore =
          repository.observeMutationHistory(boardId, 200).first().count {
            it.mutationType == PlanMutationType.ITEM_MOVE
          }
      }
      val (root, child, separate) = createdItems
      val targetColumnId = requireNotNull(targetColumn).first

      composeRule.onNodeWithTag("tab_Plan").performClick()
      composeRule.onNodeWithText("Board").performClick()
      composeRule.waitUntil(10_000) {
        composeRule.onAllNodes(hasTestTag("screen_plan_board")).fetchSemanticsNodes().isNotEmpty()
      }
      composeRule.onNodeWithTag("board_select").performClick()
      composeRule.onNodeWithTag("board_selection_bar").assertIsDisplayed()

      composeRule.onNodeWithTag("board_select_all").performScrollTo().performClick()
      composeRule.onNodeWithTag("board_select_${root.id}").performScrollTo().assertIsOn()
      composeRule.onNodeWithTag("board_clear_selection").performScrollTo().performClick()
      composeRule.onNodeWithTag("board_select_${root.id}").performScrollTo().assertIsOff()

      composeRule
        .onNodeWithTag("plan_board_lanes")
        .performScrollToNode(hasTestTag("plan_board_lane_column:${requireNotNull(root.columnId)}"))
      listOf(root, child, separate).forEach { item ->
        composeRule
          .onNodeWithTag("board_select_${item.id}")
          .assertIsDisplayed()
          .performClick()
          .assertIsOn()
      }
      composeRule.waitUntil(5_000) {
        composeRule.onAllNodes(hasText("3 selected")).fetchSemanticsNodes().isNotEmpty()
      }
      composeRule.onNodeWithText("3 selected").assertIsDisplayed()

      // Long press is an accelerator, not a commit: it stages the complete selected hierarchy.
      composeRule
        .onNode(hasTestTag("plan_board_item") and hasText(root.title))
        .performTouchInput {
          down(center)
          advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
          // A drag detector stages on the post-hold movement stream, not a stationary long-click.
          moveBy(Offset(1f, 1f))
          up()
        }
      composeRule.onNodeWithTag("board_move_preview").assertIsDisplayed()
      composeRule.onNodeWithText("3 selected · 3 included", substring = true).assertIsDisplayed()
      composeRule.onNodeWithTag("board_batch_move_cancel").performClick()
      assertEquals(
        listOf(root, child, separate).associate { it.id to it.columnId },
        runBlocking {
          listOf(root.id, child.id, separate.id).associateWith {
            database.planDao().getItem(it)?.columnId
          }
        },
      )

      composeRule.onNodeWithTag("board_batch_move").performScrollTo().performClick()
      composeRule.onNodeWithTag("board_move_destination_$targetColumnId").performClick()
      composeRule.onNodeWithText("Review move to ${requireNotNull(targetColumn).second}").assertIsDisplayed()
      composeRule.onNodeWithText("one atomic Plan History step", substring = true).assertIsDisplayed()
      composeRule.onNodeWithTag("board_batch_move_confirm").performClick()

      composeRule.waitUntil(10_000) {
        runBlocking {
          listOf(root.id, child.id, separate.id).all {
            database.planDao().getItem(it)?.columnId == targetColumnId
          }
        }
      }
      composeRule.onNodeWithText("0 selected").assertIsDisplayed()
      val moveMutationsAfter =
        runBlocking {
          repository.observeMutationHistory(root.boardId, 200).first().count {
            it.mutationType == PlanMutationType.ITEM_MOVE
          }
        }
      assertEquals(1, moveMutationsAfter - moveMutationsBefore)

      composeRule.onNodeWithTag("board_selection_done").performScrollTo().performClick()
      composeRule
        .onNodeWithTag("plan_board_lanes")
        .performScrollToNode(hasTestTag("plan_board_lane_column:$targetColumnId"))
      composeRule
        .onNode(hasTestTag("plan_board_item") and hasText(root.title))
        .performCustomAccessibilityActionWithLabel(
          "Review move to ${columnsSourceName(repository, root.boardId)}"
        )
      composeRule.onNodeWithTag("board_move_preview").assertIsDisplayed()
      composeRule.onNodeWithTag("board_batch_move_cancel").performClick()
      assertEquals(targetColumnId, runBlocking { database.planDao().getItem(root.id)?.columnId })

      val leftDestination = leftDestinationName(repository, root.boardId, targetColumnId)
      composeRule
        .onNode(hasTestTag("plan_board_item") and hasText(root.title))
        .requestFocus()
        .performKeyInput {
          withKeyDown(Key.AltLeft) { pressKey(Key.DirectionLeft) }
        }
      composeRule.onNodeWithTag("board_move_preview").assertIsDisplayed()
      composeRule.onNodeWithText("Review move to $leftDestination").assertIsDisplayed()
      composeRule.onNodeWithTag("board_batch_move_cancel").performClick()
      assertEquals(targetColumnId, runBlocking { database.planDao().getItem(root.id)?.columnId })
    } finally {
      runBlocking {
        createdItems.asReversed().forEach { item ->
          database.planDao().getItem(item.id)?.let { database.planDao().deleteItem(it) }
        }
        if (originalView == null) settings.deleteSetting(SettingKeys.PLAN_VIEW)
        else settings.writeSetting(SettingKeys.PLAN_VIEW, originalView)
      }
    }
  }

  private fun columnsSourceName(repository: PlanRepository, boardId: String): String =
    runBlocking { repository.observeColumns(boardId).first().first().name }

  private fun leftDestinationName(
    repository: PlanRepository,
    boardId: String,
    currentColumnId: String,
  ): String =
    runBlocking {
      val columns = repository.observeColumns(boardId).first()
      val currentIndex = columns.indexOfFirst { it.id == currentColumnId }
      require(currentIndex >= 0) { "Current column is unavailable" }
      if (currentIndex == 0) "Inbox" else columns[currentIndex - 1].name
    }
}
