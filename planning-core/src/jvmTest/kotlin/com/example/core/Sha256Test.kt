package com.example.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** FIPS 180-4 SHA-256 checked against the NIST-announced test vectors. */
class Sha256Test {

  private fun hex(s: String): ByteArray {
    require(s.length % 2 == 0)
    return ByteArray(s.length / 2) { i ->
      ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
    }
  }

  @Test
  fun `empty input matches the NIST vector`() {
    assertArrayEquals(
      hex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
      Sha256.hash(ByteArray(0)),
    )
  }

  @Test
  fun `abc matches the NIST vector`() {
    assertArrayEquals(
      hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
      Sha256.hash("abc".encodeToByteArray()),
    )
  }

  @Test
  fun `two-block input matches the NIST vector`() {
    assertArrayEquals(
      hex("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"),
      Sha256.hash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
    )
  }

  @Test
  fun `million a matches the NIST vector`() {
    assertArrayEquals(
      hex("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"),
      Sha256.hash(ByteArray(1_000_000) { 'a'.code.toByte() }),
    )
  }
}