package com.example.data.repository

import com.example.core.WorkingCalendar
import com.example.core.WorkingCalendarSpec
import com.example.data.model.PlanItem
import com.example.data.model.PlanItemSchedule

data class ResolvedPlanHealthSchedules(
  val specsByScheduleId: Map<String, WorkingCalendarSpec> = emptyMap(),
  val scheduleIdByItemId: Map<String, String> = emptyMap(),
  val problem: String? = null,
) {
  val isUsable: Boolean
    get() = problem == null && specsByScheduleId.isNotEmpty()
}

/** Strictly resolves every active task before multi-schedule capacity can run. */
object PlanHealthSchedulePolicy {
  fun resolve(
    items: List<PlanItem>,
    mappings: List<PlanItemSchedule>,
    calendars: List<PersistedWorkingCalendar>,
    defaultScheduleId: String,
  ): ResolvedPlanHealthSchedules {
    if (defaultScheduleId.isBlank()) {
      return ResolvedPlanHealthSchedules(problem = "The default working schedule is unavailable")
    }
    val activeCalendars = calendars.filter { it.schedule.archivedAt == null }
    if (activeCalendars.map { it.schedule.id }.distinct().size != activeCalendars.size) {
      return ResolvedPlanHealthSchedules(problem = "Active working schedules contain duplicate IDs")
    }
    val activeDefaults = activeCalendars.filter { it.schedule.isDefault }
    val default = activeDefaults.singleOrNull()
    if (default == null || default.schedule.id != defaultScheduleId) {
      return ResolvedPlanHealthSchedules(
        problem = "Exactly one matching active default working schedule is required",
      )
    }
    val scheduleByItemId = linkedMapOf<String, PersistedWorkingCalendar>()
    items.filter { it.archivedAt == null }.sortedBy(PlanItem::id).forEach { item ->
      val resolution = PlanItemScheduleResolver.resolve(item.id, mappings, activeCalendars)
      val calendar =
        resolution.calendar
          ?: return ResolvedPlanHealthSchedules(
            problem =
              resolution.problem
                ?: "Plan Health cannot resolve a task-specific working schedule",
          )
      scheduleByItemId[item.id] = calendar
    }
    val usedCalendars =
      (scheduleByItemId.values + default)
        .associateBy { it.schedule.id }
        .toSortedMap()
    usedCalendars.values.forEach { calendar ->
      calendarProblem(calendar)?.let { problem ->
        return ResolvedPlanHealthSchedules(problem = problem)
      }
    }
    return ResolvedPlanHealthSchedules(
      specsByScheduleId = usedCalendars.mapValues { (_, calendar) -> calendar.spec },
      scheduleIdByItemId =
        scheduleByItemId.mapValues { (_, calendar) -> calendar.schedule.id },
    )
  }

  private fun calendarProblem(calendar: PersistedWorkingCalendar): String? {
    val schedule = calendar.schedule
    val spec = calendar.spec
    if (
      schedule.timeZoneId != spec.zoneId ||
        schedule.minimumChunkMinutes != spec.minimumChunkMinutes ||
        schedule.maximumChunkMinutes != spec.maximumChunkMinutes ||
        schedule.bufferMinutes != spec.bufferMinutes
    ) {
      return "Working schedule ${schedule.name} is refreshing or internally inconsistent"
    }
    val validation = runCatching { WorkingCalendar.validate(spec) }.exceptionOrNull()
    return validation?.let {
      "Working schedule ${schedule.name} is invalid: " +
        (it.message ?: "its availability cannot be verified")
    }
  }
}
