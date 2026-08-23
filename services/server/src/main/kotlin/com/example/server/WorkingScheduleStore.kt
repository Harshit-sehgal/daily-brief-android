package com.example.server

import com.example.contract.WorkScheduleWire
import com.example.contract.WorkScheduleWindowWire
import com.example.core.WorkingCalendar
import com.example.core.WorkingCalendarSpec
import com.example.core.WorkingDateOverride
import com.example.core.WorkingDayWindow
import com.example.core.WorkingWeekWindow
import com.example.server.db.Db
import com.example.server.db.Db.execute
import com.example.server.db.Db.query
import com.example.server.db.Db.queryOne
import kotlinx.datetime.TimeZone
import java.sql.Connection
import java.sql.ResultSet

/**
 * Workspace-owned source of truth for the default planning schedule.
 *
 * The planner remains pure: this object only validates and persists the schedule that clients
 * send in their planning requests. A workspace always gets one explicit default schedule, so a
 * client cannot silently fall back to a local machine's timezone or working hours.
 */
object WorkingScheduleStore {
  /** Pure route-boundary validation exposed to the server tests; persistence stays below it. */
  internal fun specFor(input: WorkScheduleWire): WorkingCalendarSpec = input.toSpec()

  fun getOrCreateDefault(workspaceId: String): WorkScheduleWire =
    Db.inTransaction { conn ->
      lockWorkspace(conn, workspaceId)
      if (findDefaultId(conn, workspaceId) == null) createDefault(conn, workspaceId, System.currentTimeMillis())
      readDefault(conn, workspaceId) ?: error("default working schedule was not created")
    }

  fun updateDefault(workspaceId: String, incoming: WorkScheduleWire): WorkScheduleWire {
    val spec = incoming.toSpec()
    val name = incoming.name.trim()
    require(name.isNotEmpty()) { "working schedule name cannot be empty" }

    return Db.inTransaction { conn ->
      lockWorkspace(conn, workspaceId)
      val now = System.currentTimeMillis()
      val scheduleId = findDefaultId(conn, workspaceId)
      if (scheduleId == null) {
        val id = Db.newId()
        conn.execute(
          "INSERT INTO work_schedules (id, workspace_id, name, name_key, time_zone_id, is_default, minimum_chunk_minutes, maximum_chunk_minutes, buffer_minutes, rank, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, true, ?, ?, ?, ?, ?, ?)",
          listOf(
            id,
            workspaceId,
            name,
            name.lowercase(),
            spec.zoneId,
            spec.minimumChunkMinutes,
            spec.maximumChunkMinutes,
            spec.bufferMinutes,
            incoming.rank,
            now,
            now,
          ),
        )
        replaceWindows(conn, id, workspaceId, incoming.windows, now)
      } else {
        conn.execute(
          "UPDATE work_schedules SET name = ?, name_key = ?, time_zone_id = ?, minimum_chunk_minutes = ?, maximum_chunk_minutes = ?, buffer_minutes = ?, updated_at = ? WHERE id = ? AND workspace_id = ? AND archived_at IS NULL",
          listOf(
            name,
            name.lowercase(),
            spec.zoneId,
            spec.minimumChunkMinutes,
            spec.maximumChunkMinutes,
            spec.bufferMinutes,
            now,
            scheduleId,
            workspaceId,
          ),
        )
        replaceWindows(conn, scheduleId, workspaceId, incoming.windows, now)
      }
      readDefault(conn, workspaceId) ?: error("updated working schedule disappeared")
    }
  }

  private fun lockWorkspace(conn: Connection, workspaceId: String) {
    require(
      conn.queryOne("SELECT id FROM workspaces WHERE id = ? FOR UPDATE", listOf(workspaceId)) { it.getString(1) } != null,
    ) { "workspace does not exist" }
  }

  private fun findDefaultId(conn: Connection, workspaceId: String): String? =
    conn.queryOne(
      "SELECT id FROM work_schedules WHERE workspace_id = ? AND is_default = true AND archived_at IS NULL ORDER BY rank, created_at LIMIT 1",
      listOf(workspaceId),
    ) { it.getString(1) }

  private fun createDefault(conn: Connection, workspaceId: String, now: Long): String {
    val id = Db.newId()
    conn.execute(
      "INSERT INTO work_schedules (id, workspace_id, name, name_key, time_zone_id, is_default, minimum_chunk_minutes, maximum_chunk_minutes, buffer_minutes, rank, created_at, updated_at) VALUES (?, ?, ?, ?, 'UTC', true, 30, 120, 0, 0, ?, ?)",
      listOf(id, workspaceId, DEFAULT_NAME, DEFAULT_NAME.lowercase(), now, now),
    )
    val windows = (CALENDAR_MONDAY..CALENDAR_FRIDAY).mapIndexed { index, day ->
      WorkScheduleWindowWire(
        id = "$id-weekly-$day",
        kind = "weekly",
        dayOfWeek = day,
        startMinute = DEFAULT_START_MINUTE,
        endMinute = DEFAULT_END_MINUTE,
        rank = index.toLong(),
      )
    }
    replaceWindows(conn, id, workspaceId, windows, now)
    return id
  }

