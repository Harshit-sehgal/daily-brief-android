package com.example.server

import com.example.contract.ExternalEventWire
import com.example.contract.TaskWire
import com.example.server.db.Db
import com.example.server.db.Db.execute
import com.example.server.db.Db.query
import com.example.server.db.Db.queryOne
import com.example.server.db.Envelope
import com.example.server.key.KeyProviders
import com.example.server.google.DriveFailure
import com.example.server.google.DriveStore
import com.example.server.google.FixtureDrive
import com.example.server.google.FixturePlanCalendar
import com.example.server.google.GooglePlanCalendar
import com.example.server.google.PlanCalendarDiff
import com.example.server.google.PlanCalendarFailure
import com.example.server.google.PlanCalendarSync
import com.example.server.google.PlannedEvent
import com.example.server.google.GoogleAccount
import com.example.server.google.GoogleDriveStore
import com.example.server.google.GoogleNotConnected
import com.example.server.google.GoogleOAuth
import com.example.server.google.GoogleScopes
import com.example.server.google.NotebookExport
import com.example.server.reconcile.ReconcileWorker
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * What a public notebook needs beyond planning: the calendar across a whole week, a task
 * that can be ticked, highlighted, reworded or rubbed out, the state of the reader's
 * Google connections, a readable copy of the notebook in their Drive, and a way to take
 * everything away again.
 */

@Serializable
data class CalendarConnection(val connected: Boolean, val lastSyncAt: Long? = null)

@Serializable
data class DriveConnection(val connected: Boolean, val lastBackupAt: Long? = null, val fileUrl: String? = null)

@Serializable
data class PlanCalendarConnection(val connected: Boolean, val lastPushAt: Long? = null)

@Serializable
data class ConnectionsResponse(
  val email: String,
  val name: String,
  val calendar: CalendarConnection,
  val drive: DriveConnection,
  val planCalendar: PlanCalendarConnection,
)

/**
 * A day's notes: typed lines and a sketch. The strokes are the client's own drawing
 * format, kept as opaque JSON — the server stores a page, it does not read handwriting.
 */
@Serializable
data class DayNote(val day: String, val text: String = "", val strokes: kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.JsonArray(emptyList())) {
  /** Non-null when the note must be refused instead of stored. */
  fun refusalReason(): String? =
    when {
      !DAY.matches(day) -> "day must be a date like 2026-10-06"
      text.length > MAX_TEXT -> "that note is too long"
      strokes.toString().length > MAX_STROKES -> "that sketch is too large to save; rub some of it out"
      else -> null
    }

  companion object {
    val DAY = Regex("""\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])""")
    const val MAX_TEXT = 20_000
    const val MAX_STROKES = 400_000
  }
}

/** One PATCH of a task, already validated: the columns to set and whether it leaves the plan. */
internal data class TaskEdit(val sets: List<Pair<String, Any?>>, val leavesPlan: Boolean) {
  companion object {
    private val PRIORITIES = setOf("low", "normal", "high", "urgent")

    /**
     * Reads a partial update. Only keys that are present change anything, and an explicit
     * null clears an estimate or a due date — which is why this takes the raw object: a
     * data class cannot tell "absent" from "null".
     */
    fun parse(body: JsonObject, nowMs: Long): TaskEdit {
      val sets = mutableListOf<Pair<String, Any?>>()
      body["title"]?.let { value ->
        val title = value.jsonPrimitive.contentOrNull?.trim().orEmpty()
        require(title.isNotEmpty()) { "a task needs a title" }
        require(title.length <= 500) { "that title is too long" }
        sets += "title" to title
      }
      body["effortMinutes"]?.let { value ->
        val minutes = if (value is JsonNull) null else value.jsonPrimitive.intOrNull
        require(value is JsonNull || (minutes != null && minutes in 1..24 * 60)) { "an estimate is between a minute and a day" }
        sets += "effort_minutes" to minutes
      }
      body["dueAt"]?.let { value ->
        val due = if (value is JsonNull) null else value.jsonPrimitive.longOrNull
        require(value is JsonNull || (due != null && due > 0)) { "dueAt must be a time" }
        sets += "due_at" to due
      }
      body["priority"]?.let { value ->
        val priority = value.jsonPrimitive.contentOrNull
        require(priority in PRIORITIES) { "priority must be one of $PRIORITIES" }
        sets += "priority" to priority
      }
      val completed = body["completed"]?.jsonPrimitive?.booleanOrNull
      val archived = body["archived"]?.jsonPrimitive?.booleanOrNull
      if (completed != null) sets += "completed_at" to if (completed) nowMs else null
      if (archived != null) sets += "archived_at" to if (archived) nowMs else null
      require(sets.isNotEmpty()) { "nothing to change" }
      return TaskEdit(sets, leavesPlan = completed == true || archived == true)
    }
  }
}

