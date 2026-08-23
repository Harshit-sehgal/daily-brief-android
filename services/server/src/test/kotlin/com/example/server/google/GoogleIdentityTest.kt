package com.example.server.google

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GoogleIdentityTest {
  private lateinit var server: HttpServer
  private lateinit var userInfoUrl: String

  @Before
  fun bootServer() {
    server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/userinfo") { exchange ->
      val body =
        when (exchange.requestHeaders.getFirst("Authorization")) {
          "Bearer access-token" ->
            """{"sub":"google-sub-1","email":"person@example.com","email_verified":true,"name":"Person Example"}"""
          "Bearer unverified-token" ->
            """{"sub":"google-sub-2","email":"person@example.com","email_verified":false,"name":"Unverified"}"""
          else -> "{}"
        }
      val code = if (body == "{}") 401 else 200
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(code, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    userInfoUrl = "http://localhost:${server.address.port}/userinfo"
  }

  @After
  fun stopServer() {
    server.stop(0)
  }

  @Test
  fun `verified userinfo returns the real identity`() {
    val identity = GoogleOAuth.fetchIdentity("access-token", userInfoUrl)

    assertEquals("google-sub-1", identity.sub)
    assertEquals("person@example.com", identity.email)
    assertEquals("Person Example", identity.name)
  }

  @Test
  fun `unverified or unauthorized identity is refused`() {
    assertNull(runCatching { GoogleOAuth.fetchIdentity("unverified-token", userInfoUrl) }.getOrNull())
    assertNull(runCatching { GoogleOAuth.fetchIdentity("wrong-token", userInfoUrl) }.getOrNull())
  }
}
