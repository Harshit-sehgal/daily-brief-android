package com.example.data.database

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the two legacy identities against the JVM implementation's exact output.
 *
 * `nameKey` is stored on board, column and work-schedule rows and compared on Undo;
 * `stableId` is how the v5 catalog import derives IDs that survive every later migration.
 * A reimplementation that differs for one input breaks existing installs silently, so the
 * pairs below were captured from `java.text.Normalizer`/`UUID.nameUUIDFromBytes` before the
 * `expect`/`actual` split and must hold against it afterwards.
 */
class LegacyNameKeysFixtureTest {

  @Test
  fun `nameKey pairs match the captured JVM output`() {
    assertEquals("work", nameKey("Work"))
    assertEquals("work", nameKey(" work "))
    assertEquals("work", nameKey("Ｗｏｒｋ"))
    assertEquals("café", nameKey("Café"))
    assertEquals("café", nameKey("Cafe\u0301"))
    assertEquals("straße", nameKey("Straße"))
    assertEquals("😀emoji", nameKey("😀emoji"))
    assertEquals("mixed case 123", nameKey("Mixed CASE 123"))
    assertEquals("café café", nameKey("café cafe\u0301"))
  }

  @Test
  fun `trim semantics match JVM Character isWhitespace, not Unicode whitespace`() {
    // The JVM actual trims with Character.isWhitespace: U+0085, U+00A0, U+2007 and U+202F
    // survive (their NFKC decomposes them to a plain space, so the expected values show the
    // space), while U+1680, U+2002 and U+3000 are trimmed. The iOS actual shares legacyTrim,
    // so these pins are the byte-compatibility proof for stored name keys.
    assertEquals(" work ", nameKey("\u00a0work\u00a0"))
    assertEquals("\u0085work\u0085", nameKey("\u0085work\u0085"))
    assertEquals(" work ", nameKey("\u2007work\u2007"))
    assertEquals(" work ", nameKey("\u202fwork\u202f"))
    assertEquals("work", nameKey("\twork\n"))
    assertEquals("work", nameKey("\u001cwork\u001d"))
    assertEquals("work", nameKey("\u1680work\u1680"))
    assertEquals("work", nameKey("\u2002work\u2002"))
    assertEquals("work", nameKey("\u3000work\u3000"))
  }

  @Test
  fun `stableId pairs match the captured JVM output`() {
    assertEquals("2c1743a3-9130-3fbf-b67d-f8e4f069f9f9", stableId("alpha"))
    assertEquals("d5237a63-e184-3adb-8f97-149839950bc0", stableId("alpha "))
    assertEquals("f6e8039a-deae-3776-8983-3aca9007e5a8", stableId("Ｗｏｒｋ"))
    assertEquals("4655bd14-eebf-3f44-8e5b-33d6851dbbd0", stableId("Café"))
    assertEquals("04a71aee-847c-3532-8083-e66e54095ec8", stableId("Cafe\u0301"))
    assertEquals("2a02eac3-9d71-3a70-acf3-7579185927b6", stableId("😀"))
    assertEquals("a763ca07-3cfd-31fc-a8a1-4f2f0cc84591", stableId("Straße"))
    assertEquals("42b8a1bd-d52e-3f34-a8e7-50ae2e6cdcef", stableId("task_1"))
  }
}