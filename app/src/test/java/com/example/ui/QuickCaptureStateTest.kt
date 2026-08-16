package com.example.ui

import com.example.data.model.PlanPriority
import com.example.ui.viewmodel.EventDraft
import com.example.ui.viewmodel.PlanItemDraft
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickCaptureStateTest {
  @Test
  fun `task event task round trip keeps both complete type specific drafts`() {
    val task =
      PlanItemDraft(
        boardId = "plan-board",
        columnId = "in-progress",
        parentId = "parent",
        title = "Original task",
        notes = "Task notes",
        startConstraint = 100L,
        dueAt = 200L,
        effortMinutes = 75,
        progress = 35,
        priority = PlanPriority.URGENT,
        owner = "Alex",
        schedulingMode = "manual",
        locked = true,
        isMilestone = false,
      )
    val event =
      EventDraft(
        title = "Original event",
        description = "Event notes",
        startMs = 1_000L,
        endMs = 2_000L,
        isAllDay = true,
        isUrgent = true,
        isDeadline = true,
        board = "Calendar board",
        column = "Booked",
      )

    val eventAfterSwitch = resumeEventCapture(event, task)
    val editedEvent = eventAfterSwitch.copy(title = "Shared title", description = "Shared notes")
    val taskAfterReturn = resumeTaskCapture(task, editedEvent)

    assertEquals(event.copy(title = task.title, description = task.notes), eventAfterSwitch)
    assertEquals(task.copy(title = "Shared title", notes = "Shared notes"), taskAfterReturn)
  }
}
