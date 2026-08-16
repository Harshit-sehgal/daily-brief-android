package com.example.data.database

import androidx.room.Embedded
import androidx.room.Relation
import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem

/** One atomic Room snapshot for a Gantt row and every block it owns. */
data class PlanItemWithBlocks(
  @Embedded val item: PlanItem,
  @Relation(parentColumn = "id", entityColumn = "planItemId") val blocks: List<PlanBlock>,
)
