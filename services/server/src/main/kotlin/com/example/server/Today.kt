package com.example.server

import com.example.core.ScheduleAnalysis
import com.example.data.model.BriefingEvent
import com.example.server.db.Db
import com.example.server.db.Db.query
import com.example.core.Mapping.toWire
import kotlinx.serialization.Serializable

/**
 * The Today screen's data: the day's events and plan blocks, the overlaps between them
 * (ScheduleAnalysis — the same code the device runs), and the day's shape. No Gemini in the
 * slice; summaries are WP-15.
 */
object Today {
  @Serializable
  data class TodayBlockResponse(
    val id: String,
    val planItemId: String,
    val title: String,
    val projectName: String? = null,
    val startAt: Long,
    val endAt: Long,
    val position: Int = 0,
    val locked: Boolean = false,
    val linkedEventId: String? = null,
  )

  @Serializable
  data class TodayResponse(
    val date: String,
    val events: List<com.example.contract.ExternalEventWire>,
    val blocks: List<TodayBlockResponse>,
    val conflicts: List<com.example.contract.PlanConflictWire>,
    val busyMinutes: Int,
    val freeMinutes: Int,
  )

  /** Includes planned work in the same overlap sweep as provider events. */
  internal fun conflictWires(
    events: List<BriefingEvent>,
    plannedBlocks: List<BriefingEvent>,
  ): List<com.example.contract.PlanConflictWire> =
    ScheduleAnalysis.findConflicts(events + plannedBlocks).map { conflict ->
      com.example.contract.PlanConflictWire(
        first = conflict.first.toWire(),
        second = conflict.second.toWire(),
        overlapMs = conflict.overlapMs,
      )
    }

  fun forWorkspace(workspaceId: String, nowMs: Long): TodayResponse {
    val dayStart = java.time.Instant.ofEpochMilli(nowMs).truncatedTo(java.time.temporal.ChronoUnit.DAYS).toEpochMilli()
    val dayEnd = dayStart + 24 * 60 * 60 * 1000L

    val events =
      Db.dataSource.connection.use { conn ->
        conn.query(
          "SELECT id, title, start_time, end_time, source, description, is_deadline, is_urgent, is_all_day, location, kanban_status, kanban_board, user_edited " +
            "FROM external_events WHERE workspace_id = ? AND start_time < ? AND end_time > ?",
          listOf(workspaceId, dayEnd, dayStart),
        ) { rs ->
          BriefingEvent(
            id = rs.getString("id"),
            title = rs.getString("title"),
            startTime = rs.getLong("start_time"),
            endTime = rs.getLong("end_time"),
            source = rs.getString("source"),
            description = rs.getString("description"),
            isDeadline = rs.getBoolean("is_deadline"),
            isUrgent = rs.getBoolean("is_urgent"),
            isAllDay = rs.getBoolean("is_all_day"),
            location = rs.getString("location"),
            kanbanStatus = rs.getString("kanban_status"),
            kanbanBoard = rs.getString("kanban_board"),
            userEdited = rs.getBoolean("user_edited"),
          )
        }
      }

    val blocks =
      Db.dataSource.connection.use { conn ->
        conn.query(
          "SELECT b.id, b.task_id, t.title, p.name AS project_name, b.start_at, b.end_at, b.position, b.locked, b.linked_event_id " +
            "FROM scheduled_blocks b " +
            "LEFT JOIN tasks t ON t.project_id = b.project_id AND t.id = b.task_id " +
            "LEFT JOIN projects p ON p.id = b.project_id " +
            "WHERE b.workspace_id = ? AND b.start_at < ? AND b.end_at > ?",
          listOf(workspaceId, dayEnd, dayStart),
        ) { rs ->
          TodayBlockResponse(
            id = rs.getString("id"),
            planItemId = rs.getString("task_id"),
            title = rs.getString("title") ?: rs.getString("task_id"),
            projectName = rs.getString("project_name"),
            startAt = rs.getLong("start_at"),
            endAt = rs.getLong("end_at"),
            position = rs.getInt("position"),
            locked = rs.getBoolean("locked"),
            linkedEventId = rs.getString("linked_event_id"),
          )
        }
      }

    val blockEvents = blocks.map { block ->
      BriefingEvent(
        id = "plan:${block.id}",
        title = block.title,
        startTime = block.startAt,
        endTime = block.endAt,
        source = "plan",
        description = null,
        isDeadline = false,
        isUrgent = false,
        isAllDay = false,
        location = null,
        kanbanStatus = "",
        kanbanBoard = block.projectName.orEmpty(),
        userEdited = false,
      )
    }

    val conflicts = conflictWires(events, blockEvents)

    val busy =
      (events.filterNot { it.isAllDay } + blockEvents)
        .mapNotNull { e ->
          val s = maxOf(e.startTime, dayStart)
          val t = minOf(e.endTime, dayEnd)
          if (t > s) s to t else null
        }
        .sortedBy { it.first }
        .fold(0L) { acc, (s, t) -> acc + (t - s) }

    return TodayResponse(
      date = java.time.Instant.ofEpochMilli(nowMs).toString().substringBefore("T"),
      events = events.map { it.toWire() },
      blocks = blocks,
      conflicts = conflicts,
      busyMinutes = (busy / 60_000L).toInt(),
      freeMinutes = maxOf(0, 24 * 60 - (busy / 60_000L).toInt()),
    )
  }
}
