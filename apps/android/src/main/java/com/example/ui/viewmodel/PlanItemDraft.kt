package com.example.ui.viewmodel

import com.example.data.model.PlanItem
import com.example.data.model.PlanPriority
import com.example.data.model.PlanSchedulingMode

/** Complete editable state for one app-owned task. */
data class PlanItemDraft(
  val id: String? = null,
  val boardId: String,
  val columnId: String? = null,
  val parentId: String? = null,
  val title: String = "",
  val notes: String = "",
  val startConstraint: Long? = null,
  val dueAt: Long? = null,
  val effortMinutes: Int? = null,
  val progress: Int = 0,
  val priority: String = PlanPriority.NORMAL,
  val owner: String = "",
  val schedulingMode: String = PlanSchedulingMode.AUTO,
  val locked: Boolean = false,
  val isMilestone: Boolean = false,
)

internal fun PlanItem.asDraft(): PlanItemDraft =
  PlanItemDraft(
    id = id,
    boardId = boardId,
    columnId = columnId,
    parentId = parentId,
    title = title,
    notes = notes.orEmpty(),
    startConstraint = startConstraint,
    dueAt = dueAt,
    effortMinutes = effortMinutes,
    progress = progress,
    priority = priority,
    owner = owner.orEmpty(),
    schedulingMode = schedulingMode,
    locked = locked,
    isMilestone = isMilestone,
  )
