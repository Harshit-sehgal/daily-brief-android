package com.example.server

import com.example.contract.BaselineComparisonWire
import com.example.contract.BaselineSnapshotWire
import com.example.contract.BillingResponseWire
import com.example.contract.BoardWire
import com.example.contract.CapacityRequestWire
import com.example.contract.CreateProjectWire
import com.example.contract.PlanningRequest
import com.example.contract.PlanningResult
import com.example.contract.PlannerApi
import com.example.contract.PortfolioBlockWire
import com.example.contract.PortfolioResponseWire
import com.example.contract.PortfolioRowWire
import com.example.contract.ProjectWire
import com.example.contract.ScenarioOrderingWire
import com.example.contract.ScenarioRequestWire
import com.example.contract.ScenarioResponseWire
import com.example.contract.ScenarioResultWire
import com.example.contract.ScheduledBlockWire
import com.example.contract.StageWire
import com.example.contract.SummaryRequestWire
import com.example.contract.SummaryResponseWire
import com.example.contract.TaskVarianceWire
import com.example.contract.TaskWire
import com.example.contract.TierLimitsWire
import com.example.contract.TierUsageWire
import com.example.server.billing.BillingProvider
import com.example.server.billing.FixtureBillingProvider
import com.example.server.billing.NotConfiguredBillingProvider
import com.example.server.billing.StripeBillingProvider
import com.example.server.billing.SubscriptionStore
import com.example.server.billing.TierLimits
import com.example.server.billing.WebhookEvent
import com.example.server.db.Db
import com.example.server.db.Db.execute
import com.example.server.db.Db.newId
import com.example.server.db.Db.query
import com.example.server.db.Db.queryOne
import com.example.server.db.Envelope
import com.example.server.key.KeyProviders
import com.example.server.google.FixtureCalendarProvider
import com.example.server.google.GoogleCalendarProvider
import com.example.server.google.GoogleOAuth
import com.example.server.plan.Journal
import com.example.server.push.ExpoPushProvider
import com.example.server.push.FixturePushProvider
import com.example.server.push.NotConfiguredPushProvider
import com.example.server.push.PushPayload
import com.example.server.push.PushProvider
import com.example.server.summary.FixtureSummaryProvider
import com.example.server.summary.GeminiQuota
import com.example.server.summary.GeminiSummaryProvider
import com.example.server.summary.NotConfiguredSummaryProvider
import com.example.server.summary.SummaryContext
import com.example.server.summary.SummaryProvider
import com.example.core.Mapping
import com.example.core.Mapping.answerCapacity
import com.example.core.Mapping.toEngine
import com.example.core.Mapping.toWire
import com.example.core.AutoPlanResult
import com.example.core.PlanScenario
import com.example.core.BaselineVariance
import com.example.core.PlanProposal
import com.example.core.PlanScenarios
import com.example.core.PortfolioRollup
import com.example.core.UnplacedTask
import com.example.server.reconcile.ReconcileWorker
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.Principal
import io.ktor.server.auth.principal
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.authentication
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.auth.authenticate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import io.ktor.server.request.httpMethod

/**
 * The slice's HTTP surface. Nothing here decides anything the engine decides: the planner
 * endpoint maps, proposes, and stores the run; apply/undo go through the journal; Today
 * reads. The client renders; the server is authoritative for Apply.
 */
