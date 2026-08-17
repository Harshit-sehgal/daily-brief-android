package com.example

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `commonMain` is the portable half of the engine, and this is what actually enforces it.
 *
 * The obvious mechanism — let `compileCommonMainKotlinMetadata` reject `java.util.Calendar` —
 * does not work here, and it fails open rather than closed, which is worse than not having it.
 * Kotlin only produces a metadata compilation once a target needs one; with `jvm` and
 * `androidTarget` both being JVM-family, the task is registered and then SKIPPED, so a file
 * importing `java.util.Calendar` compiles happily in `commonMain` and nothing says a word.
 * Verified by planting one: build still green.
 *
 * Earning the compiler check means adding a non-JVM target, and on Linux that is a Kotlin/Native
 * toolchain of about a gigabyte that this pinned, offline toolchain does not carry. So the rule is
 * enforced the way `UiConsistencyTest` enforces the radius scale: by reading the source. When an
 * iOS or wasm target is added later, the compiler takes over and this test becomes a fast
 * duplicate rather than the only guard.
 *
 * A file in `commonMain` may use the Kotlin common stdlib and nothing else. Anything needing JVM
 * APIs belongs in `jvmShared`, which both the server target and Android depend on.
 */
class CommonMainPurityTest {
  private val commonMain: File by lazy {
    // Gradle runs tests with the module directory as the working directory, but do not rely on it.
    val candidates =
      listOf(
        File("src/commonMain/kotlin"),
        File("planning-core/src/commonMain/kotlin"),
        File("../planning-core/src/commonMain/kotlin"),
      )
    candidates.firstOrNull { it.isDirectory }
      ?: error("commonMain sources not found from ${File(".").absolutePath}")
  }

  private fun commonMainSources(): List<File> =
    commonMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

  /** `java.lang` is imported implicitly, so `Math.addExact` needs catching by name, not by import. */
  private val forbidden =
    listOf(
      Regex("""^import\s+java\.""") to "java.* is not in the common stdlib",
      Regex("""^import\s+javax\.""") to "javax.* is not in the common stdlib",
      Regex("""^import\s+android\.""") to "commonMain cannot depend on the Android platform",
      // androidx.room is deliberately allowed: room-common publishes iOS, wasm, JS and JVM
      // variants, so the domain model keeps its @Entity metadata without leaving commonMain.
      // Every other androidx artifact is Android-only until shown otherwise.
      Regex("""^import\s+androidx\.(?!room\.)""") to
        "commonMain cannot depend on androidx (androidx.room is the one exception — it is multiplatform)",
      Regex("""^import\s+kotlinx\.coroutines""") to
        "the engine is synchronous on purpose; concurrency belongs to the caller",
      Regex("""\bMath\.\w+""") to
        "java.lang.Math is implicit on the JVM only — use kotlin.math, a.mod(b), or guarded arithmetic",
    )

  @Test
  fun commonMainUsesOnlyTheCommonStandardLibrary() {
    val sources = commonMainSources()
    assertTrue("No commonMain sources were scanned, so this test proves nothing", sources.isNotEmpty())

    val violations = mutableListOf<String>()
    sources.forEach { file ->
      file.readLines().forEachIndexed { index, line ->
        // Skip comment lines so prose about java.time does not trip the scan.
        val code = line.substringBefore("//").trim()
        if (code.isEmpty() || code.startsWith("*")) return@forEachIndexed
        forbidden.forEach { (pattern, why) ->
          if (pattern.containsMatchIn(code)) {
            violations += "${file.name}:${index + 1}  $code    <- $why"
          }
        }
      }
    }

    assertTrue(
      buildString {
        appendLine("commonMain must stay portable, but ${violations.size} line(s) reach outside it:")
        violations.forEach { appendLine("  $it") }
        appendLine()
        appendLine("Move the file to src/jvmShared/kotlin, or replace the JVM API with a common one.")
      },
      violations.isEmpty(),
    )
  }

  /**
   * The point of the split is that it moves. If this number falls, something was pushed back to
   * `jvmShared` and the reason belongs in `docs/saas/05-kmp-portability-audit.md`.
   */
  @Test
  fun thePortableShareOfTheEngineIsRecorded() {
    val commonLines = commonMainSources().sumOf { it.readLines().size }
    val jvmShared =
      commonMain.parentFile.parentFile.resolve("jvmShared/kotlin")
        .walkTopDown().filter { it.isFile && it.extension == "kt" }.sumOf { it.readLines().size }
    val share = 100 * commonLines / (commonLines + jvmShared)

    println("planning-core: $commonLines lines in commonMain, $jvmShared in jvmShared ($share% portable)")
    assertTrue(
      "commonMain has shrunk to $share% — a file moved back to jvmShared without the audit saying why",
      share >= 20,
    )
  }
}
