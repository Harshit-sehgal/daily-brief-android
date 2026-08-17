package com.example.server

import com.example.server.db.Db
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

fun main() {
  val config = Config.fromEnv()
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
      val origin = config.webOrigin.replaceFirst(Regex("^https?://"), "")
      allowHost(origin, listOf("http", "https"))
    allowHeader(HttpHeaders.ContentType)
    allowHeader(HttpHeaders.Authorization)
    allowMethod(HttpMethod.Post)
    allowMethod(HttpMethod.Get)
    allowMethod(HttpMethod.Options)
    allowCredentials = false
  }
  routes(config)
}