fun Application.routes(config: Config) {
  val sessions = Sessions(config.sessionSecret)
  val csrfTokens = CsrfTokens(config.sessionSecret)
  val oauthState = OAuthState(config.sessionSecret)
  val cache = PlanCache()

  fun AuthenticationContext.rejectSession() {
    challenge("session", AuthenticationFailedCause.InvalidCredentials) { challenge, call ->
      challenge.complete()
      call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthenticated"))
    }
  }

  authentication {
    provider("session") {
      authenticate { context ->
        val bearerToken = context.call.request.headers[HttpHeaders.Authorization]
          ?.takeIf { it.startsWith("Bearer ") }
          ?.removePrefix("Bearer ")
        if (bearerToken != null) {
          val session = sessions.verify(bearerToken)
          if (session != null) context.principal(session) else context.rejectSession()
        } else {
          val cookieSession = context.call.request.cookies[BROWSER_SESSION_COOKIE]
          val session = sessions.verify(cookieSession ?: "")
          val safeMethod = context.call.request.httpMethod.value in setOf("GET", "HEAD", "OPTIONS")
          val csrfValid = safeMethod || csrfTokens.verify(context.call.request.headers[CSRF_HEADER])
          if (session != null && csrfValid) context.principal(session) else context.rejectSession()
        }
      }
    }
  }

  fun ApplicationCall.setBrowserSession(token: String): String {
    val secure = parseWebOrigin(config.webOrigin).scheme == "https"
    val csrfToken = csrfTokens.issue()
    response.headers.append(HttpHeaders.SetCookie, browserSessionCookie(token, secure))
    response.headers.append(CSRF_HEADER, csrfToken)
    return csrfToken
  }

  fun ApplicationCall.clearBrowserSession() {
    val secure = parseWebOrigin(config.webOrigin).scheme == "https"
    response.headers.append(HttpHeaders.SetCookie, clearBrowserSessionCookie(secure))
  }

  routing {
    route("/v1") {
      if (config.fixtureProvider) {
        post("/fixture/signup") {
          val workspaceId = FixtureWorld.ensure(call)
          call.respond(SessionResponse(workspaceId, sessions.issue(workspaceId)))
        }
        post("/fixture/browser-signup") {
          val workspaceId = FixtureWorld.ensure(call)
          val csrfToken = call.setBrowserSession(sessions.issue(workspaceId))
          call.respond(BrowserSessionResponse(workspaceId, csrfToken))
        }
        // Stage 4.5's boundary leg: a fresh workspace on the free trial, untouched by the loop.
        post("/fixture/trial-workspace") {
          val workspaceId = FixtureWorld.ensureTrialWorkspace()
          call.respond(SessionResponse(workspaceId, sessions.issue(workspaceId)))
        }
      } else {
        // The real-account leg: the web client redirects to Google, then trades the code for
        // a server-side token (envelope-encrypted in calendar_connections) and a session.
        get("/auth/start") {
          if (config.googleClientId.isNullOrBlank() || config.googleClientSecret.isNullOrBlank()) {
            call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "Google OAuth is not configured on this deployment"))
            return@get
          }
          val redirectUri =
            runCatching {
              OAuthRedirects.validate(
                config,
                call.request.queryParameters["redirect_uri"] ?: OAuthRedirects.webCallback(config),
              )
            }.getOrElse {
              call.respond(HttpStatusCode.BadRequest, mapOf("error" to it.message.orEmpty()))
              return@get
            }
          val state = oauthState.issue(redirectUri)
          val codeChallenge = requireNotNull(oauthState.codeVerifier(state)) { "could not derive OAuth PKCE verifier" }
          call.respond(
            mapOf(
              "url" to GoogleOAuth.authorizeUrl(config, redirectUri, state, oauthState.codeChallenge(codeChallenge)),
              "redirectUri" to redirectUri,
            ),
          )
        }

        post("/auth/callback") {
          if (config.googleClientId.isNullOrBlank() || config.googleClientSecret.isNullOrBlank()) {
            call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "Google OAuth is not configured on this deployment"))
            return@post
          }
          val body = call.receive<AuthCallback>()
          val redirectUri = OAuthRedirects.validate(config, body.redirectUri)
          require(oauthState.verify(body.state, redirectUri)) { "OAuth state is missing, invalid, or expired" }
          val codeVerifier = requireNotNull(oauthState.codeVerifier(body.state)) { "OAuth PKCE verifier could not be derived" }
          val tokens = GoogleOAuth.exchangeCode(config, body.code, redirectUri, codeVerifier)
          val identity = GoogleOAuth.fetchIdentity(tokens.access_token)
          val workspaceId = FixtureWorld.ensure(call, email = identity.email, name = identity.name ?: identity.email)
          GoogleOAuth.storeToken(config, workspaceId, "google", "google-account", GoogleOAuth.json.encodeToString(tokens))
          val token = sessions.issue(workspaceId)
          if (redirectUri == OAuthRedirects.webCallback(config)) {
            val csrfToken = call.setBrowserSession(token)
            call.respond(BrowserSessionResponse(workspaceId, csrfToken))
          } else {
            call.respond(SessionResponse(workspaceId, token))
          }
        }
      }

      post("/auth/logout") {
        call.clearBrowserSession()
        call.respond(mapOf("ok" to true))
      }

      // Stage 4.5: the billing seam. Checkout is opened against the provider; the webhook is
      // where a payment becomes a paid workspace. The webhook endpoint is unauthenticated by
      // design — the provider's signature check is the authentication.
      val billingProvider: BillingProvider =
        when {
          config.fixtureProvider -> FixtureBillingProvider()
          config.stripeSecretKey != null && config.stripeWebhookSecret != null && config.stripePriceId != null ->
            StripeBillingProvider(config.stripeSecretKey, config.stripeWebhookSecret, config.stripePriceId)
          else -> NotConfiguredBillingProvider()
        }

      // The daily brief's seam: the platform key belongs to the server (invariant 4).
      // Fixture mode runs the journey headless; a real deployment activates the REST seam
      // when GEMINI_API_KEY is present; without one the seam answers honestly.
      val summaryProvider: SummaryProvider =
        when {
          config.fixtureProvider -> FixtureSummaryProvider()
          config.geminiApiKey != null -> GeminiSummaryProvider(config.geminiApiKey, config.geminiModel)
          else -> NotConfiguredSummaryProvider()
        }

      // The push seam: the registry lives in push_tokens (tenant-bound), delivery goes through
      // the provider. Fixture mode records deliveries for the journey; a deployment with
      // EXPO_ACCESS_TOKEN rings real phones; without one the seam stays silent.
      val pushProvider: PushProvider =
        when {
          config.fixtureProvider -> FixturePushProvider()
          config.expoAccessToken != null -> ExpoPushProvider(config.expoAccessToken)
          else -> NotConfiguredPushProvider()
        }

      post("/billing/webhook") {
        val payload = call.receive<String>()
        val signature = call.request.headers["X-DailyBrief-Signature"]
        when (val event = billingProvider.webhookEvent(payload, signature)) {
          is WebhookEvent.CheckoutCompleted -> {
            if (event.workspaceId != null) {
              SubscriptionStore.upgrade(event.workspaceId, System.currentTimeMillis())
            }
            call.respond(HttpStatusCode.NoContent)
          }
          else -> call.respond(HttpStatusCode.NoContent)
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

        fun planningProjectId(session: Session, boardId: String): String? {
          if (boardId == "b1") return FixtureWorld.projectId(session.workspaceId)
          return Db.dataSource.connection.use { conn ->
            conn.queryOne(
              "SELECT id FROM projects WHERE workspace_id = ? AND id = ? AND archived_at IS NULL",
              listOf(session.workspaceId, boardId),
            ) { it.getString(1) }
          }
        }

        fun planningItemError(session: Session, request: PlanningRequest): String? =
          request.items.firstOrNull { item ->
            Db.dataSource.connection.use { conn ->
              conn.queryOne(
                "SELECT 1 FROM tasks WHERE workspace_id = ? AND project_id = ? AND id = ? AND archived_at IS NULL",
                listOf(session.workspaceId, item.boardId, item.id),
              ) { it.getInt(1) } == null
            }
          }?.let { "task ${it.id} does not belong to project ${it.boardId} in this workspace" }

        fun countRows(workspaceId: String, table: String): Int =
          Db.dataSource.connection.use { conn ->
            conn.queryOne(
              "SELECT COUNT(*) FROM $table WHERE workspace_id = ?",
              listOf(workspaceId),
            ) { it.getInt(1) } ?: 0
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

        get("/settings/planning") {
          val session = sessions.require(call)
          call.respond(WorkingScheduleStore.getOrCreateDefault(session.workspaceId))
        }

        put("/settings/planning") {
          val session = sessions.require(call)
          val incoming = call.receive<com.example.contract.WorkScheduleWire>()
          try {
            call.respond(WorkingScheduleStore.updateDefault(session.workspaceId, incoming))
          } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to (e.message ?: "invalid working schedule")))
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
          val subscription = SubscriptionStore.read(session.workspaceId)
          if (subscription != null && !TierLimits.isPaid(subscription.tier, subscription.status)) {
            val used = countRows(session.workspaceId, "projects")
            if (used >= TierLimits.maxProjects(subscription.tier)) {
              call.respond(
                HttpStatusCode.PaymentRequired,
                mapOf("error" to "the free tier allows one project — upgrade to add more"),
              )
              return@post
            }
          }
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
          val tasks =
            Db.dataSource.connection.use { conn ->
              conn.query(
                "SELECT id, project_id, stage_id, parent_id, title, notes, rank, start_constraint, due_at, effort_minutes, progress, priority, owner, scheduling_mode, locked, is_milestone, completed_at, archived_at " +
                  "FROM tasks WHERE workspace_id = ? AND archived_at IS NULL ORDER BY rank, created_at",
                listOf(session.workspaceId),
              ) { rs ->
                com.example.contract.TaskWire(
                  id = rs.getString("id"),
                  boardId = rs.getString("project_id"),
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
          val canonicalRequest =
            request.copy(
              items = request.items.map { item ->
                item.copy(boardId = planningProjectId(session, item.boardId) ?: item.boardId)
              },
            )
          val planningError = planningItemError(session, canonicalRequest)
          if (planningError != null) {
            call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to planningError))
            return@post
          }
          val replaceExisting = call.request.queryParameters["replan"] == "1"
          val (runId, result) = cache.planAndStore(canonicalRequest, session.workspaceId, replaceExisting)
          call.respond(PlanRunResponse(runId, result))
        }

        post("/capacity") {
          val session = sessions.require(call)
          val subscription = SubscriptionStore.read(session.workspaceId)
          if (subscription == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no subscription"))
            return@post
          }
          if (!TierLimits.allowsCapacity(subscription.tier)) {
            call.respond(
              HttpStatusCode.PaymentRequired,
              mapOf("error" to "capacity is part of the paid tier — upgrade to measure another client"),
            )
            return@post
          }
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

        // Stage 4.4 — scenarios, baselines, portfolio. All three are analysis that writes
        // nothing: choosing a scenario is the caller's /plan call, a baseline is a snapshot,
        // and the portfolio reads the current week.

        fun taskWireRows(workspaceId: String, projectId: String? = null): List<TaskWire> =
          Db.dataSource.connection.use { conn ->
            val where =
              if (projectId == null) "workspace_id = ?" else "workspace_id = ? AND project_id = ?"
            val params =
              if (projectId == null) listOf(workspaceId) else listOf(workspaceId, projectId)
            conn.query(
              "SELECT id, project_id, parent_id, title, notes, rank, start_constraint, due_at, effort_minutes, progress, priority, owner, scheduling_mode, locked, is_milestone, completed_at, archived_at, stage_id " +
                "FROM tasks WHERE $where AND archived_at IS NULL ORDER BY rank",
              params,
            ) { rs ->
              TaskWire(
                id = rs.getString("id"),
                boardId = rs.getString("project_id"),
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
          }

        fun blockWireRows(workspaceId: String): List<ScheduledBlockWire> =
          Db.dataSource.connection.use { conn ->
            conn.query(
              "SELECT id, task_id, start_at, end_at, position, locked, linked_event_id FROM scheduled_blocks WHERE workspace_id = ? ORDER BY start_at",
              listOf(workspaceId),
            ) { rs ->
              ScheduledBlockWire(
                id = rs.getString("id"),
                planItemId = rs.getString("task_id"),
                startAt = rs.getLong("start_at"),
                endAt = rs.getLong("end_at"),
                position = rs.getInt("position"),
                locked = rs.getBoolean("locked"),
                linkedEventId = rs.getString("linked_event_id"),
              )
            }
          }

        /** The same three approaches the app's PlanScenarios compares, as wire orderings. */
        fun defaultOrderings(plan: PlanningRequest): List<ScenarioOrderingWire> {
          val items = plan.items
          val priorityRank =
            mapOf(
              "urgent" to 0,
              "high" to 1,
              "normal" to 2,
              "low" to 3,
            )
          return listOf(
            ScenarioOrderingWire(
              key = "due",
              name = "Due date first",
              rationale = "Whatever is due soonest gets the first free slot.",
              preferredOrder = emptyList(),
            ),
            ScenarioOrderingWire(
              key = "priority",
              name = "Priority first",
              rationale = "Urgent and high-priority work is placed before anything else.",
              preferredOrder =
                items
                  .sortedWith(compareBy({ priorityRank[it.priority] ?: 2 }, { it.rank }, { it.id }))
                  .map { it.id },
            ),
            ScenarioOrderingWire(
              key = "short",
              name = "Quick wins first",
              rationale = "The smallest pieces of stated effort go first, clearing the list faster.",
              preferredOrder =
                items
                  .sortedWith(compareBy({ it.effortMinutes ?: Int.MAX_VALUE }, { it.rank }, { it.id }))
                  .map { it.id },
            ),
          )
        }

        post("/scenarios") {
          val session = sessions.require(call)
          val request = call.receive<ScenarioRequestWire>()
          if (request.plan.workspaceId != session.workspaceId) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "workspace mismatch"))
            return@post
          }
          val refusal = request.plan.refusalReason()
          if (refusal != null) {
            call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to refusal))
            return@post
          }
          val orderings = request.orderings ?: defaultOrderings(request.plan)
          val scenarios =
            orderings.map { ordering ->
              val copy = request.plan.copy(preferredOrder = ordering.preferredOrder)
              val result = Mapping.propose(Mapping.toEngine(copy, copy.nowMs), copy)
              ScenarioResultWire(ordering.key, ordering.name, ordering.rationale, ordering.preferredOrder, result)
            }
          val spread =
            PlanScenarios.describeSpread(
              scenarios.map { scenario ->
                PlanScenarioAdapter.toDomain(scenario)
              },
            )
          call.respond(ScenarioResponseWire(PlannerApi.VERSION, scenarios, spread))
        }

        post("/baselines") {
          val session = sessions.require(call)
          val subscription = SubscriptionStore.read(session.workspaceId)
          if (subscription == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no subscription"))
            return@post
          }
          if (!TierLimits.allowsBaselines(subscription.tier)) {
            call.respond(
              HttpStatusCode.PaymentRequired,
              mapOf("error" to "baselines are part of the paid tier — upgrade to snapshot your plan"),
            )
            return@post
          }
          val tasks = taskWireRows(session.workspaceId)
          val blocks = blockWireRows(session.workspaceId)
          val id = newId()
          val now = System.currentTimeMillis()
          val payload =
            PlannerApi.json.encodeToString(
              BaselinePayload.serializer(),
              BaselinePayload(tasks, blocks),
            )
          Db.dataSource.connection.use { conn ->
            conn.execute(
              "INSERT INTO baselines (id, workspace_id, created_at, task_count, block_count, payload) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
              listOf(id, session.workspaceId, now, tasks.size, blocks.size, payload),
            )
          }
          call.respond(
            BaselineSnapshotWire(
              v = PlannerApi.VERSION,
              id = id,
              workspaceId = session.workspaceId,
              createdAt = now,
              taskCount = tasks.size,
              blockCount = blocks.size,
            ),
          )
        }

        get("/baselines") {
          val session = sessions.require(call)
          val snapshots =
            Db.dataSource.connection.use { conn ->
              conn.query(
                "SELECT id, created_at, task_count, block_count FROM baselines WHERE workspace_id = ? ORDER BY created_at DESC",
                listOf(session.workspaceId),
              ) { rs ->
                BaselineSnapshotWire(
                  v = PlannerApi.VERSION,
                  id = rs.getString("id"),
                  workspaceId = session.workspaceId,
                  createdAt = rs.getLong("created_at"),
                  taskCount = rs.getInt("task_count"),
                  blockCount = rs.getInt("block_count"),
                )
              }
            }
          call.respond(snapshots)
        }

        get("/baselines/{baselineId}/variance") {
          val session = sessions.require(call)
          val baselineId = call.parameters["baselineId"] ?: error("baselineId required")
          val payload =
            Db.dataSource.connection.use { conn ->
              conn.queryOne(
                "SELECT payload FROM baselines WHERE workspace_id = ? AND id = ?",
                listOf(session.workspaceId, baselineId),
              ) { rs -> rs.getString("payload") }
            }
          if (payload == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such baseline"))
            return@get
          }
          val snapshot =
            PlannerApi.json.decodeFromString(BaselinePayload.serializer(), payload)
          val now = System.currentTimeMillis()
          val comparison =
            BaselineVariance.compare(
              baselineItems = snapshot.tasks.map { it.toEngine(now) },
              baselineBlocks = snapshot.blocks.map { it.toEngine(now) },
              currentItems = taskWireRows(session.workspaceId).map { it.toEngine(now) },
              currentBlocks = blockWireRows(session.workspaceId).map { it.toEngine(now) },
            )
          call.respond(
            BaselineComparisonWire(
              v = PlannerApi.VERSION,
              summary = comparison.summary,
              rows =
                comparison.rows.map { row ->
                  TaskVarianceWire(
                    itemId = row.itemId,
                    title = row.title,
                    baselineStartMs = row.baselineStartMs,
                    currentStartMs = row.currentStartMs,
                    baselineMinutes = row.baselineMinutes,
                    currentMinutes = row.currentMinutes,
                    driftMinutes = row.driftMinutes,
                    addedSinceBaseline = row.addedSinceBaseline,
                    removedSinceBaseline = row.removedSinceBaseline,
                  )
                },
            ),
          )
        }

        get("/portfolio") {
          val session = sessions.require(call)
          val now = System.currentTimeMillis()
          val weekStart =
            java.time.Instant.ofEpochMilli(now).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
              .minus(java.time.Duration.ofDays(java.time.DayOfWeek.MONDAY.getValue() - 1L))
              .toEpochMilli()
          val weekEnd = weekStart + 7 * 24 * 60 * 60 * 1000L
          val projects =
            Db.dataSource.connection.use { conn ->
              conn.query(
                "SELECT id, name FROM projects WHERE workspace_id = ? AND archived_at IS NULL ORDER BY rank, created_at",
                listOf(session.workspaceId),
              ) { rs -> rs.getString("id") to rs.getString("name") }
            }
          val tasksByProject =
            projects.map { (id, _) -> id to taskWireRows(session.workspaceId, id) }.toMap()
          val allBlocks = blockWireRows(session.workspaceId)
          val weekBlocksByProject =
            tasksByProject.mapValues { (projectId, tasks) ->
              val ids = tasks.mapTo(mutableSetOf()) { it.id }
              allBlocks.filter { it.planItemId in ids && it.startAt < weekEnd && it.endAt > weekStart }
            }
          val itemsByBoard =
            tasksByProject.mapValues { (_, tasks) -> tasks.map { it.toEngine(now) } }
          val blocksByItem =
            allBlocks.groupBy { it.planItemId }.mapValues { (_, blocks) -> blocks.map { it.toEngine(now) } }
          val rollup =
            PortfolioRollup.summarise(
              boards = projects,
              itemsByBoard = itemsByBoard,
              blocksByItem = blocksByItem,
              nowMs = now,
            )
          val byId = rollup.rows.associateBy { it.boardId }
          call.respond(
            PortfolioResponseWire(
              v = PlannerApi.VERSION,
              rows =
                projects.map { (projectId, projectName) ->
                  val row = requireNotNull(byId[projectId])
                  PortfolioRowWire(
                    projectId = projectId,
                    projectName = projectName,
                    openTasks = row.openTaskCount,
                    doneTasks = row.doneTaskCount,
                    statedEffortMinutes = row.statedEffortMinutes,
                    scheduledMinutes = row.scheduledMinutes,
                    overdueTasks = row.overdueTaskCount,
                    unestimatedTasks = row.unestimatedTaskCount,
                    weekBlocks =
                      weekBlocksByProject[projectId].orEmpty().map { block ->
                        PortfolioBlockWire(
                          itemId = block.planItemId,
                          title = "",
                          startAt = block.startAt,
                          endAt = block.endAt,
                        )
                      },
                  )
                },
              note = rollup.note,
            ),
          )
        }

        // The planner UI needs stable block ids when it prepares a replacement proposal. Keep
        // this operational read separate from the frozen planning contract: it is not an engine
        // input and is never accepted from an untrusted client without workspace scoping.
        get("/plan-blocks") {
          val session = sessions.require(call)
          val blocks =
            Db.dataSource.connection.use { conn ->
              conn.query(
                "SELECT id, project_id, task_id, start_at, end_at, position, locked, linked_event_id " +
                  "FROM scheduled_blocks WHERE workspace_id = ? ORDER BY start_at, id",
                listOf(session.workspaceId),
              ) { rs ->
                PlanBlockResponse(
                  id = rs.getString("id"),
                  projectId = rs.getString("project_id"),
                  taskId = rs.getString("task_id"),
                  startAt = rs.getLong("start_at"),
                  endAt = rs.getLong("end_at"),
                  position = rs.getInt("position"),
                  locked = rs.getBoolean("locked"),
                  linkedEventId = rs.getString("linked_event_id"),
                )
              }
            }
          call.respond(blocks)
        }

        post("/billing/checkout") {
          val session = sessions.require(call)
          val tier =
            SubscriptionStore.read(session.workspaceId)?.tier ?: run {
              call.respond(HttpStatusCode.NotFound, mapOf("error" to "no subscription"))
              return@post
            }
          val url = billingProvider.checkoutUrl(session.workspaceId, tier)
          if (url == null) {
            call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "billing is not configured on this deployment"))
          } else {
            call.respond(mapOf("url" to url))
          }
        }

        get("/billing") {
          val session = sessions.require(call)
          val sub = SubscriptionStore.read(session.workspaceId)
          if (sub == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no subscription"))
            return@get
          }
          call.respond(
            BillingResponseWire(
              v = PlannerApi.VERSION,
              tier = sub.tier,
              status = sub.status,
              trialEndsAt = sub.trialEndsAt,
              limits =
                TierLimitsWire(
                  maxProjects = TierLimits.maxProjects(sub.tier),
                  baselines = TierLimits.allowsBaselines(sub.tier),
                  capacity = TierLimits.allowsCapacity(sub.tier),
                ),
              usage =
                TierUsageWire(
                  projects = countRows(session.workspaceId, "projects"),
                  baselines = countRows(session.workspaceId, "baselines"),
                ),
              checkoutUrl = billingProvider.checkoutUrl(session.workspaceId, sub.tier),
            ),
          )
        }

        post("/plan/{runId}/apply") {
          val session = sessions.require(call)
          val runId = call.parameters["runId"] ?: error("runId required")
          val proposals = PlanStore.proposalsFor(session.workspaceId, runId)
          if (proposals.isEmpty()) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no proposals for this run"))
            return@post
          }
          val now = System.currentTimeMillis()
          val projectProposals = proposals.map { Journal.ProjectProposal(it.projectId, it.proposal) }
          val replacementBlocks = PlanStore.replacementBlocksFor(session.workspaceId, runId)
          val applied =
            if (replacementBlocks.isEmpty()) {
              Journal.applyProposals(session.workspaceId, projectProposals, now)
            } else {
              Journal.replaceProposals(session.workspaceId, replacementBlocks, projectProposals, now)
                ?: run {
                  call.respond(HttpStatusCode.Conflict, mapOf("error" to "the schedule changed; replan before applying"))
                  return@post
                }
            }
          val (blocks, entryId) = applied
          PlanStore.markRun(session.workspaceId, runId, "accepted")
          // Fire-and-forget: a committed plan is the one moment worth a phone's attention.
          // The delivery is bounded by the provider's timeouts and wrapped so a push outage
          // can never fail an apply that already committed.
          runCatching {
            val tokens =
              Db.dataSource.connection.use { conn ->
                conn.query("SELECT token FROM push_tokens WHERE workspace_id = ?", listOf(session.workspaceId)) {
                  it.getString(1)
                }
              }
            tokens.forEach { token ->
              pushProvider.send(
                token,
                PushPayload(
                  title = "Your plan was applied",
                  body = if (blocks.size == 1) "1 block applied to your week" else "${blocks.size} blocks applied to your week",
                  data = mapOf("type" to "plan-applied", "entryId" to entryId),
                ),
              )
            }
          }
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

        post("/summary") {
          val session = sessions.require(call)
          if (!summaryProvider.configured) {
            call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "no Gemini key configured on this deployment"))
            return@post
          }
          val request = call.receive<SummaryRequestWire>()
          val nowMs = System.currentTimeMillis()
          val date =
            request.date ?: java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
          val today =
            Today.forWorkspace(
              session.workspaceId,
              java.time.Instant.parse("${date}T12:00:00Z").toEpochMilli(),
            )
          val context =
            SummaryContext(
              date = date,
              dayName = java.time.LocalDate.parse(date).dayOfWeek.toString().lowercase().replaceFirstChar { it.uppercase() },
              eventTitles = today.events.map { it.title },
              blockCount = today.blocks.size,
              conflictCount = today.conflicts.size,
              busyMinutes = today.busyMinutes,
              freeMinutes = today.freeMinutes,
            )
          val quotaJson =
            Db.dataSource.connection.use { conn ->
              conn.queryOne("SELECT quota_json FROM subscriptions WHERE workspace_id = ?", listOf(session.workspaceId)) {
                it.getString(1)
              } ?: "{}"
            }
          val limit = GeminiQuota.limitFrom(quotaJson)
          val month = GeminiQuota.periodMonth(nowMs)
          // Reserve-then-refuse in the request transaction (invariant 4): increment the
          // ledger first; if the limit would be exceeded, roll back and say so.
          val usedAfter: Long =
            try {
              Db.inTransaction { conn ->
                val used =
                  conn.queryOne(
                    "SELECT requests FROM gemini_usage WHERE workspace_id = ? AND period_month = ? FOR UPDATE",
                    listOf(session.workspaceId, month),
                  ) { it.getLong(1) } ?: 0L
                if (GeminiQuota.refuses(used, limit)) {
                  throw QuotaExceeded(used, limit)
                }
                conn.execute(
                  "INSERT INTO gemini_usage (workspace_id, period_month, requests, input_tokens, output_tokens) VALUES (?, ?, 1, 0, 0) " +
                    "ON CONFLICT (workspace_id, period_month) DO UPDATE SET requests = gemini_usage.requests + 1",
                  listOf(session.workspaceId, month),
                )
                used + 1
              }
            } catch (e: QuotaExceeded) {
              call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "summary quota exhausted — ${e.used} of ${e.limit} used this month"))
              return@post
            }
          val text =
            try {
              summaryProvider.summarize(context).text
            } catch (t: Throwable) {
              // The provider failed; the reservation is refunded so the failure is not counted.
              Db.dataSource.connection.use { conn ->
                conn.execute(
                  "UPDATE gemini_usage SET requests = requests - 1 WHERE workspace_id = ? AND period_month = ?",
                  listOf(session.workspaceId, month),
                )
              }
              throw t
            }
          call.respond(
            SummaryResponseWire(
              v = PlannerApi.VERSION,
              date = date,
              text = text,
              source = if (config.fixtureProvider) "fixture" else "platform",
              used = usedAfter.toInt(),
              limit = limit,
            ),
          )
        }

        // Push: the registry is tenant-bound by primary key — a token registered by one
        // workspace can never be delivered to by another. The trigger is Apply: a committed
        // plan is the one moment worth a phone's attention, and the delivery is
        // fire-and-forget so a push outage never fails an apply.
        post("/devices") {
          val session = sessions.require(call)
          val body = call.receive<DeviceRequest>()
          if (body.token.isBlank()) {
            call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "push token cannot be empty"))
            return@post
          }
          val now = System.currentTimeMillis()
          Db.dataSource.connection.use { conn ->
            conn.execute(
              "INSERT INTO push_tokens (workspace_id, token, platform, created_at, updated_at) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (workspace_id, token) DO UPDATE SET platform = excluded.platform, updated_at = excluded.updated_at",
              listOf(session.workspaceId, body.token, body.platform ?: "expo", now, now),
            )
          }
          call.respond(mapOf("ok" to true))
        }

        delete("/devices") {
          val session = sessions.require(call)
          val body = call.receive<DeviceRequest>()
          Db.dataSource.connection.use { conn ->
            conn.execute(
              "DELETE FROM push_tokens WHERE workspace_id = ? AND token = ?",
              listOf(session.workspaceId, body.token),
            )
          }
          call.respond(mapOf("ok" to true))
        }

        // Fixture-only: the journey reads the recorded deliveries and proves the payload and
        // the tenant isolation. Never wired outside fixture mode.
        if (config.fixtureProvider && pushProvider is FixturePushProvider) {
          get("/fixture/push-deliveries") {
            val token = call.request.queryParameters["token"]
            call.respond(
              buildJsonObject {
                putJsonArray("deliveries") {
                  pushProvider.deliveriesFor(token).forEach {
                    addJsonObject {
                      put("token", it.token)
                      put("title", it.title)
                      put("body", it.body)
                      putJsonObject("data") {
                        it.data.forEach { (key, value) -> put(key, value) }
                      }
                    }
                  }
                }
              },
            )
          }
        }

        // Fixture-only: the WP-14 sync ledger, so the journey can prove a reconcile ran and
        // committed. The ledger itself is written in every mode; only this read is fixture-only.
        if (config.fixtureProvider) {
          get("/fixture/sync-runs") {
            val session = sessions.require(call)
            val runs =
              Db.dataSource.connection.use { conn ->
                conn.query(
                  "SELECT id, started_at, finished_at, duration_ms, events_seen, status FROM sync_runs WHERE workspace_id = ? ORDER BY started_at DESC",
                  listOf(session.workspaceId),
                ) { rs ->
                  SyncRunRow(
                    id = rs.getString("id"),
                    startedAt = rs.getLong("started_at"),
                    finishedAt = rs.getLong("finished_at"),
                    durationMs = rs.getLong("duration_ms"),
                    eventsSeen = rs.getInt("events_seen"),
                    status = rs.getString("status"),
                  )
                }
              }
            call.respond(runs)
          }
        }
      }
    }
  }
}

