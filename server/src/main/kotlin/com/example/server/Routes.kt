package com.example.server

import com.example.contract.BoardWire
import com.example.contract.CapacityRequestWire
import com.example.contract.CreateProjectWire
import com.example.contract.PlanningRequest
import com.example.contract.PlanningResult
import com.example.contract.ProjectWire
import com.example.contract.StageWire
import com.example.contract.TaskWire
import com.example.server.db.Db
import com.example.server.db.Db.execute
import com.example.server.db.Db.newId
import com.example.server.db.Db.query
import com.example.server.db.Db.queryOne
import com.example.server.db.Envelope
import com.example.server.google.FixtureCalendarProvider
import com.example.server.google.GoogleCalendarProvider
import com.example.server.google.GoogleOAuth
import com.example.server.plan.Journal
import com.example.core.Mapping
import com.example.core.Mapping.answerCapacity
import com.example.core.Mapping.toWire
import com.example.server.reconcile.ReconcileWorker
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.Principal
import io.ktor.server.auth.principal
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authentication
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.auth.bearer
import io.ktor.server.auth.authenticate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The slice's HTTP surface. Nothing here decides anything the engine decides: the planner
 * endpoint maps, proposes, and stores the run; apply/undo go through the journal; Today
 * reads. The client renders; the server is authoritative for Apply.
 */
