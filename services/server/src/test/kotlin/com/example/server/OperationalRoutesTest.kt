package com.example.server

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalRoutesTest {
  private val config =
    Config(
      databaseUrl = "jdbc:postgresql://localhost/dailybrief",
      databaseUser = "postgres",
      databasePassword = "postgres",
      sessionSecret = "test-session-secret-with-more-than-32-bytes",
    )

  @Test
  fun `liveness is public and readiness fails closed without an initialized database`() = testApplication {
    application { module(config) }

    assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
    assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health/ready").status)
  }

  @Test
  fun `real mode refuses OAuth start when provider credentials are absent`() = testApplication {
    application { module(config) }

    assertEquals(HttpStatusCode.NotImplemented, client.get("/v1/auth/start").status)
  }

  @Test
  fun `browser logout expires the HttpOnly cookie`() = testApplication {
    application { module(config) }

    val response = client.post("/v1/auth/logout")

    assertEquals(HttpStatusCode.OK, response.status)
    val cookie = response.headers["Set-Cookie"]
    check(cookie != null)
    assertTrue(cookie.startsWith("dailybrief_session=; Max-Age=0"))
    assertTrue(cookie.contains("HttpOnly"))
    assertTrue(cookie.contains("SameSite=Lax"))
  }
}
