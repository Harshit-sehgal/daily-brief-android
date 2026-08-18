package com.example.contract

import kotlinx.serialization.Serializable

/** Wire names for [com.example.core.CapacityVerdict], mirroring the engine enum exactly. */
object CapacityVerdictWire {
  const val CAN_TAKE = "CAN_TAKE"
  const val MOVE = "MOVE"
  const val CANNOT = "CANNOT"
  const val INCOMPLETE = "INCOMPLETE"
}

/**
 * The capacity question (docs/saas/02-v1-product-spec.md): "can I take another client?"
 *
 * The client composes the same [plan] it would send to /v1/plan plus the client load it wants
 * to test; the server answers with three numbers and one sentence. The answer is a floor,
 * never a promise — the INCOMPLETE verdict says so.
 */
@Serializable
data class CapacityRequestWire(
  val v: Int = PlannerApi.VERSION,
  val plan: PlanningRequest,
  val newClientHoursPerWeek: Int = 8,
) {
  init {
    PlannerApi.checkVersion(v)
  }
}

/** One task that would have to leave the range to make room for the new client. */
@Serializable
data class CapacityMoveWire(
  val itemId: String,
  val title: String,
  val unscheduledMinutes: Int,
)

@Serializable
data class CapacityResponse(
  val v: Int = PlannerApi.VERSION,
  val availableMinutes: Int,
  val plannedMinutes: Int,
  val spareMinutes: Int,
  val verdict: String,
  val sentence: String,
  val moves: List<CapacityMoveWire> = emptyList(),
)