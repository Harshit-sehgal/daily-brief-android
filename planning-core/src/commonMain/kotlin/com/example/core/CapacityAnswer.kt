package com.example.core

import com.example.data.model.PlanPriority

/**
 * The product answer for the ICP's actual question — "can I take another client?" — behind
 * three numbers and one sentence (02: Capacity hides the max-flow machinery).
 *
 * [answer] takes the range's [PlanHealthResult] (computed by [MultiSchedulePlanHealth], which
 * owns the max-flow) plus the proposed client load, and returns:
 * - availableMinutes: working time after fixed commitments in the range,
 * - plannedMinutes: what is on the plate — scheduled plus still-unscheduled demand,
 * - spareMinutes: what is left after the existing planned blocks,
 * - a verdict and one sentence, and, when the verdict is MOVE, the deferrable tasks that would
 *   have to leave the range to make room.
 *
 * A task is deferrable only if it is due strictly after [rangeEnd] or has no due date: work due
 * inside the range cannot "move" without breaking the deadline, and an overdue task is already
 * late. The verdict is a floor, never a promise — incomplete data makes the answer say so.
 */
object CapacityAnswer {
  fun answer(
    health: PlanHealthResult,
    newClientHoursPerWeek: Int,
    rangeEnd: Long,
  ): CapacityResult {
    require(newClientHoursPerWeek >= 0) { "Client hours per week cannot be negative" }
    val clientMinutes = newClientHoursPerWeek * 60
    val available = health.capacityAfterCommitmentsMinutes
    val planned = (health.scheduledPlanMinutes.toLong() + health.unscheduledDemandMinutes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val spare = health.freeAfterPlannedMinutes

    if (health.assessment == PlanHealthAssessment.INCOMPLETE_DATA) {
      val missing = health.missingEstimateCount
      val invalid = health.invalidPlanningFieldCount
      val sentence =
        when {
          missing > 0 && invalid > 0 ->
            "$missing unfinished tasks have no effort estimate and $invalid planning records are invalid, so the capacity answer is not trustworthy yet"
          missing > 0 -> "$missing unfinished tasks have no effort estimate, so the capacity answer is not trustworthy yet"
          else -> "$invalid planning records are invalid, so the capacity answer is not trustworthy yet"
        }
      return CapacityResult(available, planned, spare, CapacityVerdict.INCOMPLETE, sentence, emptyList())
    }

    if (spare >= clientMinutes) {
      val sentence =
        "${formatHours(available)} h available this week, ${formatHours(planned)} h planned, " +
          "${formatHours(spare)} h spare — an ${formatHours(clientMinutes)} h/week client fits."
      return CapacityResult(available, planned, spare, CapacityVerdict.CAN_TAKE, sentence, emptyList())
    }

    val shortfall = clientMinutes - spare
    val deferrable =
      health.demands
        .filter { demand ->
          demand.unscheduledMinutes != null &&
            demand.unscheduledMinutes > 0 &&
            (demand.item.dueAt == null || demand.item.dueAt > rangeEnd)
        }
        .sortedWith(deferralOrder())
    val moves = mutableListOf<CapacityMove>()
    var freed = 0
    for (demand in deferrable) {
      if (freed >= shortfall) break
      val minutes = demand.unscheduledMinutes ?: continue
      moves += CapacityMove(demand.item.id, demand.item.title, minutes)
      freed += minutes
    }
    return if (freed >= shortfall) {
      val sentence =
        "${formatHours(spare)} h spare is ${formatHours(shortfall)} h short of an " +
          "${formatHours(clientMinutes)} h/week client; moving ${titles(moves)} past this week frees it."
      CapacityResult(available, planned, spare, CapacityVerdict.MOVE, sentence, moves)
    } else {
      val sentence =
        "${formatHours(spare)} h spare is ${formatHours(shortfall)} h short of an " +
          "${formatHours(clientMinutes)} h/week client, and even moving every deferrable task leaves " +
          "${formatHours(shortfall - freed)} h missing."
      CapacityResult(available, planned, spare, CapacityVerdict.CANNOT, sentence, moves)
    }
  }

  private fun deferralOrder() =
    compareBy<PlanTaskDemand> { demand ->
        when (demand.item.priority) {
          PlanPriority.URGENT -> 0
          PlanPriority.HIGH -> 1
          PlanPriority.NORMAL -> 2
          else -> 3
        }
      }
      .thenBy { it.item.dueAt ?: Long.MAX_VALUE }
      .thenBy { it.item.rank }
      .thenBy { it.item.id }

  private fun titles(moves: List<CapacityMove>): String =
    when (moves.size) {
      0 -> "nothing"
      1 -> "\"${moves[0].title}\""
      2 -> "\"${moves[0].title}\" and \"${moves[1].title}\""
      else ->
        moves.dropLast(1).joinToString(", ") { "\"${it.title}\"" } +
          " and \"${moves.last().title}\""
    }

  /** Hours to one decimal, trimming a trailing zero: 480 → "8", 750 → "12.5". */
  private fun formatHours(minutes: Int): String {
    val tenths = (minutes.toLong() * 10) / 60
    return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
  }
}

enum class CapacityVerdict {
  CAN_TAKE,
  MOVE,
  CANNOT,
  INCOMPLETE,
}

data class CapacityMove(
  val itemId: String,
  val title: String,
  val unscheduledMinutes: Int,
)

data class CapacityResult(
  val availableMinutes: Int,
  val plannedMinutes: Int,
  val spareMinutes: Int,
  val verdict: CapacityVerdict,
  val sentence: String,
  val moves: List<CapacityMove>,
)
