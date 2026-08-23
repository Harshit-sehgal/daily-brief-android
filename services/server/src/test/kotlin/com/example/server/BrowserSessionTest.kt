package com.example.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSessionTest {
  @Test
  fun `local browser cookie is HttpOnly and does not require Secure`() {
    val cookie = browserSessionCookie("workspace.123.signature", secure = false)

    assertTrue(cookie.startsWith("dailybrief_session=workspace.123.signature"))
    assertTrue(cookie.contains("Max-Age=604800"))
    assertTrue(cookie.contains("Path=/"))
    assertTrue(cookie.contains("HttpOnly"))
    assertTrue(cookie.contains("SameSite=Lax"))
    assertFalse(cookie.contains("Secure"))
  }

  @Test
  fun `production browser cookie is cross-subdomain safe and secure`() {
    val cookie = browserSessionCookie("token", secure = true)

    assertTrue(cookie.contains("HttpOnly"))
    assertTrue(cookie.contains("SameSite=None"))
    assertTrue(cookie.contains("Secure"))
  }

  @Test
  fun `clearing cookie expires the same browser scope`() {
    val cookie = clearBrowserSessionCookie(secure = true)

    assertTrue(cookie.startsWith("dailybrief_session=; Max-Age=0; Path=/"))
    assertTrue(cookie.contains("HttpOnly"))
    assertTrue(cookie.contains("SameSite=None"))
    assertTrue(cookie.contains("Secure"))
  }
}
