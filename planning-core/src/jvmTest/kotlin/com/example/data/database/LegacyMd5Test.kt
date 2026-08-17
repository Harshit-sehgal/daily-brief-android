package com.example.data.database

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Pins [LegacyMd5] against the RFC 1321 test suite. The digest is the one piece of stored
 * identity that must be byte-identical on iOS (stableId's MD5), and the RFC vectors are the
 * same ones `java.util.UUID.nameUUIDFromBytes`'s own MD5 must satisfy, so passing here plus
 * the LegacyNameKeysFixtureTest pairs proves the iOS actual's digest without a Mac.
 */
class LegacyMd5Test {

  private fun md5Hex(input: String): String {
    val digest = LegacyMd5.digest(input.encodeToByteArray())
    return digest.joinToString("") { "%02x".format(it) }
  }

  @Test
  fun `RFC 1321 vectors`() {
    assertArrayEquals(hex("d41d8cd98f00b204e9800998ecf8427e"), LegacyMd5.digest(byteArrayOf()))
    assertArrayEquals(hex("0cc175b9c0f1b6a831c399e269772661"), LegacyMd5.digest("a".encodeToByteArray()))
    assertArrayEquals(hex("900150983cd24fb0d6963f7d28e17f72"), LegacyMd5.digest("abc".encodeToByteArray()))
    assertArrayEquals(hex("f96b697d7cb7938d525a2f31aaf161d0"), LegacyMd5.digest("message digest".encodeToByteArray()))
    assertArrayEquals(hex("c3fcd3d76192e4007dfb496cca67e13b"), LegacyMd5.digest("abcdefghijklmnopqrstuvwxyz".encodeToByteArray()))
    assertArrayEquals(hex("d174ab98d277d9f5a5611c2c9f419d9f"), LegacyMd5.digest("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".encodeToByteArray()))
    assertArrayEquals(hex("57edf4a22be3c955ac49da2e2107b67a"), LegacyMd5.digest("12345678901234567890123456789012345678901234567890123456789012345678901234567890".encodeToByteArray()))
  }

  @Test
  fun `empty and single-byte inputs`() {
    assertArrayEquals(hex("93b885adfe0da089cdf634904fd59f71"), LegacyMd5.digest(byteArrayOf(0)))
    assertArrayEquals(hex("d41d8cd98f00b204e9800998ecf8427e"), LegacyMd5.digest(ByteArray(0)))
  }

  private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}