object Notebook {
  fun syncCalendar(config: Config, workspaceId: String) {
    ReconcileWorker.reconcile(workspaceId, FixtureWorld.projectId(workspaceId), providerFor(config, workspaceId))
  }

  /** Writes the notebook's readable copy to the reader's Drive, refreshing the token once if refused. */
  fun copyToDrive(config: Config, workspaceId: String): DriveConnection {
    val now = System.currentTimeMillis()
    val content = export(config, workspaceId, now)
    if (config.fixtureProvider) {
      FixtureDrive.store(workspaceId).put(null, NotebookExport.FILE_NAME, content)
      return DriveConnection(true, now, null)
    }
    val stored = GoogleAccount.load(config, workspaceId)?.takeIf { GoogleScopes.DRIVE in it.scopes }
      ?: throw GoogleNotConnected("Google Drive")
    fun put(token: String) = (GoogleDriveStore(token) as DriveStore).put(stored.driveFileId, NotebookExport.FILE_NAME, content)
    val fileId =
      try {
        put(stored.tokens.access_token)
      } catch (failure: DriveFailure) {
        if (failure.statusCode != 401) throw failure
        put(GoogleAccount.refresh(config, workspaceId, stored).access_token)
      }
    GoogleAccount.recordDriveFile(workspaceId, stored.connectionId, fileId, now)
    return DriveConnection(true, now, driveUrl(fileId))
  }

  private const val DAY_MS = 24 * 60 * 60 * 1000L

  /**
   * Makes the reader's "Daily Brief" calendar in Google show exactly the plan, from
   * yesterday to two months ahead. One way: nothing written there is read back.
   */
  fun pushPlan(config: Config, workspaceId: String): PlanCalendarConnection {
    val now = System.currentTimeMillis()
    val from = now - DAY_MS
    val to = now + 62 * DAY_MS
    val desired =
      Db.dataSource.connection.use { conn ->
        conn.query(
          "SELECT b.id, COALESCE(t.title, b.task_id), b.start_at, b.end_at FROM scheduled_blocks b " +
            "LEFT JOIN tasks t ON t.project_id = b.project_id AND t.id = b.task_id " +
            "WHERE b.workspace_id = ? AND b.end_at > ? AND b.start_at < ?",
          listOf(workspaceId, from, to),
        ) { rs ->
          val title = rs.getString(2)
          PlannedEvent(PlanCalendarDiff.eventId(rs.getString(1), rs.getLong(3), rs.getLong(4), title), title, rs.getLong(3), rs.getLong(4))
        }
      }
    val zone = WorkingScheduleStore.getOrCreateDefault(workspaceId).timeZoneId
    if (config.fixtureProvider) {
      PlanCalendarSync.run(FixturePlanCalendar.of(workspaceId), "fixture-plan-calendar", zone, desired, from, to)
      FixturePlanCalendar.pushedAt[workspaceId] = now
      return PlanCalendarConnection(true, now)
    }
    val stored = GoogleAccount.load(config, workspaceId)?.takeIf { GoogleScopes.PLAN_CALENDAR in it.scopes }
      ?: throw GoogleNotConnected("The plan calendar")
    fun sync(token: String) = PlanCalendarSync.run(GooglePlanCalendar(token), stored.planCalendarId, zone, desired, from, to)
    val calendarId =
      try {
        sync(stored.tokens.access_token)
      } catch (failure: PlanCalendarFailure) {
        if (failure.statusCode != 401) throw failure
        sync(GoogleAccount.refresh(config, workspaceId, stored).access_token)
      }
    GoogleAccount.recordPlanCalendar(workspaceId, stored.connectionId, calendarId, now)
    return PlanCalendarConnection(true, now)
  }

