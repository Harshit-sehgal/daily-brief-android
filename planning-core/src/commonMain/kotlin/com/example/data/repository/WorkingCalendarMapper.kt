package com.example.data.repository

import com.example.core.WorkingCalendar
import com.example.core.WorkingCalendarSpec
import com.example.core.WorkingDateOverride
import com.example.core.WorkingDayWindow
import com.example.core.WorkingWeekWindow
import com.example.data.database.nameKey
import com.example.data.database.stableId
import com.example.data.database.WorkScheduleDefaults
import com.example.data.model.WorkSchedule
import com.example.data.model.WorkScheduleWindow
import com.example.data.model.WorkScheduleWindowKind

/** A database schedule and its windows after strict conversion to the pure planning contract. */
data class PersistedWorkingCalendar(
  val schedule: WorkSchedule,
  val windows: List<WorkScheduleWindow>,
  val spec: WorkingCalendarSpec,
)

/**
 * Strict, pure conversion between the normalized Room model and [WorkingCalendarSpec].
 *
 * No row is ignored. A foreign schedule ID, unknown kind, ambiguous closed override, malformed
 * field combination, duplicate ID/date, invalid wall-clock window, overlap, or unknown time zone
 * rejects the complete value before a repository can persist it.
 */
object WorkingCalendarMapper {
  fun fromEntities(
    schedule: WorkSchedule,
    windows: List<WorkScheduleWindow>,
  ): PersistedWorkingCalendar {
    require(schedule.id.isNotBlank()) { "Working schedule ID cannot be blank" }
    require(schedule.timeZoneId.isNotBlank()) { "Working schedule time zone must be explicit" }
    require(windows.all { it.id.isNotBlank() }) { "Working-window ID cannot be blank" }
    require(windows.map { it.id }.distinct().size == windows.size) {
      "Working-window IDs must be unique"
    }
    require(windows.all { it.scheduleId == schedule.id }) {
      "Every working window must belong to the mapped schedule"
    }

    val weekly = mutableListOf<WorkingWeekWindow>()
    val dateRows = linkedMapOf<String, MutableList<WorkScheduleWindow>>()
    windows.forEach { row ->
      require(row.kind in WorkScheduleWindowKind.All) { "Unknown working-window kind" }
      when (row.kind) {
        WorkScheduleWindowKind.WEEKLY -> {
          val dayOfWeek = requireNotNull(row.dayOfWeek) { "A weekly window requires a weekday" }
          require(row.localDate == null) { "A weekly window cannot contain a local date" }
          require(!row.isClosed) { "Weekly availability cannot use a closed-date sentinel" }
          weekly += WorkingWeekWindow(dayOfWeek, row.startMinute, row.endMinute)
        }
        WorkScheduleWindowKind.DATE_OVERRIDE -> {
          require(row.dayOfWeek == null) { "A date override cannot contain a weekday" }
          val localDate = requireNotNull(row.localDate) { "A date override requires a local date" }
          require(localDate.isNotBlank()) { "A date override requires a local date" }
          if (row.isClosed) {
            require(row.startMinute == row.endMinute) {
              "A closed-date sentinel must have zero duration"
            }
            require(row.startMinute in 0..MINUTES_PER_DAY) {
              "A closed-date sentinel must stay within its local day"
            }
          }
          dateRows.getOrPut(localDate) { mutableListOf() } += row
        }
      }
    }

    val overrides =
      dateRows.entries.sortedBy { it.key }.map { (localDate, rows) ->
        val closed = rows.filter { it.isClosed }
        require(closed.size <= 1) { "A date can have only one closed override" }
        require(closed.isEmpty() || rows.size == 1) {
          "A closed override cannot be combined with working windows"
        }
        WorkingDateOverride(
          localDate = localDate,
          windows =
            if (closed.isNotEmpty()) emptyList()
            else
              rows
                .sortedWith(compareBy<WorkScheduleWindow>({ it.startMinute }, { it.endMinute }, { it.id }))
                .map { WorkingDayWindow(it.startMinute, it.endMinute) },
        )
      }
    val spec =
      WorkingCalendarSpec(
        zoneId = schedule.timeZoneId,
        weeklyWindows =
          weekly.sortedWith(
            compareBy<WorkingWeekWindow>({ it.dayOfWeek }, { it.startMinute }, { it.endMinute })
          ),
        overrides = overrides,
        minimumChunkMinutes = schedule.minimumChunkMinutes,
        maximumChunkMinutes = schedule.maximumChunkMinutes,
        bufferMinutes = schedule.bufferMinutes,
      )
    WorkingCalendar.validate(spec)
    return PersistedWorkingCalendar(
      schedule = schedule,
      windows = windows.sortedWith(windowComparator),
      spec = spec,
    )
  }