/** The typed quota refusal; the request transaction rolls back the reservation with it. */
private class QuotaExceeded(val used: Long, val limit: Int) : Exception()

/** The push registry's wire shape: a token plus the platform that minted it. */
@kotlinx.serialization.Serializable
private data class DeviceRequest(
  val token: String = "",
  val platform: String? = null,
)

/** One row of the WP-14 sync ledger, as the fixture read returns it. */
@Serializable
private data class SyncRunRow(
  val id: String,
  val startedAt: Long,
  val finishedAt: Long,
  val durationMs: Long,
  val eventsSeen: Int,
  val status: String,
)

@Serializable
data class SessionResponse(val workspaceId: String, val token: String)

/** Web sign-in returns only the workspace identity; the session token stays HttpOnly. */
@Serializable
data class BrowserSessionResponse(val workspaceId: String, val csrfToken: String)

@Serializable
data class PlanRunResponse(val runId: String, val result: PlanningResult)

/** What a baseline holds: the frozen wire forms, so decoding never needs per-row SQL. */
@Serializable
data class BaselinePayload(
  val tasks: List<TaskWire>,
  val blocks: List<ScheduledBlockWire>,
)

/**
 * The scenario sentence (`PlanScenarios.describeSpread`) speaks in domain results; the wire
 * result is the same shape with the same fields, so this adapter hands it back without
 * re-running anything.
 */
