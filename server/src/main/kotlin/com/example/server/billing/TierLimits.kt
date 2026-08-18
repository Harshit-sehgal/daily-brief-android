package com.example.server.billing

/**
 * The tier boundary from `02-v1-product-spec.md`, as pure decisions.
 *
 * Free is one workspace, one calendar connection, one project; paid unlocks unlimited
 * projects, capacity and baselines. The engine itself is never tiered — `MultiSchedulePlanHealth`
 * runs for everyone, and the paid tier unlocks the *multiple engagements* that make the
 * max-flow question real. Limits are enforced here, reported on the wire, and never guessed
 * by a client.
 */
object TierLimits {
  const val TIER_FREE = "free"
  const val TIER_PAID = "paid"
  const val STATUS_TRIAL = "trial"
  const val STATUS_ACTIVE = "active"

  const val FREE_MAX_PROJECTS = 1
  const val TRIAL_DAYS = 14L

  fun maxProjects(tier: String): Int = if (tier == TIER_PAID) Int.MAX_VALUE else FREE_MAX_PROJECTS

  fun allowsBaselines(tier: String): Boolean = tier == TIER_PAID

  fun allowsCapacity(tier: String): Boolean = tier == TIER_PAID

  /**
   * A workspace on a trial that has ended is still free: the boundary is about what the tier
   * allows, and the trial end only tells the client when to start talking about it.
   */
  fun isPaid(tier: String, status: String): Boolean = tier == TIER_PAID && status == STATUS_ACTIVE
}