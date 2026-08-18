package com.example.core

import com.example.data.model.BriefingEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the byte layout of [ScheduleAnalysis.signature] for a fixed event list.
 *
 * The signature is the daily-brief cache key, and the layout must be byte-identical to the
 * `java.security.MessageDigest` implementation it replaced — the fixture value was captured from
 * that implementation before the port. A change here means a deliberate cache miss for every user,
 * so it gets a test, not a hope.
 */
class SignatureFixtureTest {

  private val fixture: List<BriefingEvent>
    get() =
      listOf(
        BriefingEvent(
          id = "zebra",
          title = "Sign the engagement letter",
          startTime = 1_772_000_000_000L,
          endTime = 1_772_003_600_000L,
          source = "notion",
          description = null,
          isDeadline = true,
          isUrgent = false,
        ),
        BriefingEvent(
          id = "alpha",
          title = "Stand-up with Côte d'Azur team",
          startTime = 1_771_900_000_000L,
          endTime = 1_771_903_000_000L,
          source = "device",
          description = null,
          isDeadline = false,
          isUrgent = true,
          isAllDay = true,
          location = "Zoom",
        ),
        BriefingEvent(
          id = "mid",
          title = "  ",
          startTime = 1_771_950_000_000L,
          endTime = 1_771_960_000_000L,
          source = "notion",
          description = null,
          isDeadline = false,
          isUrgent = false,
          location = "  ",
        ),
      )

  @Test
  fun `signature is byte-identical to the JVM implementation it replaced`() {
    assertEquals(
      "582d3d0d0db0425c2ed63c8da635c70cc2ffdb6fb307a9797a740de29e014760",
      ScheduleAnalysis.signature(fixture),
    )
  }

  @Test
  fun `input order does not change the signature`() {
    assertEquals(
      ScheduleAnalysis.signature(fixture),
      ScheduleAnalysis.signature(fixture.reversed()),
    )
  }
}