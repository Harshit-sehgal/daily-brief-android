package com.example.server.reconcile

import com.example.data.model.BriefingEvent
import com.example.data.repository.SyncMergePolicy
import com.example.server.db.Db
import com.example.server.db.Db.execute
import com.example.server.db.Db.query
import com.example.server.google.CalendarProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection

/**
 * The WP-11 concurrency design, executable: the provider fetch happens *outside* the
 * per-tenant advisory lock, so a slow network never queues the tenant's user edits. The
 * merge then runs against the state at write time — the policy is the only place that
 * decides what a re-sync may overwrite, exactly as on the device.
 */
object ReconcileWorker {
  fun reconcile(workspaceId: String, projectId: String, provider: CalendarProvider) {
    // Fetch outside the lock.
    val rangeStart = 0L
    val rangeEnd = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
    val incoming = provider.fetchEvents(rangeStart, rangeEnd)

    Db.inTransaction { conn ->
      // The tenant's write gate: a crashed transaction cannot leak it. The call returns a
      // row, so consume it via query rather than execute.
      conn.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", listOf(workspaceId)) {}
      conn.execute("SET LOCAL lock_timeout = '5s'")

      val existing = loadEvents(conn, workspaceId)
      val merged =
        SyncMergePolicy.merge(
          SyncMergePolicy.scopedToSources(incoming, listOf(provider.name())),
          existing,
          provider::providerIdOf,
        )
      val keptIds = merged.mapTo(mutableSetOf()) { it.id }
      conn.execute("DELETE FROM external_events WHERE workspace_id = ?", listOf(workspaceId))
      merged.forEach { insertEvent(conn, workspaceId, provider, it) }
    }
  }

  private fun loadEvents(conn: Connection, workspaceId: String): List<BriefingEvent> =
    conn.query(
      "SELECT id, title, start_time, end_time, source, description, is_deadline, is_urgent, is_all_day, location, kanban_status, kanban_board, user_edited " +
        "FROM external_events WHERE workspace_id = ?",
      listOf(workspaceId),
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

  private fun insertEvent(conn: Connection, workspaceId: String, provider: CalendarProvider, event: BriefingEvent) {
    conn.execute(
      "INSERT INTO external_events (id, workspace_id, title, start_time, end_time, source, description, is_deadline, is_urgent, is_all_day, location, kanban_status, kanban_board, user_edited) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
      listOf(
        event.id,
        workspaceId,
        event.title,
        event.startTime,
        event.endTime,
        event.source,
        event.description,
        event.isDeadline,
        event.isUrgent,
        event.isAllDay,
        event.location,
        event.kanbanStatus,
        event.kanbanBoard,
        event.userEdited,
      ),
    )
  }
}