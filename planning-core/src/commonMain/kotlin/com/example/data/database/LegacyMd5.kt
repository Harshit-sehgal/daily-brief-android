package com.example.data.database

/**
 * RFC 1321 MD5, pure Kotlin. Exists only because [legacyStableId] must reproduce Java's
 * `UUID.nameUUIDFromBytes` byte-for-byte on iOS, and Apple's platform libraries expose no
 * MD5 to Kotlin/Native. The K table is the RFC's own constants, hardcoded rather than
 * generated from `sin`, because `sin` is not correctly-rounded IEEE on every platform and
 * a one-ULP difference would change stored IDs. The digest is pinned against the RFC
 * vectors in [com.example.data.database.LegacyMd5Test] and the full identity chain against
 * the captured JVM outputs in [com.example.data.database.LegacyNameKeysFixtureTest].
 */
internal object LegacyMd5 {
  // The canonical RFC 1321 round constants.
  private val K =
    uintArrayOf(
      0xd76aa478u, 0xe8c7b756u, 0x242070dbu, 0xc1bdceeeu,
      0xf57c0fafu, 0x4787c62au, 0xa8304613u, 0xfd469501u,
      0x698098d8u, 0x8b44f7afu, 0xffff5bb1u, 0x895cd7beu,
      0x6b901122u, 0xfd987193u, 0xa679438eu, 0x49b40821u,
      0xf61e2562u, 0xc040b340u, 0x265e5a51u, 0xe9b6c7aau,
      0xd62f105du, 0x02441453u, 0xd8a1e681u, 0xe7d3fbc8u,
      0x21e1cde6u, 0xc33707d6u, 0xf4d50d87u, 0x455a14edu,
      0xa9e3e905u, 0xfcefa3f8u, 0x676f02d9u, 0x8d2a4c8au,
      0xfffa3942u, 0x8771f681u, 0x6d9d6122u, 0xfde5380cu,
      0xa4beea44u, 0x4bdecfa9u, 0xf6bb4b60u, 0xbebfbc70u,
      0x289b7ec6u, 0xeaa127fau, 0xd4ef3085u, 0x04881d05u,
      0xd9d4d039u, 0xe6db99e5u, 0x1fa27cf8u, 0xc4ac5665u,
      0xf4292244u, 0x432aff97u, 0xab9423a7u, 0xfc93a039u,
      0x655b59c3u, 0x8f0ccc92u, 0xffeff47du, 0x85845dd1u,
      0x6fa87e4fu, 0xfe2ce6e0u, 0xa3014314u, 0x4e0811a1u,
      0xf7537e82u, 0xbd3af235u, 0x2ad7d2bbu, 0xeb86d391u,
    )

  private val S =
    intArrayOf(
      7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
      5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
      4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
      6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

  fun digest(message: ByteArray): ByteArray {
    val bitLength = message.size.toLong() * 8
    val padded = ByteArray(((message.size + 8) / 64 + 1) * 64)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    var bits = bitLength
    for (i in 0 until 8) {
      padded[padded.size - 8 + i] = (bits and 0xff).toByte()
      bits = bits ushr 8
    }

    var a0 = 0x67452301u
    var b0 = 0xefcdab89u
    var c0 = 0x98badcfeu
    var d0 = 0x10325476u

    for (offset in padded.indices step 64) {
      val m = UIntArray(16)
      for (i in 0 until 16) {
        val j = offset + i * 4
        m[i] =
          (padded[j].toUInt() and 0xffu) or
            ((padded[j + 1].toUInt() and 0xffu) shl 8) or
            ((padded[j + 2].toUInt() and 0xffu) shl 16) or
            ((padded[j + 3].toUInt() and 0xffu) shl 24)
      }

      var a = a0
      var b = b0
      var c = c0
      var d = d0

      for (i in 0 until 64) {
        val f: UInt
        val g: Int
        when (i / 16) {
          0 -> {
            f = (b and c) or ((b xor 0xffffffffu) and d)
            g = i
          }
          1 -> {
            f = (d and b) or ((d xor 0xffffffffu) and c)
            g = (5 * i + 1) % 16
          }
          2 -> {
            f = b xor c xor d
            g = (3 * i + 5) % 16
          }
          else -> {
            f = c xor (b or (d xor 0xffffffffu))
            g = (7 * i) % 16
          }
        }
        val tmp = d
        d = c
        c = b
        b = b + rotl(a + f + K[i] + m[g], S[i])
        a = tmp
      }

      a0 += a
      b0 += b
      c0 += c
      d0 += d
    }

    return byteArrayOf(
      *a0.littleEndianBytes(),
      *b0.littleEndianBytes(),
      *c0.littleEndianBytes(),
      *d0.littleEndianBytes(),
    )
  }

  private fun rotl(x: UInt, n: Int): UInt = (x shl n) or (x shr (32 - n))

  private fun UInt.littleEndianBytes(): ByteArray =
    byteArrayOf((this and 0xffu).toByte(), ((this shr 8) and 0xffu).toByte(), ((this shr 16) and 0xffu).toByte(), ((this shr 24) and 0xffu).toByte())
}