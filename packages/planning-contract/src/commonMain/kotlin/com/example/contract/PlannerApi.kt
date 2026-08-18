package com.example.contract

import kotlinx.serialization.json.Json

/**
 * The planner API envelope: every top-level message carries the wire version it speaks.
 *
 * A decoder accepts exactly [VERSION]; anything newer raises [ApiVersionNotSupported] before
 * any field is interpreted, so a client that has learned a new shape can never be silently
 * misread by an older server (and vice versa). Within a version, unknown fields are ignored
 * and missing fields fall back to defaults — the JSON instance below is the contract's own
 * posture, shared by every endpoint.
 */
object PlannerApi {
  const val VERSION: Int = 1

  val json: Json =
    Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      // Pretty-printed so the golden contract files stay reviewable; formatting is presentation,
      // never semantics.
      prettyPrint = true
      prettyPrintIndent = "  "
    }

  fun checkVersion(v: Int) {
    if (v != VERSION) {
      throw ApiVersionNotSupported(VERSION, v)
    }
  }
}

class ApiVersionNotSupported(val supported: Int, val received: Int) :
  IllegalArgumentException("Planner API v$received is not supported; this server speaks v$supported")

/** Wire names for [DeadlinePolicy], mirroring the engine enum exactly. */
object DeadlinePolicyWire {
  const val SOFT = "SOFT"
  const val HARD = "HARD"
}

/** Wire names for [PlanHealthAssessment], mirroring the engine enum exactly. */
object PlanHealthAssessmentWire {
  const val ON_TRACK = "ON_TRACK"
  const val AT_RISK = "AT_RISK"
  const val OVERCOMMITTED = "OVERCOMMITTED"
  const val INCOMPLETE_DATA = "INCOMPLETE_DATA"
}