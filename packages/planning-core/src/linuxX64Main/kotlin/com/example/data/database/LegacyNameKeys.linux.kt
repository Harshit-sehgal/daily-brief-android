package com.example.data.database

/**
 * The linuxX64 target exists to make `compileCommonMainKotlinMetadata` real, not to run the
 * planner. Legacy identity on this target is therefore deliberately absent rather than
 * approximated: a NFKC reimplementation that differs by one character from `java.text.Normalizer`
 * would corrupt stored name keys, and this module's fixture test pins the JVM outputs the app
 * already shipped. The real deployment surfaces — Android and the JVM server — both resolve the
 * jvmShared actual. If a native target ever runs the planner, port
 * `precomposedStringWithCompatibilityMapping` here (per the LegacyNameKeys doc comment) with the
 * fixture test green before and after.
 */
actual fun nameKey(value: String): String =
  throw UnsupportedOperationException(
    "nameKey is only defined on JVM targets; linuxX64 exists to enforce commonMain purity.",
  )

actual fun stableId(seed: String): String =
  throw UnsupportedOperationException(
    "stableId is only defined on JVM targets; linuxX64 exists to enforce commonMain purity.",
  )
