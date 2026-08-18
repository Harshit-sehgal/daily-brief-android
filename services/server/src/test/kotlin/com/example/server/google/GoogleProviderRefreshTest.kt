package com.example.server.google

import com.example.server.Config
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * WP-14 hardening, proven against a local HTTP server: an expired access token is
 * refreshed exactly once, the fresh pair is persisted through the callback, and the fetch
 * is retried once. No Google credentials — the provider's [GoogleCalendarProvider.baseUrl]
 * points at the test's own server.
 */
class GoogleProviderRefreshTest {
  private lateinit var server: HttpServer
  private lateinit var baseUrl: String
  private val requests = mutableListOf<String>() // Authorization headers, in call order

  private val config =
    Config(
      databaseUrl = "jdbc:postgresql://localhost:1/none",
      databaseUser = "postgres",
      databasePassword = "postgres",
      sessionSecret = "test-secret",
    )

  /** Responds per call: `authHeader -> (status, body)`. */
  private lateinit var responder: (String) -> Pair<Int, String>

  @Before
  fun bootServer() {
    requests.clear()
    responder = { 200 to "{\"items\":[]}" }
    server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/calendar/") { exchange ->
      val auth = exchange.requestHeaders.getFirst("Authorization").orEmpty()
      requests.add(auth)
      val (code, body) = responder(auth)
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(code, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    baseUrl = "http://localhost:${server.address.port}"
  }

  @After
  fun stopServer() {
    server.stop(0)
  }

  @Test
  fun `a 401 refreshes once, persists the fresh pair, and retries with the new token`() {
    responder = { auth ->
      if (auth == "Bearer stale-token") 401 to "{\"error\":\"invalid_grant\"}" else 200 to "{\"items\":[]}"
    }
    val refreshed = AtomicInteger(0)
    val persisted = AtomicInteger(0)
    val provider =
      GoogleCalendarProvider(
        config,
        connectionId = "conn-1",
        accessToken = "stale-token",
        baseUrl = baseUrl,
        refresher = {
          refreshed.incrementAndGet()
          GoogleOAuth.TokenResponse(
            access_token = "fresh-token",
            token_type = "Bearer",
            expires_in = 3600,
            refresh_token = "rotated-refresh",
          )
        },
        onTokensRefreshed = { fresh ->
          persisted.incrementAndGet()
          assertEquals("fresh-token", fresh.access_token)
          assertEquals("rotated-refresh", fresh.refresh_token)
        },
      )
    assertEquals(0, provider.fetchEvents(0L, 1000L).size)
    assertEquals(1, refreshed.get())
    assertEquals(1, persisted.get())
    assertEquals(listOf("Bearer stale-token", "Bearer fresh-token"), requests)
  }

  @Test
  fun `a 403 with no refresher configured propagates the failure`() {
    responder = { 403 to "{\"error\":\"forbidden\"}" }
    val provider =
      GoogleCalendarProvider(config, connectionId = "conn-1", accessToken = "token", baseUrl = baseUrl)
    try {
      provider.fetchEvents(0L, 1000L)
      fail("expected the 403 to throw")
    } catch (expected: IllegalStateException) {
      assertTrue(expected.message!!.contains("403"))
    }
    assertEquals(listOf("Bearer token"), requests)
  }

  @Test
  fun `a refresh that still gets refused propagates without looping`() {
    responder = { 401 to "{\"error\":\"invalid_grant\"}" }
    val refreshed = AtomicInteger(0)
    val provider =
      GoogleCalendarProvider(
        config,
        connectionId = "conn-1",
        accessToken = "stale-token",
        baseUrl = baseUrl,
        refresher = {
          refreshed.incrementAndGet()
          GoogleOAuth.TokenResponse("still-bad", "Bearer", 3600, null)
        },
      )
    try {
      provider.fetchEvents(0L, 1000L)
      fail("expected the retried 401 to throw")
    } catch (expected: IllegalStateException) {
      assertTrue(expected.message!!.contains("after token refresh"))
    }
    assertEquals(1, refreshed.get())
    assertEquals(2, requests.size)
  }

  @Test
  fun `a success needs no refresh at all`() {
    val refreshed = AtomicInteger(0)
    val provider =
      GoogleCalendarProvider(
        config,
        connectionId = "conn-1",
        accessToken = "token",
        baseUrl = baseUrl,
        refresher = {
          refreshed.incrementAndGet()
          GoogleOAuth.TokenResponse("fresh", "Bearer", 3600, null)
        },
      )
    assertEquals(0, provider.fetchEvents(0L, 1000L).size)
    assertEquals(0, refreshed.get())
    assertEquals(listOf("Bearer token"), requests)
  }
}