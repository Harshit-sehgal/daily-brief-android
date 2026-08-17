package com.example.data.database

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

actual fun nameKey(value: String): String =
  Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

actual fun stableId(seed: String): String =
  UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()