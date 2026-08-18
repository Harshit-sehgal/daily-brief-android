package com.example.server

import com.example.server.key.SigV4
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins SigV4 to the AWS-documented example (GET iam, 20150830, secret
 * wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY): the derived signing key and the final
 * signature are both documented values, so a wrong derivation chain or a wrong canonical
 * request shape fails this test rather than producing requests AWS would reject.
 */
class SigV4Test {
  private val secret = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"

  @Test
  fun `derives the documented signing key`() {
    assertEquals(
      "c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9",
      SigV4.bytesToHex(SigV4.signingKey(secret, "20150830", "us-east-1", "iam")),
    )
  }

  @Test
  fun `signs the documented example request`() {
    val amzDate = "20150830T123600Z"
    val canonical =
      SigV4.canonicalRequest(
        method = "GET",
        canonicalUri = "/",
        canonicalQuery = "Action=ListUsers&Version=2010-05-08",
        canonicalHeaders =
          listOf(
            "content-type" to "application/x-www-form-urlencoded; charset=utf-8",
            "host" to "iam.amazonaws.com",
            "x-amz-date" to amzDate,
          ),
        hashedPayload = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      )
    val scope = "20150830/us-east-1/iam/aws4_request"
    val signature =
      SigV4.hmacSha256Hex(
        SigV4.signingKey(secret, "20150830", "us-east-1", "iam"),
        SigV4.stringToSign(amzDate, scope, SigV4.sha256Hex(canonical.toByteArray(Charsets.UTF_8))),
      )
    assertEquals("5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7", signature)
  }

  @Test
  fun `canonical request hashes to the documented value`() {
    val canonical =
      SigV4.canonicalRequest(
        method = "GET",
        canonicalUri = "/",
        canonicalQuery = "Action=ListUsers&Version=2010-05-08",
        canonicalHeaders =
          listOf(
            "content-type" to "application/x-www-form-urlencoded; charset=utf-8",
            "host" to "iam.amazonaws.com",
            "x-amz-date" to "20150830T123600Z",
          ),
        hashedPayload = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      )
    assertEquals(
      "f536975d06c0309214f805bb90ccff089219ecd68b2577efef23edd43b7e1a59",
      SigV4.sha256Hex(canonical.toByteArray(Charsets.UTF_8)),
    )
  }
}