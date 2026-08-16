package com.example.ui.components

import com.example.data.model.PlanItem
import com.example.ui.viewmodel.PlanItemDraft
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskEditorPolicyTest {
  @Test
  fun `new tasks can choose a destination`() {
    val draft = draft(id = null, parentId = "parent")

    assertTrue(taskDestinationChangeAllowed(draft, draft, emptyList()))
  }

  @Test
  fun `existing standalone tasks can choose a destination`() {
    val draft = draft(id = "standalone")

    assertTrue(taskDestinationChangeAllowed(draft, draft, listOf(item("standalone"))))
  }

  @Test
  fun `existing children cannot split from their hierarchy in the editor`() {
    val original = draft(id = "child", parentId = "parent")

    assertFalse(
      taskDestinationChangeAllowed(
        draft = original.copy(parentId = null),
        original = original,
        items = listOf(item("parent"), item("child", parentId = "parent")),
      )
    )
  }

  @Test
  fun `existing parents move only as a group while archived children do not lock them`() {
    val parent = draft(id = "parent")

    assertFalse(
      taskDestinationChangeAllowed(
        parent,
        parent,
        listOf(item("parent"), item("active-child", parentId = "parent")),
      )
    )
    assertTrue(
      taskDestinationChangeAllowed(
        parent,
        parent,
        listOf(item("parent"), item("archived-child", parentId = "parent", archivedAt = 10L)),
      )
    )
  }

  private fun draft(id: String?, parentId: String? = null) =
    PlanItemDraft(
      id = id,
      boardId = "board",
      columnId = "column",
      parentId = parentId,
    )

  private fun item(id: String, parentId: String? = null, archivedAt: Long? = null) =
    PlanItem(
      id = id,
      boardId = "board",
      columnId = "column",
      parentId = parentId,
      title = id,
      rank = 0L,
      archivedAt = archivedAt,
      createdAt = 0L,
      updatedAt = 0L,
    )
}
