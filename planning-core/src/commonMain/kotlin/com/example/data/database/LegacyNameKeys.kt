package com.example.data.database

/**
 * The two stable legacy identities shared by the journal codecs and the catalog import.
 *
 * Both are pure and deterministic — `nameKey` must never change, because it is stored on
 * board/column/work-schedule rows and compared on Undo, and `stableId` is how the v5 catalog
 * import derives IDs that survive every later migration. They live in `planning-core` so the
 * mutation journal can validate them wherever the engine runs. Do not reimplement NFKC here:
 * the JVM actual keeps `java.text.Normalizer`, iOS will use
 * `precomposedStringWithCompatibilityMapping`, and the fixture test pins both against the
 * exact outputs this module shipped before the split.
 */
expect fun nameKey(value: String): String

expect fun stableId(seed: String): String