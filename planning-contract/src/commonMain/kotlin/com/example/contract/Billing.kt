package com.example.contract

import kotlinx.serialization.Serializable

/**
 * Stage 4.5 wire — the tier boundary from the V1 spec's table: free is one workspace, one
 * calendar connection, one project; paid unlocks unlimited projects, capacity and baselines.
 * The engine itself is never tiered. The server enforces the limits; this surface reports
 * them so the client can show the boundary instead of guessing it.
 */

/** What a tier allows, so a client can render the boundary without hard-coding it. */
@Serializable
data class TierLimitsWire(
  val maxProjects: Int,
  val baselines: Boolean,
  val capacity: Boolean,
)

/** What the workspace is using, so the boundary reads as "2 of 1 used". */
@Serializable
data class TierUsageWire(
  val projects: Int,
  val baselines: Int,
)

@Serializable
data class BillingResponseWire(
  val v: Int,
  val tier: String,
  val status: String,
  val trialEndsAt: Long? = null,
  val limits: TierLimitsWire,
  val usage: TierUsageWire,
  /** Where an upgrade goes; null when this deployment has no billing provider configured. */
  val checkoutUrl: String? = null,
)