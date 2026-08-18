plugins {
  // No version: the Kotlin plugin is already pinned on the root buildscript classpath,
  // and the serialization plugin ships inside the Kotlin Gradle plugin distribution.
  id("org.jetbrains.kotlin.multiplatform")
  alias(libs.plugins.android.kotlin.multiplatform.library)
  alias(libs.plugins.kotlin.serialization)
  id("maven-publish")
}

// The frozen wire contract between the phone, the server and the web client.
// Nothing in Stage 3 starts until this module is stable: the service maps engine shapes
// to these DTOs, the app serializes these DTOs, and the golden files pin the exact bytes.
//
// This module deliberately depends on nothing but kotlinx-serialization. The engine types
// must stay out: the contract is a versioned agreement, not a dependency on the engine's
// internal shape.
//
// The targets are KMP now because the planner-engine native module runs the engine on iOS
// too, and the contract types travel there inside the PlannerCore framework. The wire
// bytes never change with the platform — the golden files in jvmTest pin that — and the
// JVM consumers (:server, :app, the jvm target) are untouched.

group = "com.example"
version = "0.1.0"

kotlin {
  jvmToolchain(21)

  jvm()

  // The Android target exists because planning-core's android AAR carries the contract as
  // an `api` dependency: the mobile planner-engine module consumes the AAR, and its POM
  // resolves the contract to the android variant. The contract itself is pure common code,
  // so the target has no androidMain sources of its own.
  androidLibrary {
    namespace = "com.example.contract"
    // Same arrangement as planning-core: the gate builds at 37.1, the mobile-facing
    // publication at 36 (-PmobileCompileSdk=36) because AGP 8.12 has no minor API levels.
    if (providers.gradleProperty("mobileCompileSdk").isPresent) {
      compileSdk = providers.gradleProperty("mobileCompileSdk").get().toInt()
    } else {
      compileSdk { version = release(37) { minorApiLevel = 1 } }
    }
    minSdk = 24
  }

  // The Apple + Linux targets exist so commonMain metadata gets a consumer on this machine
  // (linuxX64 compiles here) and so the iOS framework can embed the contract types; they
  // never *build* on Linux (Apple targets need macOS), exactly like planning-core.
  linuxX64()
  listOf(iosArm64(), iosSimulatorArm64()).forEach { /* klib dependency of planning-core only */ }

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain.dependencies {
      api(libs.kotlinx.serialization.json)
    }
    getByName("jvmTest").dependencies { implementation(libs.junit) }
  }
}

// The KMP publishing plugin registers the jvm / linuxX64 / iosArm64 / iosSimulatorArm64
// publications automatically; this block exists to name them deliberately.
publishing {
  publications.withType<MavenPublication>().configureEach {
    pom {
      description = "Frozen wire contract of the Daily Brief planner API"
    }
  }
}