fun Application.routes(config: Config) {
  val sessions = Sessions(config.sessionSecret)
  val cache = PlanCache()

  authentication {
    bearer("session") { authenticate { credentials -> sessions.verify(credentials.token) } }
  }

  routing {
    route("/v1") {
      if (config.fixtureProvider) {
        post("/fixture/signup") {
          val workspaceId = FixtureWorld.ensure(call)
          call.respond(SessionResponse(workspaceId, sessions.issue(workspaceId)))
        }
      } else {
        // The real-account leg: the web client redirects to Google, then trades the code for
        // a server-side token (envelope-encrypted in calendar_connections) and a session.
        get("/auth/start") {
          val redirectUri = call.request.queryParameters["redirect_uri"] ?: config.webOrigin
          call.respond(
            mapOf(
              "url" to GoogleOAuth.authorizeUrl(config, redirectUri),
              "redirectUri" to redirectUri,
            ),
          )
        }

        post("/auth/callback") {
          val body = call.receive<AuthCallback>()
          val tokens = GoogleOAuth.exchangeCode(config, body.code, body.redirectUri)
          val workspaceId = FixtureWorld.ensure(call, email = "google@dailybrief.dev", name = "Google User")
          GoogleOAuth.storeToken(config, workspaceId, "google", "google-account", GoogleOAuth.json.encodeToString(tokens))
          call.respond(SessionResponse(workspaceId, sessions.issue(workspaceId)))
        }
      }

      authenticate("session") {
        // A task names its project through boardId; an unknown board falls back to the
        // workspace's default project, which is what the mobile client (boardId "b1") sends.
        fun projectFor(session: Session, boardId: String?): String {
          if (boardId == null) return FixtureWorld.projectId(session.workspaceId)
          return Db.dataSource.connection.use { conn ->
            conn.queryOne(
              "SELECT id FROM projects WHERE workspace_id = ? AND id = ? AND archived_at IS NULL",
              listOf(session.workspaceId, boardId),
            ) { it.getString(1) }
          } ?: FixtureWorld.projectId(session.workspaceId)
        }

        fun insertTask(projectId: String, workspaceId: String, task: com.example.contract.TaskWire) {
          val now = System.currentTimeMillis()
          Db.inTransaction { conn ->
            conn.execute(
              "INSERT INTO tasks (id, project_id, stage_id, parent_id, workspace_id, title, notes, rank, start_constraint, due_at, effort_minutes, progress, priority, owner, scheduling_mode, locked, is_milestone, completed_at, archived_at, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (project_id, id) DO UPDATE SET title = EXCLUDED.title, effort_minutes = EXCLUDED.effort_minutes, due_at = EXCLUDED.due_at, priority = EXCLUDED.priority, updated_at = EXCLUDED.updated_at",
              listOf(
                task.id,
                projectId,
                task.columnId,
                task.parentId,
                workspaceId,
                task.title,
                task.notes,
                task.rank,
                task.startConstraint,
                task.dueAt,
                task.effortMinutes,
                task.progress,
                task.priority,
                task.owner,
                task.schedulingMode,
                task.locked,
                task.isMilestone,
                task.completedAt,
                task.archivedAt,
                now,
                now,
              ),
            )
          }
        }

        post("/tasks") {
          val session = sessions.require(call)
          val task = call.receive<com.example.contract.TaskWire>()
          insertTask(projectFor(session, task.boardId), session.workspaceId, task)
          call.respond(mapOf("ok" to true))
        }

        get("/projects") {
          val session = sessions.require(call)
          val projects =
            Db.dataSource.connection.use { conn ->
              conn.query(
                "SELECT id, name, is_default, rank, archived_at FROM projects WHERE workspace_id = ? AND archived_at IS NULL ORDER BY rank, created_at",
                listOf(session.workspaceId),
              ) { rs ->
                ProjectWire(
                  id = rs.getString("id"),
                  workspaceId = session.workspaceId,
                  name = rs.getString("name"),
                  isDefault = rs.getBoolean("is_default"),
                  rank = rs.getLong("rank"),
                  archivedAt = rs.getLong("archived_at").takeIf { !rs.wasNull() },
                )
              }
            }
          call.respond(projects)
        }

        post("/projects") {
          val session = sessions.require(call)
          val request = call.receive<CreateProjectWire>()
          val name = request.name.trim()
          if (name.isEmpty()) {
            call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "project name cannot be empty"))
            return@post
          }
          val projectId = newId()
          val now = System.currentTimeMillis()
          Db.inTransaction { conn ->
            conn.execute(
              "INSERT INTO projects (id, workspace_id, name, name_key, rank, is_default, created_at, updated_at) VALUES (?, ?, ?, ?, ?, false, ?, ?)",
              listOf(projectId, session.workspaceId, name, name.lowercase(), 1, now, now),
            )
            DefaultStages.create(conn, projectId, session.workspaceId, now)
          }
          call.respond(
            ProjectWire(
              id = projectId,
              workspaceId = session.workspaceId,
              name = name,
              isDefault = false,
              rank = 1,
            ),
          )
        }

        get("/projects/{projectId}") {
          val session = sessions.require(call)
          val projectId = call.parameters["projectId"] ?: error("projectId required")
          val project =
            Db.dataSource.connection.use { conn ->
              conn.queryOne(
                "SELECT id, name, is_default, rank, archived_at FROM projects WHERE workspace_id = ? AND id = ? AND archived_at IS NULL",
                listOf(session.workspaceId, projectId),
              ) { rs ->
                ProjectWire(
                  id = rs.getString("id"),
                  workspaceId = session.workspaceId,
                  name = rs.getString("name"),
                  isDefault = rs.getBoolean("is_default"),
                  rank = rs.getLong("rank"),
                  archivedAt = rs.getLong("archived_at").takeIf { !rs.wasNull() },
                )
              }
            }
          if (project == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such project"))
            return@get
          }
          val (stages, tasks) =
            Db.dataSource.connection.use { conn ->
              val stages =
                conn.query(
                  "SELECT id, name, rank, archived_at FROM workflow_stages WHERE project_id = ? AND archived_at IS NULL ORDER BY rank",
                  listOf(projectId),
                ) { rs ->
                  StageWire(
                    id = rs.getString("id"),
                    name = rs.getString("name"),
                    rank = rs.getLong("rank"),
                    archivedAt = rs.getLong("archived_at").takeIf { !rs.wasNull() },
                  )
                }
              val tasks =
                conn.query(
                  "SELECT id, parent_id, title, notes, rank, start_constraint, due_at, effort_minutes, progress, priority, owner, scheduling_mode, locked, is_milestone, completed_at, archived_at, stage_id " +
                    "FROM tasks WHERE workspace_id = ? AND project_id = ? AND archived_at IS NULL ORDER BY rank",
                  listOf(session.workspaceId, projectId),
                ) { rs ->
                  TaskWire(
                    id = rs.getString("id"),
                    boardId = projectId,
                    columnId = rs.getString("stage_id"),
                    parentId = rs.getString("parent_id"),
                    title = rs.getString("title"),
                    notes = rs.getString("notes"),
                    rank = rs.getLong("rank"),
                    startConstraint = rs.getLong("start_constraint").takeIf { !rs.wasNull() },
                    dueAt = rs.getLong("due_at").takeIf { !rs.wasNull() },
                    effortMinutes = rs.getInt("effort_minutes").takeIf { !rs.wasNull() },
                    progress = rs.getInt("progress"),
                    priority = rs.getString("priority"),
                    owner = rs.getString("owner"),
                    schedulingMode = rs.getString("scheduling_mode"),
                    locked = rs.getBoolean("locked"),
                    isMilestone = rs.getBoolean("is_milestone"),
                    completedAt = rs.getLong("completed_at").takeIf { !rs.wasNull() },
                    archivedAt = rs.getLong("archived_at").takeIf { !rs.wasNull() },
                  )
                }
              stages to tasks
            }
          call.respond(BoardWire(project = project, stages = stages, tasks = tasks))
        }

        post("/projects/{projectId}/tasks") {
          val session = sessions.require(call)
          val projectId = call.parameters["projectId"] ?: error("projectId required")
          if (projectFor(session, projectId) != projectId) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such project"))
            return@post
          }
          val task = call.receive<com.example.contract.TaskWire>().copy(boardId = projectId)
          insertTask(projectId, session.workspaceId, task)
          call.respond(mapOf("ok" to true))
        }

        get("/tasks") {
          val session = sessions.require(call)
          val projectId = FixtureWorld.projectId(session.workspaceId)
          val tasks =
            Db.dataSource.connection.use { conn ->
              conn.query(
                "SELECT id, parent_id, title, notes, rank, start_constraint, due_at, effort_minutes, progress, priority, owner, scheduling_mode, locked, is_milestone, completed_at, archived_at " +
                  "FROM tasks WHERE workspace_id = ? AND project_id = ? AND archived_at IS NULL ORDER BY rank",
                listOf(session.workspaceId, projectId),
              ) { rs ->
                com.example.contract.TaskWire(
                  id = rs.getString("id"),
                  boardId = projectId,
                  columnId = null,
                  parentId = rs.getString("parent_id"),
                  title = rs.getString("title"),
                  notes = rs.getString("notes"),
                  rank = rs.getLong("rank"),
                  startConstraint = rs.getLong("start_constraint").takeIf { !rs.wasNull() },
                  dueAt = rs.getLong("due_at").takeIf { !rs.wasNull() },
                  effortMinutes = rs.getInt("effort_minutes").takeIf { !rs.wasNull() },
                  progress = rs.getInt("progress"),
                  priority = rs.getString("priority"),
                  owner = rs.getString("owner"),
                  schedulingMode = rs.getString("scheduling_mode"),
                  locked = rs.getBoolean("locked"),
                  isMilestone = rs.getBoolean("is_milestone"),
                  completedAt = rs.getLong("completed_at").takeIf { !rs.wasNull() },
                  archivedAt = rs.getLong("archived_at").takeIf { !rs.wasNull() },
                )
              }
            }
          call.respond(tasks)
        }

        post("/reconcile") {
          val session = sessions.require(call)
          val provider = providerFor(config, session.workspaceId)
          ReconcileWorker.reconcile(session.workspaceId, FixtureWorld.projectId(session.workspaceId), provider)
          call.respond(mapOf("ok" to true))
        }

        post("/plan") {
          val session = sessions.require(call)
          val request = call.receive<PlanningRequest>()
          if (request.workspaceId != session.workspaceId) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "workspace mismatch"))
            return@post
          }
          val refusal = request.refusalReason()
          if (refusal != null) {
            call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to refusal))
            return@post
          }
          val projectId = FixtureWorld.projectId(session.workspaceId)
          val (runId, result) = cache.planAndStore(request, session.workspaceId, projectId)
          call.respond(PlanRunResponse(runId, result))
        }

        post("/capacity") {
          val session = sessions.require(call)
          val request = call.receive<CapacityRequestWire>()
          if (request.plan.workspaceId != session.workspaceId) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "workspace mismatch"))
            return@post
          }
          val refusal = request.plan.refusalReason()
          if (refusal != null) {
            call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to refusal))
            return@post
          }
          if (request.plan.schedules.isEmpty()) {
            call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "at least one working schedule is required"))
            return@post
          }
          call.respond(request.answerCapacity().toWire())
        }

        post("/plan/{runId}/apply") {
          val session = sessions.require(call)
          val runId = call.parameters["runId"] ?: error("runId required")
          val projectId = FixtureWorld.projectId(session.workspaceId)
          val proposals = PlanStore.proposalsFor(session.workspaceId, runId)
          if (proposals.isEmpty()) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no proposals for this run"))
            return@post
          }
          val now = System.currentTimeMillis()
          val (blocks, entryId) = Journal.applyProposal(session.workspaceId, projectId, proposals, now)
          PlanStore.markRun(session.workspaceId, runId, "accepted")
          call.respond(ApplyResponse(blocks.size, entryId))
        }

        post("/plan/{runId}/reject") {
          val session = sessions.require(call)
          val runId = call.parameters["runId"] ?: error("runId required")
          // A rejection writes nothing: no blocks, no journal entry, run marked rejected.
          PlanStore.markRun(session.workspaceId, runId, "rejected")
          call.respond(mapOf("ok" to true))
        }

        post("/plan/{entryId}/undo") {
          val session = sessions.require(call)
          val entryId = call.parameters["entryId"] ?: error("entryId required")
          val restored = Journal.undo(session.workspaceId, entryId, System.currentTimeMillis())
          if (restored == null) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "stale, expired, or already undone"))
          } else {
            call.respond(mapOf("restored" to restored))
          }
        }

        get("/today") {
          val session = sessions.require(call)
          val today = Today.forWorkspace(session.workspaceId, System.currentTimeMillis())
          call.respond(today)
        }
      }
    }
  }
}

