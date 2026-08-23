package com.example.server.reconcile

import com.example.data.model.BriefingEvent
import com.example.data.repository.SyncMergePolicy
import com.example.server.db.Db
import com.example.server.db.Db.execute
import com.example.server.db.Db.newId
import com.example.server.db.Db.query
import com.example.server.google.CalendarProvider
import com.example.server.google.CalendarProviderFailure
import java.io.IOException
import java.sql.Connection

/**
 * The WP-11 concurrency design, executable: the provider fetch happens *outside* the
 * per-tenant advisory lock, so a slow network never queues the tenant's user edits. The
 * merge then runs against the state at write time — the policy is the only place that
 * decides what a re-sync may overwrite, exactly as on the device.
 *
 * WP-14 hardening: the fetch is retried with bounded exponential backoff (a provider blip
 * must not fail a sync that a second attempt would complete), and every run — committed or
 * failed — records a row in `sync_runs` so sync health is measurable without touching the
 * provider. A failed merge never leaves partial rows: the delete-and-insert is one
 * transaction, so a mid-merge failure rolls back cleanly and the ledger row is the record.
 */
object ReconcileWorker {
  private const val FETCH_ATTEMPTS = 3
  private const val BACKOFF_MS = 200L

  fun reconcile(workspaceId: String, projectId: String, provider: CalendarProvider) {
    val startedAt = System.currentTimeMillis()
    var eventsSeen = 0
    try {
      // Fetch outside the lock, with bounded backoff. Only transient provider failures are
      // retried — a 401/403, malformed payload, or validation error is not made better by
      // immediately asking again.
      val incoming =
        retry(FETCH_ATTEMPTS, BACKOFF_MS) {
          val rangeStart = 0L
          val rangeEnd = startedAt + 30L * 24 * 60 * 60 * 1000
          provider.fetchEvents(rangeStart, rangeEnd)
        }
      eventsSeen = incoming.size
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
      record(workspaceId, startedAt, incoming.size, "ok")
    } catch (t: Throwable) {
      // Fetch and merge failures both belong in the ledger. If the database itself is down,
      // preserve the original provider/merge exception rather than masking it with a ledger
      // write failure.
      runCatching { record(workspaceId, startedAt, eventsSeen, "failed") }
      throw t
    }
  }

  /** Bounded retry with backoff: 3 attempts, 200 ms then 400 ms. Failures are rethrown. */
  private fun <T> retry(attempts: Int, backoffMs: Long, block: () -> T): T {
    return ReconcileRetry.run(attempts, backoffMs, block)
  }

  private fun record(workspaceId: String, startedAt: Long, eventsSeen: Int, status: String) {
    val finishedAt = System.currentTimeMillis()
    Db.dataSource.connection.use { conn ->
      conn.execute(
        "INSERT INTO sync_runs (id, workspace_id, started_at, finished_at, duration_ms, events_seen, status) VALUES (?, ?, ?, ?, ?, ?, ?)",
        listOf(newId(), workspaceId, startedAt, finishedAt, finishedAt - startedAt, eventsSeen, status),
      )
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

internal object ReconcileRetry {
  fun <T> run(attempts: Int, backoffMs: Long, block: () -> T): T {
    require(attempts > 0) { "attempts must be positive" }
    require(backoffMs >= 0) { "backoffMs must not be negative" }
    var last: Throwable? = null
    repeat(attempts) { attempt ->
      try {
        return block()
      } catch (t: Throwable) {
        last = t
        if (attempt == attempts - 1 || !isTransient(t)) throw t
        Thread.sleep(backoffMs shl attempt)
      }
    }
    throw last ?: IllegalStateException("retry did not execute")
  }

  internal fun isTransient(t: Throwable): Boolean =
    when (t) {
      is CalendarProviderFailure -> t.statusCode == 408 || t.statusCode == 429 || t.statusCode >= 500
      is IOException -> true
      else -> false
    }
}
