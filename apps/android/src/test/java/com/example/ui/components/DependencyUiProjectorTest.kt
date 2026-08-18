package com.example.ui.components

import com.example.data.model.PlanDependency
import com.example.data.model.PlanDependencyType
import com.example.data.model.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DependencyUiProjectorTest {
  @Test
  fun `projects every relationship type and signed lead lag by task title`() {
    val items = listOf(item("a", "Research", rank = 2), item("b", "Draft", rank = 1))
    val dependencies =
      listOf(
        dependency("fs", "a", "b", PlanDependencyType.FINISH_TO_START, 30),
        dependency("ss", "a", "b", PlanDependencyType.START_TO_START, -15),
        dependency("ff", "a", "b", PlanDependencyType.FINISH_TO_FINISH, 0),
        dependency("sf", "a", "b", PlanDependencyType.START_TO_FINISH, 90),
      )

    val rows = DependencyUiProjector.rows(BoardId, items, dependencies).associateBy { it.dependency.id }

    assertEquals("Research", rows.getValue("fs").predecessorTitle)
    assertEquals("Draft", rows.getValue("fs").successorTitle)
    assertEquals("FS", rows.getValue("fs").typeCode)
    assertEquals("+30m lag", rows.getValue("fs").offsetLabel)
    assertEquals("SS", rows.getValue("ss").typeCode)
    assertEquals("−15m lead", rows.getValue("ss").offsetLabel)
    assertEquals("FF", rows.getValue("ff").typeCode)
    assertEquals("0m · no lead or lag", rows.getValue("ff").offsetLabel)
    assertEquals("SF", rows.getValue("sf").typeCode)
    assertEquals("+1h 30m lag", rows.getValue("sf").offsetLabel)
  }

  @Test
  fun `keeps invalid rows visible and explains missing archived self and unknown relationships`() {
    val items = listOf(item("active", "Active"), item("archived", "Old", archivedAt = 9))
    val rows =
      DependencyUiProjector.rows(
        BoardId,
        items,
        listOf(
          dependency("missing", "gone", "active"),
          dependency("archived", "archived", "active"),
          dependency("self", "active", "active"),
          dependency("unknown", "active", "archived", type = "mystery"),
        ),
      ).associateBy { it.dependency.id }

    assertEquals("Missing task · gone", rows.getValue("missing").predecessorTitle)
    assertEquals("Predecessor task is missing.", rows.getValue("missing").issue)
    assertEquals("Old (archived)", rows.getValue("archived").predecessorTitle)
    assertEquals("Predecessor task is archived.", rows.getValue("archived").issue)
    assertEquals("A task cannot depend on itself.", rows.getValue("self").issue)
    assertEquals("?", rows.getValue("unknown").typeCode)
    assertEquals("Successor task is archived.", rows.getValue("unknown").issue)
    assertNotNull(rows.getValue("unknown").accessibilityLabel)
  }

  @Test
  fun `task options stay board scoped active ordered and disambiguate duplicate titles`() {
    val options =
      DependencyUiProjector.taskOptions(
        BoardId,
        listOf(
          item("second-222222", "Same", rank = 2),
          item("first-111111", "Same", rank = 1),
          item("archived", "Archived", rank = 0, archivedAt = 5),
          item("other", "Other board", boardId = "other", rank = 0),
        ),
      )

    assertEquals(listOf("first-111111", "second-222222"), options.map { it.item.id })
    assertEquals(listOf("Same · 111111", "Same · 222222"), options.map { it.label })
  }

  @Test
  fun `draft policy rejects unavailable self and duplicate edges but permits editing same edge`() {
    val options = DependencyUiProjector.taskOptions(BoardId, listOf(item("a", "A"), item("b", "B")))
    val existing = dependency("dep", "a", "b")

    assertEquals(
      "Choose active tasks from this Plan.",
      DependencyUiProjector.draftError(
        DependencyDraft("missing", "b", PlanDependencyType.FINISH_TO_START, 0),
        options,
        listOf(existing),
        null,
      ),
    )
    assertEquals(
      "Choose two different tasks.",
      DependencyUiProjector.draftError(
        DependencyDraft("a", "a", PlanDependencyType.FINISH_TO_START, 0),
        options,
        listOf(existing),
        null,
      ),
    )
    assertEquals(
      "That relationship already exists.",
      DependencyUiProjector.draftError(
        DependencyDraft("a", "b", PlanDependencyType.START_TO_START, 0),
        options,
        listOf(existing),
        null,
      ),
    )
    assertNull(
      DependencyUiProjector.draftError(
        DependencyDraft("a", "b", PlanDependencyType.START_TO_START, -10),
        options,
        listOf(existing),
        "dep",
      )
    )
  }

  private fun item(
    id: String,
    title: String,
    boardId: String = BoardId,
    rank: Long = 0,
    archivedAt: Long? = null,
  ) =
    PlanItem(
      id = id,
      boardId = boardId,
      title = title,
      rank = rank,
      archivedAt = archivedAt,
      createdAt = rank,
      updatedAt = rank,
    )

  private fun dependency(
    id: String,
    predecessorId: String,
    successorId: String,
    type: String = PlanDependencyType.FINISH_TO_START,
    lagMinutes: Int = 0,
  ) =
    PlanDependency(
      id = id,
      boardId = BoardId,
      predecessorId = predecessorId,
      successorId = successorId,
      type = type,
      lagMinutes = lagMinutes,
      createdAt = 1,
      updatedAt = 1,
    )

  companion object {
    private const val BoardId = "board"
  }
}
