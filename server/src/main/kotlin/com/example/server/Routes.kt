package com.example.server

import com.example.contract.PlanningRequest
import com.example.contract.PlanningResult
import com.example.server.db.Db
import com.example.server.db.Db.execute
import com.example.server.db.Db.newId
import com.example.server.db.Db.query
import com.example.server.db.Db.queryOne
import com.example.server.google.FixtureCalendarProvider
import com.example.server.google.GoogleCalendarProvider
import com.example.server.plan.Journal
import com.example.server.plan.Mapping
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
      }

      authenticate("session") {
        post("/tasks") {
          val session = sessions.require(call)
          val task = call.receive<com.example.contract.TaskWire>()
          val projectId = FixtureWorld.projectId(session.workspaceId)
          val now = System.currentTimeMillis()
          Db.inTransaction { conn ->
            conn.execute(
              "INSERT INTO tasks (id, project_id, stage_id, parent_id, workspace_id, title, notes, rank, start_constraint, due_at, effort_minutes, progress, priority, owner, scheduling_mode, locked, is_milestone, completed_at, archived_at, created_at, updated_at) " +
                "VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (project_id, id) DO UPDATE SET title = EXCLUDED.title, effort_minutes = EXCLUDED.effort_minutes, due_at = EXCLUDED.due_at, priority = EXCLUDED.priority, updated_at = EXCLUDED.updated_at",
              listOf(
                task.id,
                projectId,
                task.parentId,
                session.workspaceId,
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
          call.respond(mapOf("ok" to true))
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

  fun ensure(call: ApplicationCall): String {
    val workspaceId =
      Db.dataSource.connection.use { conn ->
        conn.queryOne("SELECT id FROM workspaces ORDER BY created_at LIMIT 1") { it.getString(1) }
      } ?: Db.inTransaction { conn ->
        val userId = newId()
        val ws = newId()
        conn.execute(
          "INSERT INTO users (id, email, name, tier, created_at, updated_at) VALUES (?, ?, ?, 'free', ?, ?)",
          listOf(userId, "fixture@dailybrief.dev", "Fixture User", System.currentTimeMillis(), System.currentTimeMillis()),
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
      projectId
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
  return GoogleCalendarProvider(config, connection.first, "token")
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