  /** Builds a complete replacement set, then decodes it again before returning. */
  fun forUpdate(
    schedule: WorkSchedule,
    spec: WorkingCalendarSpec,
    updatedAt: Long,
  ): PersistedWorkingCalendar {
    require(schedule.archivedAt == null) { "An archived working schedule cannot be updated" }
    WorkingCalendar.validate(spec)
    val updatedSchedule =
      schedule.copy(
        timeZoneId = spec.zoneId,
        minimumChunkMinutes = spec.minimumChunkMinutes,
        maximumChunkMinutes = spec.maximumChunkMinutes,
        bufferMinutes = spec.bufferMinutes,
        updatedAt = updatedAt,
      )
    val rows = mutableListOf<WorkScheduleWindow>()
    spec.weeklyWindows
      .sortedWith(
        compareBy<WorkingWeekWindow>({ it.dayOfWeek }, { it.startMinute }, { it.endMinute })
      )
      .groupBy { it.dayOfWeek }
      .forEach { (dayOfWeek, dayWindows) ->
        dayWindows.forEachIndexed { index, window ->
          rows +=
            WorkScheduleWindow(
              id = "${schedule.id}-weekly-$dayOfWeek-${index + 1}",
              scheduleId = schedule.id,
              kind = WorkScheduleWindowKind.WEEKLY,
              dayOfWeek = dayOfWeek,
              localDate = null,
              startMinute = window.startMinute,
              endMinute = window.endMinute,
              isClosed = false,
              rank = rankFor(index),
              createdAt = updatedAt,
              updatedAt = updatedAt,
            )
        }
      }
    spec.overrides.sortedBy { it.localDate }.forEach { override ->
      if (override.windows.isEmpty()) {
        rows +=
          WorkScheduleWindow(
            id = "${schedule.id}-date-${override.localDate}-closed",
            scheduleId = schedule.id,
            kind = WorkScheduleWindowKind.DATE_OVERRIDE,
            dayOfWeek = null,
            localDate = override.localDate,
            startMinute = 0,
            endMinute = 0,
            isClosed = true,
            rank = rankFor(0),
            createdAt = updatedAt,
            updatedAt = updatedAt,
          )
      } else {
        override.windows
          .sortedWith(compareBy<WorkingDayWindow>({ it.startMinute }, { it.endMinute }))
          .forEachIndexed { index, window ->
            rows +=
              WorkScheduleWindow(
                id = "${schedule.id}-date-${override.localDate}-${index + 1}",
                scheduleId = schedule.id,
                kind = WorkScheduleWindowKind.DATE_OVERRIDE,
                dayOfWeek = null,
                localDate = override.localDate,
                startMinute = window.startMinute,
                endMinute = window.endMinute,
                isClosed = false,
                rank = rankFor(index),
                createdAt = updatedAt,
                updatedAt = updatedAt,
              )
          }
      }
    }
    return fromEntities(updatedSchedule, rows)
  }

  /** The exact stable seed used by the 6 -> 7 migration, with the zone supplied explicitly. */
  fun defaultSchedule(timeZoneId: String, now: Long): PersistedWorkingCalendar {
    val schedule =
      WorkSchedule(
        id = WorkScheduleDefaults.ID,
        name = WorkScheduleDefaults.NAME,
        nameKey = nameKey(WorkScheduleDefaults.NAME),
        timeZoneId = timeZoneId,
        isDefault = true,
        minimumChunkMinutes = 30,
        maximumChunkMinutes = 120,
        bufferMinutes = 0,
        rank = RANK_GAP,
        archivedAt = null,
        createdAt = now,
        updatedAt = now,
      )
    // Stored weekday numbers keep java.util.Calendar's convention — Sunday is 1 — so
    // Monday..Friday are 2..6, and the default schedule is simply the working week.
    val windows =
      (CALENDAR_MONDAY..CALENDAR_FRIDAY).mapIndexed { index, dayOfWeek ->
        WorkScheduleWindow(
          id = "${WorkScheduleDefaults.ID}-weekday-$dayOfWeek",
          scheduleId = WorkScheduleDefaults.ID,
          kind = WorkScheduleWindowKind.WEEKLY,
          dayOfWeek = dayOfWeek,
          localDate = null,
          startMinute = 9 * 60,
          endMinute = 17 * 60,
          isClosed = false,
          rank = rankFor(index),
          createdAt = now,
          updatedAt = now,
        )
      }
    return fromEntities(schedule, windows)
  }

  private fun rankFor(index: Int): Long = (index + 1L) * RANK_GAP

  private val windowComparator =
    compareBy<WorkScheduleWindow>(
      { it.kind },
      { it.dayOfWeek ?: Int.MAX_VALUE },
      { it.localDate.orEmpty() },
      { it.rank },
      { it.startMinute },
      { it.id },
    )

  private const val MINUTES_PER_DAY = 24 * 60
  private const val CALENDAR_MONDAY = 2
  private const val CALENDAR_FRIDAY = 6
  private const val RANK_GAP = 1_000_000L
}
