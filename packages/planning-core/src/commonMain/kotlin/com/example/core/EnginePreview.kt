package com.example.core

import com.example.contract.PlanningRequest
import com.example.contract.PlanningResult
import com.example.contract.PlannerApi

/**
 * The mobile native bridge: one String in, one String out. The planner-engine module
 * (Kotlin on Android, Swift on iOS) exposes exactly this function to the app, so no
 * engine or contract type ever crosses the native boundary. The server does not use it —
 * it runs the same mapping through [Mapping] with a persisted run — but a phone preview and
 * a server run meet here, so they compute the same proposals and the preview bytes are the
 * contract's canonical encoding of exactly what the server computes.
 */
object EnginePreview {
  fun previewPlan(requestJson: String): String {
    val request = PlannerApi.json.decodeFromString<PlanningRequest>(requestJson)
    PlannerApi.checkVersion(request.v)
    val input = Mapping.toEngine(request, request.nowMs)
    val result: PlanningResult = Mapping.propose(input, request)
    return PlannerApi.json.encodeToString(PlanningResult.serializer(), result)
  }
}