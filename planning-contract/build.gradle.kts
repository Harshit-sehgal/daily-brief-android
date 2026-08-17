plugins {
  // No version: the Kotlin plugin is already pinned on the root buildscript classpath,
  // and the serialization plugin ships inside the Kotlin Gradle plugin distribution.
  id("org.jetbrains.kotlin.jvm")
  // Versioned like the compose plugin: unlike the Kotlin plugin itself, the serialization
  // plugin is not on the root buildscript classpath, so it needs a resolvable coordinate.
  alias(libs.plugins.kotlin.serialization)
}

// The frozen wire contract between the phone, the server and (later) the web client.
// Nothing in Stage 3 starts until this module is stable: the service maps engine shapes
// to these DTOs, the app serializes these DTOs, and the golden files pin the exact bytes.
//
// This module deliberately depends on nothing but kotlinx-serialization. The engine types
// must stay out: the contract is a versioned agreement, not a dependency on the engine's
// internal shape.

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}