  private fun replaceWindows(
    conn: Connection,
    scheduleId: String,
    workspaceId: String,
    windows: List<WorkScheduleWindowWire>,
    now: Long,
  ) {
    conn.execute("DELETE FROM work_schedule_windows WHERE schedule_id = ? AND workspace_id = ?", listOf(scheduleId, workspaceId))
    windows.forEach { window ->
      conn.execute(
        "INSERT INTO work_schedule_windows (id, schedule_id, workspace_id, kind, day_of_week, local_date, start_minute, end_minute, is_closed, rank, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        listOf(
          window.id,
          scheduleId,
          workspaceId,
          window.kind,
          window.dayOfWeek,
          window.localDate,
          window.startMinute,
          window.endMinute,
          window.isClosed,
          window.rank,
          now,
          now,
        ),
      )
    }
  }

  private fun readDefault(conn: Connection, workspaceId: String): WorkScheduleWire? {
    data class Header(
      val id: String,
      val name: String,
      val timeZoneId: String,
      val isDefault: Boolean,
      val minimumChunkMinutes: Int,
      val maximumChunkMinutes: Int,
      val bufferMinutes: Int,
      val rank: Long,
      val archivedAt: Long?,
    )

    val header =
      conn.queryOne(
        "SELECT id, name, time_zone_id, is_default, minimum_chunk_minutes, maximum_chunk_minutes, buffer_minutes, rank, archived_at FROM work_schedules WHERE workspace_id = ? AND is_default = true AND archived_at IS NULL ORDER BY rank, created_at LIMIT 1",
        listOf(workspaceId),
      ) { rs ->
        Header(
          id = rs.getString("id"),
          name = rs.getString("name"),
          timeZoneId = rs.getString("time_zone_id"),
          isDefault = rs.getBoolean("is_default"),
          minimumChunkMinutes = rs.getInt("minimum_chunk_minutes"),
          maximumChunkMinutes = rs.getInt("maximum_chunk_minutes"),
          bufferMinutes = rs.getInt("buffer_minutes"),
          rank = rs.getLong("rank"),
          archivedAt = rs.getLong("archived_at").takeIf { !rs.wasNull() },
        )
      } ?: return null

    val windows =
      conn.query(
        "SELECT id, kind, day_of_week, local_date, start_minute, end_minute, is_closed, rank FROM work_schedule_windows WHERE workspace_id = ? AND schedule_id = ? ORDER BY rank, id",
        listOf(workspaceId, header.id),
      ) { rs ->
        WorkScheduleWindowWire(
          id = rs.getString("id"),
          kind = rs.getString("kind"),
          dayOfWeek = nullableInt(rs, "day_of_week"),
          localDate = rs.getString("local_date"),
          startMinute = rs.getInt("start_minute"),
          endMinute = rs.getInt("end_minute"),
          isClosed = rs.getBoolean("is_closed"),
          rank = rs.getLong("rank"),
        )
      }
    val result = WorkScheduleWire(
      id = header.id,
      name = header.name,
      timeZoneId = header.timeZoneId,
      isDefault = header.isDefault,
      minimumChunkMinutes = header.minimumChunkMinutes,
      maximumChunkMinutes = header.maximumChunkMinutes,
      bufferMinutes = header.bufferMinutes,
      rank = header.rank,
      archivedAt = header.archivedAt,
      windows = windows,
    )
    specFor(result)
    return result
  }

  private fun WorkScheduleWire.toSpec(): WorkingCalendarSpec {
    require(archivedAt == null) { "an archived working schedule cannot be updated" }
    require(timeZoneId in TimeZone.availableZoneIds) { "unknown working-calendar time zone" }
    require(windows.all { it.id.isNotBlank() }) { "working-window IDs cannot be blank" }
    require(windows.map { it.id }.distinct().size == windows.size) { "working-window IDs must be unique" }
    require(windows.all { it.kind == "weekly" || it.kind == "date_override" }) {
      "unknown working-window kind"
    }
    val weekly =
      windows.filter { it.kind == "weekly" }.map { row ->
        require(row.dayOfWeek != null && row.localDate == null && !row.isClosed) {
          "weekly windows require a weekday and cannot be closed"
        }
        WorkingWeekWindow(requireNotNull(row.dayOfWeek), row.startMinute, row.endMinute)
      }
    val overrides =
      windows.filter { it.kind == "date_override" }.groupBy { row ->
        require(!row.localDate.isNullOrBlank() && row.dayOfWeek == null) {
          "date overrides require a local date and cannot contain a weekday"
        }
        row.localDate
      }.map { (localDate, rows) ->
        val closed = rows.filter { it.isClosed }
        require(closed.size <= 1 && (closed.isEmpty() || rows.size == 1)) {
          "a closed override cannot be combined with working windows"
        }
        WorkingDateOverride(
          localDate = requireNotNull(localDate),
          windows =
            if (closed.isNotEmpty()) emptyList()
            else rows.map { WorkingDayWindow(it.startMinute, it.endMinute) },
        )
      }
    return WorkingCalendarSpec(
      zoneId = timeZoneId,
      weeklyWindows = weekly,
      overrides = overrides,
      minimumChunkMinutes = minimumChunkMinutes,
      maximumChunkMinutes = maximumChunkMinutes,
      bufferMinutes = bufferMinutes,
    ).also(WorkingCalendar::validate)
  }

  private fun nullableInt(rs: ResultSet, column: String): Int? {
    val value = rs.getInt(column)
    return value.takeIf { !rs.wasNull() }
  }

  private const val DEFAULT_NAME = "Default working week"
  private const val DEFAULT_START_MINUTE = 9 * 60
  private const val DEFAULT_END_MINUTE = 17 * 60
  private const val CALENDAR_MONDAY = 2
  private const val CALENDAR_FRIDAY = 6
}