private object PlanScenarioAdapter {
  fun toDomain(scenario: ScenarioResultWire): PlanScenario {
    val result = scenario.result
    return PlanScenario(
      key = scenario.key,
      name = scenario.name,
      rationale = scenario.rationale,
      result =
        AutoPlanResult(
          proposals = result.proposals.map { PlanProposal(it.itemId, it.startAt, it.endAt, it.reason) },
          unplaced = result.unplaced.map { UnplacedTask(it.itemId, it.reason) },
          explanation = result.explanation,
        ),
    )
  }
}

@Serializable
data class ApplyResponse(val blocks: Int, val entryId: String)

@Serializable
data class PlanBlockResponse(
  val id: String,
  val projectId: String,
  val taskId: String,
  val startAt: Long,
  val endAt: Long,
  val position: Int = 0,
  val locked: Boolean = false,
  val linkedEventId: String? = null,
)

@Serializable
data class AuthCallback(val code: String, val redirectUri: String, val state: String? = null)

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
    val workspaceId = Db.inTransaction { conn ->
      conn.queryOne(
        "SELECT w.id FROM workspaces w JOIN users u ON u.id = w.owner_user_id WHERE u.email = ? ORDER BY w.created_at LIMIT 1",
        listOf(email),
      ) { it.getString(1) } ?: run {
        val now = System.currentTimeMillis()
        conn.execute(
          "INSERT INTO users (id, email, name, tier, created_at, updated_at) VALUES (?, ?, ?, 'paid', ?, ?) ON CONFLICT (email) DO NOTHING",
          listOf(newId(), email, name, now, now),
        )
        val userId = conn.queryOne("SELECT id FROM users WHERE email = ?", listOf(email)) { it.getString(1) }
          ?: error("user creation did not return an id")
        conn.queryOne(
          "SELECT id FROM workspaces WHERE owner_user_id = ? ORDER BY created_at LIMIT 1",
          listOf(userId),
        ) { it.getString(1) } ?: run {
          val ws = newId()
          conn.execute(
            "INSERT INTO workspaces (id, name, owner_user_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            listOf(ws, "Fixture Workspace", userId, now, now),
          )
          ws
        }
      }
    }
    // The main journey workspace is paid: the loop's capacity, baselines and second project
    // steps are paid surfaces, and the tier boundary has its own trial leg (step 9).
    Db.inTransaction { conn ->
      conn.execute(
        "INSERT INTO subscriptions (id, workspace_id, tier, status, quota_json, created_at, updated_at) VALUES (?, ?, 'paid', 'active', '{}', ?, ?) " +
          "ON CONFLICT (workspace_id) DO NOTHING",
        listOf(newId(), workspaceId, System.currentTimeMillis(), System.currentTimeMillis()),
      )
    }
    projectId(workspaceId)
    return workspaceId
  }

  /** A fresh workspace on the free trial, for exercising the boundary without touching the loop. */
  fun ensureTrialWorkspace(): String =
    Db.inTransaction { conn ->
      val userId = newId()
      val ws = newId()
      val now = System.currentTimeMillis()
      conn.execute(
        "INSERT INTO users (id, email, name, tier, created_at, updated_at) VALUES (?, ?, ?, 'free', ?, ?)",
        listOf(userId, "trial-${now}@dailybrief.dev", "Trial User", now, now),
      )
      conn.execute(
        "INSERT INTO workspaces (id, name, owner_user_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
        listOf(ws, "Trial Workspace", userId, now, now),
      )
      SubscriptionStore.ensureTrial(conn, ws, now)
      ws
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
  val connectionId = connection.first
  val stored = Envelope.unwrap(KeyProviders.from(config), workspaceId, "calendar_connection_$connectionId", connection.second)
    ?: error("could not unwrap the stored token for workspace $workspaceId")
  val token = GoogleOAuth.json.decodeFromString<GoogleOAuth.TokenResponse>(stored)
  // Refresh is WP-14 hardening: Google access tokens live ~1 hour. When a fetch is refused
  // with 401/403, the provider refreshes once through the OAuth endpoint, persists the fresh
  // pair back through the envelope, and retries — so a long demo never needs a re-consent.
  val storedRefreshToken = token.refresh_token
  val refresher =
    storedRefreshToken?.let { refreshToken ->
      {
        GoogleOAuth.refreshAccessToken(config, refreshToken)
      }
    }
  return GoogleCalendarProvider(
    config,
    connectionId,
    token.access_token,
    refresher = refresher,
    onTokensRefreshed = { fresh ->
      // A refresh response may or may not rotate the refresh token; keep the stored one when
      // it does not. The envelope authority is the current KeyProvider, like storeToken.
      val pair = fresh.copy(refresh_token = fresh.refresh_token ?: storedRefreshToken)
      val keyProvider = KeyProviders.from(config)
      val envelope = Envelope.wrap(keyProvider, workspaceId, "calendar_connection_$connectionId", GoogleOAuth.json.encodeToString(pair))
      Db.dataSource.connection.use { conn ->
        conn.execute(
          "UPDATE calendar_connections SET token_ciphertext = ?, token_kms_key_id = ?, updated_at = ? WHERE workspace_id = ? AND id = ?",
          listOf(envelope, keyProvider.keyId, System.currentTimeMillis(), workspaceId, connectionId),
        )
      }
    },
  )
}

