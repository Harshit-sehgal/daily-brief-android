package com.example.data.database

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

actual fun nameKey(value: String): String =
  Normalizer.normalize(legacyTrim(value), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

actual fun stableId(seed: String): String = legacyStableId(seed)