  fun connections(config: Config, workspaceId: String): ConnectionsResponse {
    val (email, name) =
      Db.dataSource.connection.use { conn ->
        conn.queryOne(
          "SELECT u.email, u.name FROM users u JOIN workspaces w ON w.owner_user_id = u.id WHERE w.id = ?",
          listOf(workspaceId),
        ) { it.getString(1) to it.getString(2) }
      } ?: ("" to "")
    val lastSync =
      Db.dataSource.connection.use { conn ->
        conn.queryOne(
          "SELECT MAX(finished_at) FROM sync_runs WHERE workspace_id = ? AND status = 'ok'",
          listOf(workspaceId),
        ) { rs -> rs.getLong(1).takeIf { !rs.wasNull() } }
      }
    if (config.fixtureProvider) {
      val copy = FixtureDrive.copies[workspaceId]
      val pushed = FixturePlanCalendar.pushedAt[workspaceId]
      return ConnectionsResponse(
        email,
        name,
        CalendarConnection(lastSync != null, lastSync),
        DriveConnection(copy != null, copy?.at),
        PlanCalendarConnection(pushed != null, pushed),
      )
    }
    val stored = GoogleAccount.load(config, workspaceId)
    return ConnectionsResponse(
      email,
      name,
      CalendarConnection(stored != null && GoogleScopes.CALENDAR in stored.scopes, lastSync),
      DriveConnection(stored != null && GoogleScopes.DRIVE in stored.scopes, stored?.driveBackupAt, stored?.driveFileId?.let(::driveUrl)),
      PlanCalendarConnection(stored != null && GoogleScopes.PLAN_CALENDAR in stored.scopes, stored?.planPushedAt),
    )
  }

  private fun driveUrl(fileId: String) = "https://drive.google.com/file/d/$fileId/view"

