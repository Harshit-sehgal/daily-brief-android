package com.example.server

/** The browser must not receive the bearer token used by mobile clients. */
internal const val BROWSER_SESSION_COOKIE = "dailybrief_session"
internal const val CSRF_HEADER = "X-DailyBrief-CSRF"

private const val SESSION_MAX_AGE_SECONDS = 7 * 24 * 60 * 60

internal fun browserSessionCookie(token: String, secure: Boolean): String {
  require(token.isNotBlank()) { "browser session token must not be blank" }
  return buildString {
    append(BROWSER_SESSION_COOKIE)
    append('=').append(token)
    append("; Max-Age=").append(SESSION_MAX_AGE_SECONDS)
    append("; Path=/; HttpOnly; SameSite=").append(if (secure) "None" else "Lax")
    if (secure) append("; Secure")
  }
}

internal fun clearBrowserSessionCookie(secure: Boolean): String = buildString {
  append(BROWSER_SESSION_COOKIE)
  append("=; Max-Age=0; Path=/; HttpOnly; SameSite=").append(if (secure) "None" else "Lax")
  if (secure) append("; Secure")
}
