package com.example

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `commonMain` is the portable half of the engine. The compiler now enforces it — the
 * `linuxX64` target (WP-7) made `compileCommonMainKotlinMetadata` real, and a `java.util`
 * import there fails the build, verified by planting one — so this test is a fast duplicate
 * with a better error message, not the only guard. Keep it green: the metadata compile is
 * part of `scripts/verify.sh`, but a source-scan failure here points at the exact line.
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
      // The linuxX64 compile caught these after the import scan sailed past them: JVM-only
      // stdlib extensions and annotations are not imports, so the scan names them explicitly.
      // (Clock.System is fine and must not match — the lookbehind excludes it.)
      Regex("""\.toSortedMap\(|\.toSortedSet\(""") to
        "JVM-only stdlib extensions — sort with toList().sortedBy { it.first } and friends",
      Regex("""(?<!\.)\bSystem\.(currentTimeMillis|nanoTime|getProperty|getenv)""") to
        "java.lang.System is implicit on the JVM only — use kotlin.time.Clock.System",
      Regex("""@JvmOverloads""") to
        "@JvmOverloads is JVM-only; default arguments already cover Kotlin callers",
      Regex("""\.format\(""") to
        "String.format is JVM-only — build the string with toString(16).padStart or similar",
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
