package com.example.server.billing

import com.example.server.db.Db
import com.example.server.db.Db.execute
import com.example.server.db.Db.newId
import com.example.server.db.Db.queryOne

/**
 * The workspace's subscription: read for enforcement, upgraded by the billing webhook.
 * `ensureTrial` gives every new workspace the boundary's free leg — a trial that lapses into
 * the same free limits, so the tier, not the date, decides what runs.
 */
object SubscriptionStore {
  data class Subscription(val tier: String, val status: String, val trialEndsAt: Long?)

  fun ensureTrial(workspaceId: String, now: Long) {
    Db.inTransaction { conn -> ensureTrial(conn, workspaceId, now) }
  }

  /** Connection-scoped form for callers already inside their own transaction. */
  fun ensureTrial(conn: java.sql.Connection, workspaceId: String, now: Long) {
    conn.execute(
      "INSERT INTO subscriptions (id, workspace_id, tier, status, quota_json, trial_ends_at, created_at, updated_at) VALUES (?, ?, 'free', 'trial', '{}', ?, ?, ?) " +
        "ON CONFLICT (workspace_id) DO NOTHING",
      listOf(newId(), workspaceId, now + TierLimits.TRIAL_DAYS * 86_400_000L, now, now),
    )
  }

  fun read(workspaceId: String): Subscription? =
    Db.dataSource.connection.use { conn ->
      conn.queryOne(
        "SELECT tier, status, trial_ends_at FROM subscriptions WHERE workspace_id = ?",
        listOf(workspaceId),
      ) { rs ->
        Subscription(
          tier = rs.getString("tier"),
          status = rs.getString("status"),
          trialEndsAt = rs.getLong("trial_ends_at").takeIf { !rs.wasNull() },
        )
      }
    }

  fun upgrade(workspaceId: String, now: Long) {
    Db.inTransaction { conn ->
      conn.execute(
        "UPDATE subscriptions SET tier = 'paid', status = 'active', updated_at = ? WHERE workspace_id = ?",
        listOf(now, workspaceId),
      )
    }
  }
}