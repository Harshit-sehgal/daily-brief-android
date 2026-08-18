package com.example.server

import com.example.server.summary.GeminiQuota
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiQuotaTest {
  @Test
  fun `the default limit applies when quota_json says nothing`() {
    assertEquals(GeminiQuota.DEFAULT_SUMMARIES_PER_MONTH, GeminiQuota.limitFrom("{}"))
  }

  @Test
  fun `quota_json sets the boundary`() {
    assertEquals(10, GeminiQuota.limitFrom("""{"summaries_per_month": 10}"""))
  }

  @Test
  fun `malformed quota_json falls back to the default, never to zero`() {
    assertEquals(GeminiQuota.DEFAULT_SUMMARIES_PER_MONTH, GeminiQuota.limitFrom("not json"))
  }

  @Test
  fun `the boundary refuses at the limit, not before it`() {
    assertFalse(GeminiQuota.refuses(2, 3))
    assertTrue(GeminiQuota.refuses(3, 3))
    assertTrue(GeminiQuota.refuses(4, 3))
  }

  @Test
  fun `the month bucket is a UTC month name`() {
    // 2026-08-18T23:30Z is the 18th in UTC; a local-timezone implementation would
    // drift the bucket and the quota with it.
    assertEquals("2026-08", GeminiQuota.periodMonth(java.time.Instant.parse("2026-08-18T23:30:00Z").toEpochMilli()))
  }
}