  private fun export(config: Config, workspaceId: String, nowMs: Long): String =
    Db.dataSource.connection.use { conn ->
      // The last month of notes: recent enough to be wanted, bounded so the file stays a page.
      val keyProvider = KeyProviders.from(config)
      val notes =
        conn.query(
          "SELECT day, body_ciphertext FROM day_notes WHERE workspace_id = ? ORDER BY day DESC LIMIT 31",
          listOf(workspaceId),
        ) { rs -> rs.getString(1) to rs.getString(2) }
          .mapNotNull { (day, envelope) ->
            Envelope.unwrap(keyProvider, workspaceId, "day_note_$day", envelope)
              ?.let { NotebookExport.NotePage(day, noteJson.decodeFromString<DayNote>(it).text) }
          }
      val projects =
        conn.query(
          "SELECT id, name FROM projects WHERE workspace_id = ? AND archived_at IS NULL ORDER BY rank, created_at",
          listOf(workspaceId),
        ) { it.getString(1) to it.getString(2) }
      val pages =
        projects.map { (id, name) ->
          NotebookExport.ProjectPage(
            name,
            conn.query(
              "SELECT title, effort_minutes, due_at, completed_at, priority FROM tasks WHERE workspace_id = ? AND project_id = ? AND archived_at IS NULL ORDER BY rank, created_at",
              listOf(workspaceId, id),
            ) { rs ->
              NotebookExport.TaskLine(
                title = rs.getString("title"),
                effortMinutes = rs.getInt("effort_minutes").takeIf { !rs.wasNull() },
                dueAt = rs.getLong("due_at").takeIf { !rs.wasNull() },
                done = rs.getLong("completed_at").let { !rs.wasNull() },
                highlighted = rs.getString("priority") in setOf("high", "urgent"),
              )
            },
          )
        }
      val blocks =
        conn.query(
          "SELECT COALESCE(t.title, b.task_id), p.name, b.start_at, b.end_at FROM scheduled_blocks b " +
            "LEFT JOIN tasks t ON t.project_id = b.project_id AND t.id = b.task_id " +
            "JOIN projects p ON p.id = b.project_id WHERE b.workspace_id = ?",
          listOf(workspaceId),
        ) { rs -> NotebookExport.PlannedBlock(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)) }
      NotebookExport.markdown(nowMs, WorkingScheduleStore.getOrCreateDefault(workspaceId).timeZoneId, pages, blocks, notes)
    }

  /** Applies an edit to one task; null when the workspace has no such task. */
  internal fun editTask(workspaceId: String, taskId: String, edit: TaskEdit, nowMs: Long): TaskWire? =
    Db.inTransaction { conn ->
      val columns = edit.sets.joinToString(", ") { (column, _) -> "$column = ?" }
      val changed =
        conn.execute(
          "UPDATE tasks SET $columns, updated_at = ? WHERE workspace_id = ? AND id = ?",
          edit.sets.map { it.second } + listOf(nowMs, workspaceId, taskId),
        )
      if (changed == 0) return@inTransaction null
      // Finished or rubbed-out work leaves the plan ahead; what already happened is history.
      if (edit.leavesPlan) {
        conn.execute(
          "DELETE FROM scheduled_blocks WHERE workspace_id = ? AND task_id = ? AND start_at >= ?",
          listOf(workspaceId, taskId, nowMs),
        )
      }
      conn.queryOne(
        "SELECT id, project_id, stage_id, title, rank, due_at, effort_minutes, priority, completed_at, archived_at FROM tasks WHERE workspace_id = ? AND id = ?",
        listOf(workspaceId, taskId),
      ) { rs ->
        TaskWire(
          id = rs.getString("id"),
          boardId = rs.getString("project_id"),
          columnId = rs.getString("stage_id"),
          title = rs.getString("title"),
          rank = rs.getLong("rank"),
          dueAt = rs.getLong("due_at").takeIf { !rs.wasNull() },
          effortMinutes = rs.getInt("effort_minutes").takeIf { !rs.wasNull() },
          priority = rs.getString("priority"),
          completedAt = rs.getLong("completed_at").takeIf { !rs.wasNull() },
          archivedAt = rs.getLong("archived_at").takeIf { !rs.wasNull() },
        )
      }
    }

  fun note(config: Config, workspaceId: String, day: String): DayNote {
    val envelope =
      Db.dataSource.connection.use { conn ->
        conn.queryOne("SELECT body_ciphertext FROM day_notes WHERE workspace_id = ? AND day = ?", listOf(workspaceId, day)) { it.getString(1) }
      } ?: return DayNote(day)
    val plain = Envelope.unwrap(KeyProviders.from(config), workspaceId, "day_note_$day", envelope) ?: return DayNote(day)
    return noteJson.decodeFromString<DayNote>(plain).copy(day = day)
  }

  fun saveNote(config: Config, workspaceId: String, note: DayNote) {
    val keyProvider = KeyProviders.from(config)
    val envelope = Envelope.wrap(keyProvider, workspaceId, "day_note_${note.day}", noteJson.encodeToString(note))
    Db.dataSource.connection.use { conn ->
      conn.execute(
        "INSERT INTO day_notes (workspace_id, day, body_ciphertext, body_kms_key_id, updated_at) VALUES (?, ?, ?, ?, ?) " +
          "ON CONFLICT (workspace_id, day) DO UPDATE SET body_ciphertext = EXCLUDED.body_ciphertext, body_kms_key_id = EXCLUDED.body_kms_key_id, updated_at = EXCLUDED.updated_at",
        listOf(workspaceId, note.day, envelope, keyProvider.keyId, System.currentTimeMillis()),
      )
    }
  }

  private val noteJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

  /** Removes the workspace, everything in it, and the account that owned it. */
  fun deleteAccount(workspaceId: String) {
    Db.inTransaction { conn ->
      val ownerId = conn.queryOne("SELECT owner_user_id FROM workspaces WHERE id = ?", listOf(workspaceId)) { it.getString(1) }
      // These reference tasks or schedules without ON DELETE CASCADE, so they go first;
      // the workspace row then takes every cascading table with it.
      listOf("scheduled_blocks", "task_dependencies", "task_schedule_assignments", "plan_proposals", "external_events", "audit_entries")
        .forEach { table -> conn.execute("DELETE FROM $table WHERE workspace_id = ?", listOf(workspaceId)) }
      conn.execute("DELETE FROM workspaces WHERE id = ?", listOf(workspaceId))
      if (ownerId != null) {
        conn.execute(
          "DELETE FROM users WHERE id = ? AND NOT EXISTS (SELECT 1 FROM workspaces WHERE owner_user_id = ?) " +
            "AND NOT EXISTS (SELECT 1 FROM memberships WHERE user_id = ?)",
          listOf(ownerId, ownerId, ownerId),
        )
      }
    }
  }

  fun events(workspaceId: String, startMs: Long, endMs: Long): List<ExternalEventWire> =
    Db.dataSource.connection.use { conn ->
      conn.query(
        "SELECT id, title, start_time, end_time, source, description, is_deadline, is_urgent, is_all_day, location, kanban_status, kanban_board, user_edited " +
          "FROM external_events WHERE workspace_id = ? AND start_time < ? AND end_time > ? ORDER BY start_time, id",
        listOf(workspaceId, endMs, startMs),
      ) { rs ->
        ExternalEventWire(
          id = rs.getString("id"),
          title = rs.getString("title"),
          startTime = rs.getLong("start_time"),
          endTime = rs.getLong("end_time"),
          source = rs.getString("source"),
          description = rs.getString("description"),
          isDeadline = rs.getBoolean("is_deadline"),
          isUrgent = rs.getBoolean("is_urgent"),
          isAllDay = rs.getBoolean("is_all_day"),
          location = rs.getString("location"),
          kanbanStatus = rs.getString("kanban_status"),
          kanbanBoard = rs.getString("kanban_board"),
          userEdited = rs.getBoolean("user_edited"),
        )
      }
    }
}

