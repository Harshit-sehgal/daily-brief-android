package com.example.data.database

/**
 * The two halves of legacy identity that must be byte-identical on every platform the
 * engine runs, shared so the iOS path is the same code the JVM tests pin:
 *
 * - [legacyTrim] replicates what the JVM actual's `trim()` does — `Character.isWhitespace`
 *   at both ends — because Kotlin's own `String.trim()` is Unicode-aware on native and
 *   would silently differ for U+0085, U+00A0, U+2007 and U+202F (Java does not trim them)
 *   and U+1680, U+2000–U+2006, U+2008–U+200A, U+2028/29, U+205F, U+3000 (Java trims them,
 *   Unicode would not). Each is a stored-name-key corruption for exactly one weird input.
 * - [legacyStableId] replicates `java.util.UUID.nameUUIDFromBytes` exactly: RFC 1321 MD5 of
 *   the UTF-8 seed with no namespace, version-3 and IETF-variant bits set, lower-case hex
 *   with dashes. The digest is [LegacyMd5], pinned against the RFC vectors.
 *
 * Both actuals (jvm, ios) delegate here, and the fixture test pins the outputs on the JVM —
 * the strongest byte-compatibility proof available without a Mac.
 */
internal fun legacyTrim(value: String): String {
  var start = 0
  var end = value.length
  while (start < end && isJavaWhitespace(value[start])) start++
  while (end > start && isJavaWhitespace(value[end - 1])) end--
  return value.substring(start, end)
}

private fun isJavaWhitespace(c: Char): Boolean =
  when (c.code) {
    in 0x09..0x0d -> true
    in 0x1c..0x1f -> true
    0x20 -> true
    0x1680 -> true
    in 0x2000..0x2006 -> true
    in 0x2008..0x200a -> true
    0x2028, 0x2029, 0x205f, 0x3000 -> true
    else -> false
  }

internal fun legacyStableId(seed: String): String {
  val digest = LegacyMd5.digest(seed.encodeToByteArray())
  digest[6] = ((digest[6].toInt() and 0x0f) or 0x30).toByte()
  digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
  return buildString(36) {
    for (i in 0 until 16) {
      if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
      append(HEX[(digest[i].toInt() ushr 4) and 0x0f])
      append(HEX[digest[i].toInt() and 0x0f])
    }
  }
}

private val HEX = "0123456789abcdef".toCharArray()