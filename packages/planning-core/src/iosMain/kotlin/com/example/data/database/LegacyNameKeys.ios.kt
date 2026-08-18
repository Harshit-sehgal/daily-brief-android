package com.example.data.database

import platform.Foundation.NSString
import platform.Foundation.NSLocale
import platform.Foundation.lowercaseStringWithLocale
import platform.Foundation.precomposedStringWithCompatibilityMapping

/**
 * The iOS actual, exactly as the commonMain doc comment always said it would be:
 * `precomposedStringWithCompatibilityMapping` is Unicode NFKC (compatibility decomposition
 * followed by canonical composition) and `en_US_POSIX` lowercase applies the default
 * Unicode case mapping — the same semantics as the JVM's `Normalizer(NFKC)` plus
 * `Locale.ROOT`, so the pinned fixture outputs hold. Trim and the name-based UUID are
 * shared common code ([legacyTrim], [legacyStableId]), pinned on the JVM where the tests
 * can run; this file is only the two Apple-provided mappings, with nothing to transcribe.
 */
actual fun nameKey(value: String): String {
  val ns = (legacyTrim(value) as NSString).precomposedStringWithCompatibilityMapping
  return ns.lowercaseStringWithLocale(NSLocale(localeIdentifier = "en_US_POSIX"))
}

actual fun stableId(seed: String): String = legacyStableId(seed)