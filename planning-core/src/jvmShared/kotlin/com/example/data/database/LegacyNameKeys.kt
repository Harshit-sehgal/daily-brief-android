package com.example.data.database

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * The two stable legacy identities shared by the journal codecs and the catalog import.
 *
 * Both are pure and deterministic — `nameKey` must never change, because it is stored on
 * board/column/work-schedule rows and compared on Undo, and `stableId` is how the v5 catalog
 * import derives IDs that survive every later migration. They live in `planning-core` so the
 * mutation journal can validate them wherever the engine runs; they stay in `jvmShared` because
 * NFKC normalization and name-based UUIDs are JVM APIs.
 */
object LegacyNameKeys {
  fun nameKey(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

  fun stableId(seed: String): String =
    UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()
}