object PlanStore {
  private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

  data class StoredProposal(
    val projectId: String,
    val proposal: com.example.contract.PlanProposalWire,
  )

  fun proposalsFor(workspaceId: String, runId: String): List<StoredProposal> =
    Db.dataSource.connection.use { conn ->
      conn.query(
        "SELECT project_id, task_id, start_at, end_at, reason FROM plan_proposals WHERE workspace_id = ? AND plan_run_id = ? AND status = 'proposed' ORDER BY start_at",
        listOf(workspaceId, runId),
      ) { rs ->
        StoredProposal(
          projectId = rs.getString("project_id"),
          proposal = com.example.contract.PlanProposalWire(rs.getString("task_id"), rs.getLong("start_at"), rs.getLong("end_at"), rs.getString("reason")),
        )
      }
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

  fun replacementBlocksFor(workspaceId: String, runId: String): List<ScheduledBlockWire> {
    val raw =
      Db.dataSource.connection.use { conn ->
        conn.queryOne(
          "SELECT request_json::text FROM plan_runs WHERE workspace_id = ? AND id = ?",
          listOf(workspaceId, runId),
        ) { it.getString(1) }
      } ?: return emptyList()
    return runCatching { json.decodeFromString<StoredPlanRequest>(raw).replacementBlocks }
      .getOrElse { emptyList() }
  }
}

/** Internal plan-run metadata; the public planner request remains the frozen v1 shape. */
@Serializable
private data class StoredPlanRequest(
  val request: PlanningRequest,
  val replacementBlocks: List<ScheduledBlockWire> = emptyList(),
)

/**
 * The determinism cache (04): same request hash against the same data_version is the same
 * plan. Hash = SHA-256 of the canonical request bytes; data_version is bumped by every
 * write. In-memory for the slice; a shared cache is a deployment concern.
 */
class PlanCache {
  private val json = Json { encodeDefaults = true }
  private val map = mutableMapOf<String, PlanningResult>()
  private val rng = SecureRandom()

