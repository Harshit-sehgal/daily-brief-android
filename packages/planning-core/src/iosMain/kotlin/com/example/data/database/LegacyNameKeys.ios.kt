package com.example.data.database

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCompatibilityMapping

/**
 * The iOS actual, exactly as the commonMain doc comment always said it would be:
 * `precomposedStringWithCompatibilityMapping` is Unicode NFKC (compatibility decomposition
 * followed by canonical composition), and the stdlib's no-argument `lowercase` is the
 * invariant-locale mapping — the very thing the JVM's `Locale.ROOT` names — so the two
 * actuals agree with the pinned fixture outputs. The Foundation `lowercaseStringWithLocale`
 * call this once used does not compile: the property above hands back a Kotlin `String`,
 * and the NSString extension never applies to it. Trim and the name-based UUID are shared
 * common code ([legacyTrim], [legacyStableId]), pinned on the JVM where the tests can run;
 * this file is only the Apple-provided NFKC mapping plus the stdlib case mapping.
 */
actual fun nameKey(value: String): String {
  val ns = (legacyTrim(value) as NSString).precomposedStringWithCompatibilityMapping
  return ns.lowercase()
}

actual fun stableId(seed: String): String = legacyStableId(seed)
