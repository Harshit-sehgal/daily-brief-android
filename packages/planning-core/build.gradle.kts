plugins {
  // No version: the Kotlin plugin is already pinned on the root buildscript classpath,
  // and asking for a version again fails the compatibility check.
  id("org.jetbrains.kotlin.multiplatform")
  // AGP 9 refuses `com.android.library` alongside the multiplatform plugin; this is the
  // replacement it points at.
  alias(libs.plugins.android.kotlin.multiplatform.library)
  id("maven-publish")
}

group = "com.example"
version = "0.1.0"

// The planning engine, split out of the Android app so a server can run the same
// scheduling code the phone runs.
//
// The source-set layout is the point of this module. `commonMain` is compiled against
// the common stdlib only, so a stray `java.util.Calendar` fails to build there rather
// than at some later iOS attempt. Everything that still needs JVM APIs lives in
// `jvmShared`, which both the server target and Android depend on. Porting the engine
// is therefore one measurable move at a time: a file graduates from `jvmShared` to
// `commonMain`, and the share of lines in `commonMain` is the readiness number.
kotlin {
  jvm()

  // The whole point of this module: with a non-JVM target present, Kotlin finally has a
  // consumer for common metadata, so compileCommonMainKotlinMetadata stops being SKIPPED
  // and a stray java.* import in commonMain fails the build instead of the source-scan test.
  // linuxX64 is the cheapest non-JVM target on this machine; iosArm64 and iosSimulatorArm64
  // are the real ones (WP-16) but only *build* on macOS — the targets themselves configure
  // and resolve their metadata everywhere, so Linux keeps linuxX64 as the compiled guard and
  // a Mac compiles the Apple ones. The ~1 GB native toolchain needs one connected run to
  // fetch; the gate stays --offline afterwards (scripts/verify.sh --online for that run).
  linuxX64()
  // The Apple targets (WP-16): they configure everywhere and only *build* on macOS, where
  // scripts/build-ios-framework.sh turns them into the PlannerCore XCFramework the mobile
  // app's Swift module links against. One static framework, one ObjC name — the narrow
  // bridge the mobile client exposes is previewPlan(requestJson) -> resultJson.
  listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
    target.binaries.framework {
      baseName = "PlannerCore"
      isStatic = true
    }
  }

  androidLibrary {
    namespace = "com.example.planning"
    // Must match the app exactly. This toolchain has platform 37.1 installed and nothing
    // else; a bare `compileSdk = 37` asks for 37.0, which sends Gradle off to download a
    // platform that is not in the pinned SDK — the build then stalls on the network with
    // no visible progress at all.
    //
    // The mobile Expo app (AGP 8.12, no minor API levels) cannot consume a 37.1 AAR, so
    // the mobile-facing publication compiles at 36 via -PmobileCompileSdk=36
    // (scripts/publish-engine-local.sh --mobile). The gate and :app keep 37.1.
    if (providers.gradleProperty("mobileCompileSdk").isPresent) {
      compileSdk = providers.gradleProperty("mobileCompileSdk").get().toInt()
    } else {
      compileSdk { version = release(37) { minorApiLevel = 1 } }
    }
    minSdk = 24
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    getByName("commonMain").dependencies {
      // Room's annotations are themselves multiplatform (room-common publishes iOS, wasm,
      // JS and JVM variants), so the domain model keeps its @Entity metadata *and* lives in
      // commonMain. The app still owns the database; this module only owns the shape.
      api(libs.androidx.room.common)
      // The engine's date maths. `api` because TimeZone appears in public signatures that
      // :app and the future planning service both call.
      api(libs.kotlinx.datetime)
      // The frozen wire contract. Mapping (contract wire ⇄ engine) lives here in commonMain
      // because both the server and the mobile preview run it: the engine is the authority
      // and this file is the only place the two meet. `api` so the contract types surface in
      // the PlannerCore framework alongside EnginePreview.
      api(project(":planning-contract"))
    }

    val jvmShared = create("jvmShared") {
      dependsOn(getByName("commonMain"))
    }
    getByName("jvmMain").dependsOn(jvmShared)
    getByName("androidMain").dependsOn(jvmShared)

    getByName("jvmTest").dependencies { implementation(libs.junit) }
  }
}

// CommonMainPurityTest reads the source tree at runtime rather than through the compiler
// (see its doc comment for why). Gradle cannot infer that, so moving a file between source
// sets left the task UP-TO-DATE and the guard reported stale numbers from the previous
// layout. Declaring both trees as inputs is what makes the check trustworthy.
tasks.named("jvmTest") {
  inputs.dir(layout.projectDirectory.dir("src/commonMain/kotlin")).withPathSensitivity(
    org.gradle.api.tasks.PathSensitivity.RELATIVE,
  )
  inputs.dir(layout.projectDirectory.dir("src/jvmShared/kotlin")).withPathSensitivity(
    org.gradle.api.tasks.PathSensitivity.RELATIVE,
  )
}
