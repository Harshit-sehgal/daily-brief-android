package com.example.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.Locale
import org.junit.Test

/**
 * The switcher's ranking is a promise about what appears first when someone
 * types three letters and presses Go — so the order it documents is asserted
 * here rather than left to whoever reads the scoring constants.
 */
class FuzzyTest {

  private fun scoreOf(query: String, target: String): Int =
    requireNotNull(Fuzzy.score(query, target)) { "\"$query\" should match \"$target\"" }

  @Test
  fun `a prefix beats a word start beats a substring beats a scattered match`() {
    val prefix = scoreOf("rev", "Review the deck")
    val wordStart = scoreOf("rev", "Design review")
    val substring = scoreOf("rev", "Prevention plan")
    val subsequence = scoreOf("rev", "Roll over every visit")

    assertTrue("prefix ($prefix) must beat word start ($wordStart)", prefix > wordStart)
    assertTrue("word start ($wordStart) must beat substring ($substring)", wordStart > substring)
    assertTrue(
      "substring ($substring) must beat a scattered match ($subsequence)",
      substring > subsequence,
    )
  }

  @Test
  fun `ties break toward the shorter target`() {
    // The example the ranking documents for itself.
    val ranked = Fuzzy.rank("wee", listOf("Weekly planning review", "Week")) { it }

    assertEquals(listOf("Week", "Weekly planning review"), ranked)
  }

  @Test
  fun `matching is case and padding insensitive`() {
    assertEquals(Fuzzy.score("standup", "Standup"), Fuzzy.score("  STANDUP  ", "standup"))
    assertNotNull(Fuzzy.score("STANDUP", "Team standup"))
  }

  @Test
  fun `a query longer than the target never matches`() {
    assertNull(Fuzzy.score("retrospective", "Retro"))
  }

  @Test
  fun `characters out of order do not match`() {
    assertNull(Fuzzy.score("pdx", "Design review"))
    // ...but in order, however scattered, they do.
    assertNotNull(Fuzzy.score("dgn", "Design review"))
  }

  @Test
  fun `an empty query keeps everything in the order it was given`() {
    val items = listOf("Board", "Today", "Week")

    assertEquals(items, Fuzzy.rank("", items) { it })
    assertEquals(items, Fuzzy.rank("   ", items) { it })
  }

  @Test
  fun `ranking drops what cannot match at all`() {
    val ranked = Fuzzy.rank("zzz", listOf("Board", "Today", "Week")) { it }

    assertEquals(emptyList<String>(), ranked)
  }

  @Test
  fun `a word start after punctuation counts as one`() {
    // Palette subtitles join fields with separators, so boundaries are not just spaces.
    val afterSeparator = scoreOf("cli", "Design review · Client check-in")
    val mereSubstring = scoreOf("cli", "Recliner delivery")

    assertTrue(
      "a match starting a word ($afterSeparator) must beat one inside a word ($mereSubstring)",
      afterSeparator > mereSubstring,
    )
  }

  @Test
  fun `the closest scattered match ranks above the most spread out one`() {
    val tight = scoreOf("dr", "Draft")
    val spread = scoreOf("dr", "Deliver the quarterly report")

    assertTrue("tight ($tight) must beat spread out ($spread)", tight > spread)
  }
  /**
   * Matching must not depend on the device's language.
   *
   * Turkish is the case that catches this: `"I"` folds to a dotless `"ı"` under its rules, so a
   * locale-sensitive fold would stop "istanbul" from finding "Istanbul" — broken for one locale and
   * unreproducible for everyone else. Kotlin's `lowercase()` is locale-invariant, unlike Java's
   * `toLowerCase()`, and this runs under Turkish to keep it that way: switching any of these folds
   * to `lowercase(Locale.getDefault())` fails here rather than in one country.
   */
  @Test
  fun `matching does not depend on the device language`() {
    val original = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("tr"))
      assertNotNull(Fuzzy.score("istanbul", "Istanbul planning"))
      assertNotNull(Fuzzy.score("ISTANBUL", "istanbul planning"))
      assertNotNull(Fuzzy.score("invoice", "INVOICE review"))
      // And the ordinary rules still hold under that locale.
      val prefix = requireNotNull(Fuzzy.score("inv", "Invoice review"))
      val scattered = requireNotNull(Fuzzy.score("inv", "Interesting novel view"))
      assertTrue("a prefix must still outrank a scattered match", prefix > scattered)
    } finally {
      Locale.setDefault(original)
    }
  }
}
