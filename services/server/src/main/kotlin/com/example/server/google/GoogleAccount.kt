package com.example.server.google

import com.example.server.Config
import com.example.server.db.Db
import com.example.server.db.Db.execute
import com.example.server.db.Db.queryOne
import com.example.server.db.Envelope
import com.example.server.key.KeyProviders

/**
 * What the notebook asks Google for, and when. Signing in asks only who you are. The
 * calendar and Drive are each granted by a later consent the reader starts themselves
 * (Google's incremental authorization), so nobody is asked for their calendar before
 * they have seen what it is for — and a sign-in never shows the unverified-app warning
 * that a sensitive scope brings.
 */
object GoogleScopes {
  const val SIGN_IN = "openid email profile"
  const val CALENDAR = "https://www.googleapis.com/auth/calendar.readonly"

  /** Per-file access: the notebook can touch only the one file it created itself. */
  const val DRIVE = "https://www.googleapis.com/auth/drive.file"

  /** Secondary calendars the notebook creates itself, and events on those alone. */
  const val PLAN_CALENDAR = "https://www.googleapis.com/auth/calendar.app.created"

  fun requested(connect: String?): String =
    when (connect) {
      "calendar" -> "$SIGN_IN $CALENDAR"
      "plan" -> "$SIGN_IN $PLAN_CALENDAR"
      "drive" -> "$SIGN_IN $DRIVE"
      else -> SIGN_IN
    }

  fun parse(scopes: String?): Set<String> = scopes.orEmpty().split(' ').filter { it.isNotBlank() }.toSet()
}

/** Raised when a route needs a Google grant the workspace has not given. */
class GoogleNotConnected(val what: String) : IllegalStateException("$what is not connected")

/**
 * The workspace's one Google connection: an envelope-encrypted token pair and the scopes
 * it carries. Every sign-in and every later consent lands on the same row, so a second
 * sign-in can never strand a stale token beside a fresh one.
 */
object GoogleAccount {
  private const val PROVIDER = "google"
  private const val ACCOUNT = "google-account"

  data class Stored(
    val connectionId: String,
    val tokens: GoogleOAuth.TokenResponse,
    val scopes: Set<String>,
    val driveFileId: String?,
    val driveBackupAt: Long?,
    val planCalendarId: String? = null,
    val planPushedAt: Long? = null,
  )

  fun load(config: Config, workspaceId: String): Stored? {
    data class Row(
      val id: String,
      val ciphertext: String,
      val scopes: String,
      val fileId: String?,
      val backupAt: Long?,
      val planCalendarId: String?,
      val planPushedAt: Long?,
    )
    val row =
      Db.dataSource.connection.use { conn ->
        conn.queryOne(
          "SELECT id, token_ciphertext, scopes, drive_file_id, drive_backup_at, plan_calendar_id, plan_pushed_at FROM calendar_connections " +
            "WHERE workspace_id = ? AND provider = ? AND status = 'connected' ORDER BY updated_at DESC LIMIT 1",
          listOf(workspaceId, PROVIDER),
        ) { rs ->
          Row(
            rs.getString("id"),
            rs.getString("token_ciphertext"),
            rs.getString("scopes"),
            rs.getString("drive_file_id"),
            rs.getLong("drive_backup_at").takeIf { !rs.wasNull() },
            rs.getString("plan_calendar_id"),
            rs.getLong("plan_pushed_at").takeIf { !rs.wasNull() },
          )
        }
      } ?: return null
    val plain = Envelope.unwrap(KeyProviders.from(config), workspaceId, "calendar_connection_${row.id}", row.ciphertext) ?: return null
    return Stored(
      row.id,
      GoogleOAuth.json.decodeFromString(plain),
      GoogleScopes.parse(row.scopes),
      row.fileId,
      row.backupAt,
      row.planCalendarId,
      row.planPushedAt,
    )
  }

  /**
   * Keep what a consent returned. Google sends a refresh token only with a fresh consent
   * and names only the scopes of this grant, so both are merged with what is stored:
   * signing in again on a second device must not forget the calendar granted on the first.
   */
  fun store(config: Config, workspaceId: String, tokens: GoogleOAuth.TokenResponse) {
    val existing = load(config, workspaceId)
    val merged = merge(existing?.tokens, existing?.scopes.orEmpty(), tokens)
    write(config, workspaceId, existing?.connectionId, merged, GoogleScopes.parse(merged.scope))
  }

  internal fun merge(
    old: GoogleOAuth.TokenResponse?,
    oldScopes: Set<String>,
    fresh: GoogleOAuth.TokenResponse,
  ): GoogleOAuth.TokenResponse =
    fresh.copy(
      refresh_token = fresh.refresh_token ?: old?.refresh_token,
      scope = (oldScopes + GoogleScopes.parse(fresh.scope)).sorted().joinToString(" "),
    )

  /** A refreshed access token for a stored connection, persisted before it is returned. */
  fun refresh(config: Config, workspaceId: String, stored: Stored): GoogleOAuth.TokenResponse {
    val refreshToken = stored.tokens.refresh_token ?: throw GoogleNotConnected("Google")
    val fresh = merge(stored.tokens, stored.scopes, GoogleOAuth.refreshAccessToken(config, refreshToken))
    write(config, workspaceId, stored.connectionId, fresh, stored.scopes)
    return fresh
  }

  fun recordDriveFile(workspaceId: String, connectionId: String, fileId: String, at: Long) {
    Db.dataSource.connection.use { conn ->
      conn.execute(
        "UPDATE calendar_connections SET drive_file_id = ?, drive_backup_at = ? WHERE workspace_id = ? AND id = ?",
        listOf(fileId, at, workspaceId, connectionId),
      )
    }
  }

  fun recordPlanCalendar(workspaceId: String, connectionId: String, calendarId: String, at: Long) {
    Db.dataSource.connection.use { conn ->
      conn.execute(
        "UPDATE calendar_connections SET plan_calendar_id = ?, plan_pushed_at = ? WHERE workspace_id = ? AND id = ?",
        listOf(calendarId, at, workspaceId, connectionId),
      )
    }
  }

  private fun write(config: Config, workspaceId: String, connectionId: String?, tokens: GoogleOAuth.TokenResponse, scopes: Set<String>) {
    val id = connectionId ?: Db.newId()
    val keyProvider = KeyProviders.from(config)
    val envelope = Envelope.wrap(keyProvider, workspaceId, "calendar_connection_$id", GoogleOAuth.json.encodeToString(tokens))
    val now = System.currentTimeMillis()
    val scopeText = scopes.sorted().joinToString(" ")
    Db.inTransaction { conn ->
      if (connectionId == null) {
        conn.execute(
          "INSERT INTO calendar_connections (id, workspace_id, provider, external_account, token_ciphertext, token_kms_key_id, status, scopes, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'connected', ?, ?, ?)",
          listOf(id, workspaceId, PROVIDER, ACCOUNT, envelope, keyProvider.keyId, scopeText, now, now),
        )
      } else {
        conn.execute(
          "UPDATE calendar_connections SET token_ciphertext = ?, token_kms_key_id = ?, scopes = ?, status = 'connected', updated_at = ? WHERE workspace_id = ? AND id = ?",
          listOf(envelope, keyProvider.keyId, scopeText, now, workspaceId, id),
        )
      }
    }
  }
}
