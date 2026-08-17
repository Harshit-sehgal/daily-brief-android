package com.example.server

import com.example.server.db.Db
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
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
  routes(config)
}