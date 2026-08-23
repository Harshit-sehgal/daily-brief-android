package com.example.server

import com.example.server.db.Db
import com.example.server.key.KeyProviders
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.application.Application
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.net.URI
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

internal data class WebOrigin(val authority: String, val scheme: String)

internal fun parseWebOrigin(raw: String): WebOrigin {
  val uri = URI(raw)
  require(uri.scheme == "http" || uri.scheme == "https") { "WEB_ORIGIN must use http or https" }
  require(!uri.authority.isNullOrBlank() && uri.userInfo == null) { "WEB_ORIGIN must include a host and port, without credentials" }
  require(uri.path.isEmpty() && uri.query == null && uri.fragment == null) { "WEB_ORIGIN must be an origin without a path or query" }
  return WebOrigin(uri.authority, uri.scheme)
}

fun main() {
  val config = Config.fromEnv()
  // Validate the selected wrapping authority before Flyway or the listener starts. This is a
  // local shape check only; KMS network access still occurs when an envelope is used.
  KeyProviders.from(config)
  parseWebOrigin(config.webOrigin)
  Flyway.configure()
    .dataSource(config.databaseUrl, config.databaseUser, config.databasePassword)
    .load()
    .migrate()
  Db.init(config)
  embeddedServer(Netty, port = config.port, host = "0.0.0.0", module = { module(config) }).start(wait = true)
}

fun Application.module(config: Config) {
  install(ContentNegotiation) { json(com.example.contract.PlannerApi.json) }
  install(CallLogging) { logger = LoggerFactory.getLogger("server") }
  // The web client is a browser: it calls the API cross-origin (localhost:3000 → :8090).
  install(CORS) {
    val origin = parseWebOrigin(config.webOrigin)
    allowHost(origin.authority, listOf(origin.scheme))
    allowHeader(HttpHeaders.ContentType)
    allowHeader(HttpHeaders.Authorization)
    allowHeader(CSRF_HEADER)
    exposeHeader(CSRF_HEADER)
    allowMethod(HttpMethod.Post)
    allowMethod(HttpMethod.Put)
    allowMethod(HttpMethod.Get)
    allowMethod(HttpMethod.Options)
    // Browser sessions are HttpOnly cookies. Bearer clients (mobile and scripts) still work
    // through the same authentication provider, but browsers must opt into credentials here.
    allowCredentials = true
  }
  routing {
    // These endpoints intentionally sit outside /v1 and authentication. They are for an
    // orchestrator/load balancer, not for application data. Readiness proves that the pool can
    // execute a trivial query; liveness only proves that the process is accepting requests.
    get("/health/live") {
      call.respond(mapOf("status" to "ok"))
    }
    get("/health/ready") {
      val ready = runCatching {
        Db.dataSource.connection.use { connection ->
          connection.prepareStatement("SELECT 1").use { statement ->
            statement.executeQuery().use { result -> result.next() }
          }
        }
      }.getOrDefault(false)
      if (ready) {
        call.respond(mapOf("status" to "ready"))
      } else {
        call.respond(io.ktor.http.HttpStatusCode.ServiceUnavailable, mapOf("status" to "not_ready"))
      }
    }
    routes(config)
  }
}
