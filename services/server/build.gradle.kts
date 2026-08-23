plugins {
  // No version: the Kotlin plugin is already pinned on the root buildscript classpath.
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.kotlin.plugin.serialization")
  application
}

// The WP-13 vertical slice: Ktor service wrapping planning-core. The API calls the engine,
// never reimplements it; :planning-contract is the frozen wire; the WP-10 schema is V1.
// This module's tests run against a real Postgres (docker) via scripts/journey.sh; the
// unit tests here are pure and need no database.

kotlin {
  jvmToolchain(21)
}

application {
  mainClass.set("com.example.server.MainKt")
}

dependencies {
  implementation(project(":planning-core"))
  implementation(project(":planning-contract"))

  implementation(libs.kotlinx.serialization.json)
  implementation("io.ktor:ktor-server-core-jvm:3.4.1")
  implementation("io.ktor:ktor-server-netty-jvm:3.4.1")
  implementation("io.ktor:ktor-server-content-negotiation-jvm:3.4.1")
  implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.4.1")
  implementation("io.ktor:ktor-server-auth-jvm:3.4.1")
  implementation("io.ktor:ktor-server-cors-jvm:3.4.1")
  implementation("io.ktor:ktor-server-call-logging-jvm:3.4.1")
  implementation("org.postgresql:postgresql:42.7.7")
  implementation("com.zaxxer:HikariCP:7.0.2")
  implementation("com.squareup.okhttp3:okhttp:5.4.0")
  implementation("org.flywaydb:flyway-core:11.9.1")
  implementation("org.flywaydb:flyway-database-postgresql:11.9.1")
  implementation("ch.qos.logback:logback-classic:1.5.18")

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation("io.ktor:ktor-server-test-host-jvm:3.4.1")
}