@Serializable
data class SessionResponse(val workspaceId: String, val token: String)

@Serializable
data class PlanRunResponse(val runId: String, val result: PlanningResult)

@Serializable
data class ApplyResponse(val blocks: Int, val entryId: String)

@Serializable
data class AuthCallback(val code: String, val redirectUri: String)

/**
 * Stateless sessions: HMAC(sessionSecret, workspaceId + expiry). No sessions table — the
 * schema is frozen; sessions are tokens, not rows.
 */
class Sessions(secret: String) {
  private val key = SecretKeySpec(secret.toByteArray(), "HmacSHA256")

  fun issue(workspaceId: String): String {
    val expiresAt = System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L
    val body = "$workspaceId.$expiresAt"
    return "$body.${sign(body)}"
  }

  fun verify(token: String): Session? {
    val parts = token.split(".")
    if (parts.size != 3) return null
    val (workspaceId, expiresAtText, given) = parts
    val expiresAt = expiresAtText.toLongOrNull() ?: return null
    if (expiresAt < System.currentTimeMillis()) return null
    val body = "$workspaceId.$expiresAtText"
    if (!MessageDigest.isEqual(sign(body).toByteArray(), given.toByteArray())) return null
    return Session(workspaceId)
  }

  private fun sign(body: String): String =
    Mac.getInstance("HmacSHA256").run {
      init(key)
      doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

data class Session(val workspaceId: String) : Principal

fun Sessions.require(call: ApplicationCall): Session =
  call.principal<Session>() ?: error("unauthenticated")

/**
 * Fixture world: one workspace, one project, one provider — created on demand so the
 * journey test can boot against an empty database and still have everything wired.
 */
object FixtureWorld {
  private const val PROJECT_NAME = "My Plan"

  fun ensure(call: ApplicationCall, email: String = "fixture@dailybrief.dev", name: String = "Fixture User"): String {
    val workspaceId =
      Db.dataSource.connection.use { conn ->
        conn.queryOne("SELECT id FROM workspaces ORDER BY created_at LIMIT 1") { it.getString(1) }
      } ?: Db.inTransaction { conn ->
        val userId = newId()
        val ws = newId()
        conn.execute(
          "INSERT INTO users (id, email, name, tier, created_at, updated_at) VALUES (?, ?, ?, 'free', ?, ?)",
          listOf(userId, email, name, System.currentTimeMillis(), System.currentTimeMillis()),
        )
        conn.execute(
          "INSERT INTO workspaces (id, name, owner_user_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
          listOf(ws, "Fixture Workspace", userId, System.currentTimeMillis(), System.currentTimeMillis()),
        )
        ws
      }
    projectId(workspaceId)
    return workspaceId
  }

  fun projectId(workspaceId: String): String =
    Db.dataSource.connection.use { conn ->
      conn.queryOne(
        "SELECT id FROM projects WHERE workspace_id = ? ORDER BY created_at LIMIT 1",
        listOf(workspaceId),
      ) { it.getString(1) }
    } ?: Db.inTransaction { conn ->
      val projectId = newId()
      conn.execute(
        "INSERT INTO projects (id, workspace_id, name, name_key, rank, is_default, created_at, updated_at) VALUES (?, ?, ?, ?, 0, true, ?, ?)",
        listOf(projectId, workspaceId, PROJECT_NAME, PROJECT_NAME.lowercase(), System.currentTimeMillis(), System.currentTimeMillis()),
      )
      DefaultStages.create(conn, projectId, workspaceId, System.currentTimeMillis())
      projectId
    }
}

/** The standard board columns every project starts with, mirroring the app's DEFAULT_COLUMNS. */
object DefaultStages {
  private data class Stage(val id: String, val name: String, val rank: Long)

  private val STAGES = listOf(Stage("todo", "To Do", 0), Stage("in-progress", "In Progress", 1), Stage("done", "Done", 2))

  fun create(conn: java.sql.Connection, projectId: String, workspaceId: String, now: Long) {
    STAGES.forEach { stage ->
      conn.execute(
        "INSERT INTO workflow_stages (id, project_id, workspace_id, name, name_key, rank, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
          "ON CONFLICT (project_id, id) DO NOTHING",
        listOf(stage.id, projectId, workspaceId, stage.name, stage.name.lowercase(), stage.rank, now, now),
      )
    }
  }
}

fun providerFor(config: Config, workspaceId: String): com.example.server.google.CalendarProvider {
  if (config.fixtureProvider) return FixtureCalendarProvider(workspaceId)
  val connection =
    Db.dataSource.connection.use { conn ->
      conn.queryOne(
        "SELECT id, token_ciphertext FROM calendar_connections WHERE workspace_id = ? AND status = 'connected' LIMIT 1",
        listOf(workspaceId),
      ) { rs -> rs.getString("id") to rs.getString("token_ciphertext") }
    } ?: error("no connected calendar for workspace $workspaceId")
  val stored = Envelope.unwrap(config.envelopeKeyHex, workspaceId, "calendar_connection_${connection.first}", connection.second)
    ?: error("could not unwrap the stored token for workspace $workspaceId")
  val token = GoogleOAuth.json.decodeFromString<GoogleOAuth.TokenResponse>(stored)
  // The slice does not refresh: a short-lived access token expires during a long demo, and
  // the runbook says so. Refresh handling is WP-14 hardening.
  return GoogleCalendarProvider(config, connection.first, token.access_token)
}

object PlanStore {
  fun proposalsFor(workspaceId: String, runId: String): List<com.example.contract.PlanProposalWire> =
    Db.dataSource.connection.use { conn ->
      conn.query(
        "SELECT task_id, start_at, end_at, reason FROM plan_proposals WHERE workspace_id = ? AND plan_run_id = ? AND status = 'proposed' ORDER BY start_at",
        listOf(workspaceId, runId),
      ) { rs -> com.example.contract.PlanProposalWire(rs.getString("task_id"), rs.getLong("start_at"), rs.getLong("end_at"), rs.getString("reason")) }
    }

  fun markRun(workspaceId: String, runId: String, status: String) {
    Db.inTransaction { conn ->
      conn.execute("UPDATE plan_runs SET status = ? WHERE workspace_id = ? AND id = ?", listOf(status, workspaceId, runId))
      conn.execute(
        "UPDATE plan_proposals SET status = ? WHERE workspace_id = ? AND plan_run_id = ?",
        listOf(status, workspaceId, runId),
      )
    }
  }
}

/**
 * The determinism cache (04): same request hash against the same data_version is the same
 * plan. Hash = SHA-256 of the canonical request bytes; data_version is bumped by every
 * write. In-memory for the slice; a shared cache is a deployment concern.
 */
class PlanCache {
  private val json = Json { encodeDefaults = true }
  private val map = mutableMapOf<String, PlanningResult>()
  private val rng = SecureRandom()

  fun planAndStore(request: PlanningRequest, workspaceId: String, projectId: String): Pair<String, PlanningResult> {
    val requestHash = sha256(json.encodeToString(request))
    val dataVersion = dataVersion(workspaceId)
    val key = "$requestHash.$dataVersion"
    val result = map.getOrPut(key) { Mapping.propose(Mapping.toEngine(request, System.currentTimeMillis()), request) }

    val runId = newId()
    val now = System.currentTimeMillis()
    Db.inTransaction { conn ->
      conn.execute(
        "INSERT INTO plan_runs (id, workspace_id, request_hash, request_json, data_version, status, created_at) VALUES (?, ?, ?, ?, ?, 'accepted', ?)",
        listOf(runId, workspaceId, requestHash, json.encodeToString(request), dataVersion, now),
      )
      result.proposals.forEach { p ->
        conn.execute(
          "INSERT INTO plan_proposals (id, plan_run_id, workspace_id, project_id, task_id, start_at, end_at, reason, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'proposed', ?)",
          listOf(newId(), runId, workspaceId, projectId, p.itemId, p.startAt, p.endAt, p.reason, now),
        )
      }
    }
    return runId to result
  }

  private fun dataVersion(workspaceId: String): Long =
    Db.dataSource.connection.use { conn ->
      conn.queryOne(
        "SELECT GREATEST(COALESCE((SELECT MAX(updated_at) FROM tasks WHERE workspace_id = ?), 0), COALESCE((SELECT MAX(updated_at) FROM scheduled_blocks WHERE workspace_id = ?), 0), COALESCE((SELECT MAX(start_time) FROM external_events WHERE workspace_id = ?), 0))",
        listOf(workspaceId, workspaceId, workspaceId),
      ) { it.getLong(1) } ?: 0L
    }

  private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}