  fun planAndStore(request: PlanningRequest, workspaceId: String, replaceExisting: Boolean = false): Pair<String, PlanningResult> {
    val itemIds = request.items.mapTo(mutableSetOf()) { it.id }
    val replacementBlocks =
      if (replaceExisting) request.blocks.filter { it.planItemId in itemIds && !it.locked }
      else emptyList()
    val replacementIds = replacementBlocks.mapTo(mutableSetOf()) { it.id }
    val planningRequest =
      if (replacementBlocks.isEmpty()) request
      else request.copy(blocks = request.blocks.filterNot { it.id in replacementIds })
    val requestHash = sha256((if (replaceExisting) "replan:" else "plan:") + json.encodeToString(planningRequest))
    val dataVersion = dataVersion(workspaceId)
    val key = "$requestHash.$dataVersion"
    val result = map.getOrPut(key) { Mapping.propose(Mapping.toEngine(planningRequest, System.currentTimeMillis()), planningRequest) }

    val runId = newId()
    val now = System.currentTimeMillis()
    Db.inTransaction { conn ->
      conn.execute(
        "INSERT INTO plan_runs (id, workspace_id, request_hash, request_json, data_version, status, created_at) VALUES (?, ?, ?, ?, ?, 'accepted', ?)",
        listOf(
          runId,
          workspaceId,
          requestHash,
          json.encodeToString(StoredPlanRequest(request, replacementBlocks)),
          dataVersion,
          now,
        ),
      )
      val projectIdByTaskId = request.items.associate { it.id to it.boardId }
      result.proposals.forEach { p ->
        val projectId = projectIdByTaskId[p.itemId] ?: error("planner proposed unknown task ${p.itemId}")
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
