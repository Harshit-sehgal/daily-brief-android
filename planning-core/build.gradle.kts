plugins {
  // No version: the Kotlin plugin is already pinned on the root buildscript classpath,
  // and asking for a version again fails the compatibility check.
  id("org.jetbrains.kotlin.multiplatform")
  // AGP 9 refuses `com.android.library` alongside the multiplatform plugin; this is the
  // replacement it points at.
  alias(libs.plugins.android.kotlin.multiplatform.library)
}

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

  androidLibrary {
    namespace = "com.example.planning"
    // Must match the app exactly. This toolchain has platform 37.1 installed and nothing
    // else; a bare `compileSdk = 37` asks for 37.0, which sends Gradle off to download a
    // platform that is not in the pinned SDK — the build then stalls on the network with
    // no visible progress at all.
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    minSdk = 24
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    getByName("commonMain").dependencies {
      // Room's annotations are themselves multiplatform (room-common publishes iOS, wasm,
      // JS and JVM variants), so the domain model keeps its @Entity metadata *and* lives in
      // commonMain. The app still owns the database; this module only owns the shape.
      api(libs.androidx.room.common)
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
