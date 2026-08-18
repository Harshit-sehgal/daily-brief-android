package com.example.data.repository

import com.example.data.model.PlanItemSchedule

/** Result of resolving the working calendar for one task without an implicit fallback. */
data class PlanItemScheduleResolution(
  val calendar: PersistedWorkingCalendar?,
  val inheritsDefault: Boolean,
  val problem: String? = null,
)

enum class DefaultScheduleCompatibility {
  DEFAULT_ONLY,
  DIFFERENT_SCHEDULE,
  UNRESOLVED_ASSIGNMENT,
}

data class DefaultScheduleCompatibilityResult(
  val status: DefaultScheduleCompatibility,
  val itemId: String? = null,
)

/**
 * Pure assignment policy shared by the editor and Gantt preview.
 *
 * A mapped task must resolve to that exact active schedule. Only the absence of a mapping is allowed
 * to inherit the one active default; a stale or invalid explicit assignment never changes meaning.
 */
object PlanItemScheduleResolver {
  fun resolve(
    itemId: String,
    mappings: List<PlanItemSchedule>,
    calendars: List<PersistedWorkingCalendar>,
  ): PlanItemScheduleResolution {
    require(itemId.isNotBlank()) { "Task ID cannot be blank" }
    val itemMappings = mappings.filter { it.planItemId == itemId }
    if (itemMappings.size > 1) {
      return PlanItemScheduleResolution(
        calendar = null,
        inheritsDefault = false,
        problem = "This task has conflicting working-schedule assignments",
      )
    }
    val mapping = itemMappings.singleOrNull()
    if (mapping != null) {
      val assigned =
        calendars.singleOrNull {
          it.schedule.id == mapping.workScheduleId && it.schedule.archivedAt == null
        }
      return if (assigned != null) {
        PlanItemScheduleResolution(calendar = assigned, inheritsDefault = false)
      } else {
        PlanItemScheduleResolution(
          calendar = null,
          inheritsDefault = false,
          problem = "The assigned working schedule is unavailable or invalid",
        )
      }
    }

    val defaults = calendars.filter { it.schedule.isDefault && it.schedule.archivedAt == null }
    return if (defaults.size == 1) {
      PlanItemScheduleResolution(calendar = defaults.single(), inheritsDefault = true)
    } else {
      PlanItemScheduleResolution(
        calendar = null,
        inheritsDefault = true,
        problem = "Exactly one active default working schedule is required",
      )
    }
  }

  /**
   * Plan Health currently owns one capacity calendar. It must stop instead of applying that
   * calendar to a task whose explicit assignment has different or unknown meaning.
   */
  fun compareExplicitAssignmentsToDefault(
    itemIds: Collection<String>,
    mappings: List<PlanItemSchedule>,
    calendars: List<PersistedWorkingCalendar>,
    defaultScheduleId: String,
  ): DefaultScheduleCompatibilityResult {
    require(defaultScheduleId.isNotBlank()) { "Default working-schedule ID cannot be blank" }
    val activeIds = itemIds.toSet()
    val explicitlyAssignedIds =
      mappings
        .asSequence()
        .map(PlanItemSchedule::planItemId)
        .filter { it in activeIds }
        .distinct()
        .sorted()
        .toList()
    explicitlyAssignedIds.forEach { itemId ->
      val resolution = resolve(itemId, mappings, calendars)
      val assignedId = resolution.calendar?.schedule?.id
        ?: return DefaultScheduleCompatibilityResult(
          DefaultScheduleCompatibility.UNRESOLVED_ASSIGNMENT,
          itemId,
        )
      if (assignedId != defaultScheduleId) {
        return DefaultScheduleCompatibilityResult(
          DefaultScheduleCompatibility.DIFFERENT_SCHEDULE,
          itemId,
        )
      }
    }
    return DefaultScheduleCompatibilityResult(DefaultScheduleCompatibility.DEFAULT_ONLY)
  }
}