private const val MAX_EVENT_RANGE_MS = 93L * 24 * 60 * 60 * 1000

fun Route.notebookRoutes(config: Config, sessions: Sessions) {
  // The calendar across a span of days. /today answers for one UTC day; a week drawn
  // from it showed commitments on today and blank paper on the other six.
  get("/events") {
    val session = sessions.require(call)
    val start = call.request.queryParameters["start"]?.toLongOrNull()
    val end = call.request.queryParameters["end"]?.toLongOrNull()
    if (start == null || end == null || end <= start || end - start > MAX_EVENT_RANGE_MS) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "start and end must bound at most three months"))
      return@get
    }
    call.respond(Notebook.events(session.workspaceId, start, end))
  }

  patch("/tasks/{taskId}") {
    val session = sessions.require(call)
    val taskId = call.parameters["taskId"] ?: error("taskId required")
    val now = System.currentTimeMillis()
    val edit =
      try {
        TaskEdit.parse(call.receive<JsonObject>(), now)
      } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to (e.message ?: "invalid task edit")))
        return@patch
      }
    val task = Notebook.editTask(session.workspaceId, taskId, edit, now)
    if (task == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such task")) else call.respond(task)
  }

  get("/notes/{day}") {
    val session = sessions.require(call)
    val day = call.parameters["day"].orEmpty()
    if (!DayNote.DAY.matches(day)) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "day must be a date like 2026-10-06"))
      return@get
    }
    call.respond(Notebook.note(config, session.workspaceId, day))
  }

  put("/notes/{day}") {
    val session = sessions.require(call)
    val note = call.receive<DayNote>().copy(day = call.parameters["day"].orEmpty())
    note.refusalReason()?.let {
      call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to it))
      return@put
    }
    Notebook.saveNote(config, session.workspaceId, note)
    call.respond(mapOf("ok" to true))
  }

  get("/connections") {
    val session = sessions.require(call)
    call.respond(Notebook.connections(config, session.workspaceId))
  }

  post("/drive/backup") {
    val session = sessions.require(call)
    try {
      call.respond(Notebook.copyToDrive(config, session.workspaceId))
    } catch (e: GoogleNotConnected) {
      call.respond(HttpStatusCode.Conflict, mapOf("error" to "Your Google Drive is not connected"))
    }
  }

  post("/calendar/push") {
    val session = sessions.require(call)
    try {
      call.respond(Notebook.pushPlan(config, session.workspaceId))
    } catch (e: GoogleNotConnected) {
      call.respond(HttpStatusCode.Conflict, mapOf("error" to "Your plan calendar is not connected"))
    }
  }

  delete("/account") {
    val session = sessions.require(call)
    // Withdraw the notebook's access at Google as well; a failure there must not keep the data.
    if (!config.fixtureProvider) {
      runCatching {
        GoogleAccount.load(config, session.workspaceId)?.tokens?.let { GoogleOAuth.revoke(it.refresh_token ?: it.access_token) }
      }
    }
    Notebook.deleteAccount(session.workspaceId)
    call.respond(mapOf("ok" to true))
  }

  if (config.fixtureProvider) {
    get("/fixture/plan-calendar") {
      val session = sessions.require(call)
      call.respond(FixturePlanCalendar.events[session.workspaceId]?.values?.map { it.title }?.sorted() ?: emptyList())
    }
    get("/fixture/drive-file") {
      val session = sessions.require(call)
      call.respond(mapOf("content" to (FixtureDrive.copies[session.workspaceId]?.content ?: "")))
    }
  }
}
