package com.example.server.google

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * The plan calendar, proven against a local HTTP server standing in for Google: no
 * credentials, the client's `baseUrl` points at the test's own server. What Google
 * itself accepts is the runbook's real-account leg.
 */
class PlanCalendarTest {
  private lateinit var server: HttpServer
  private lateinit var baseUrl: String
  private val calls = mutableListOf<String>() // "METHOD path", in order
  private val bodies = mutableListOf<String>()
  private lateinit var responder: (String) -> Pair<Int, String>

  @Before
  fun bootServer() {
    calls.clear()
    bodies.clear()
    responder = { 200 to "{}" }
    server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/calendar/") { exchange ->
      val call = "${exchange.requestMethod} ${exchange.requestURI}"
      calls.add(call)
      bodies.add(exchange.requestBody.readBytes().decodeToString())
      val (code, body) = responder(call)
      val bytes = body.toByteArray()
      // 204 and friends carry no body; -1 tells the JDK server so.
      exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
    }
    server.start()
    baseUrl = "http://localhost:${server.address.port}"
  }

  @After
  fun stopServer() {
    server.stop(0)
  }

  private val hour = 3_600_000L
  private fun event(block: String, startHour: Long, title: String = "Write the proposal") =
    PlannedEvent(PlanCalendarDiff.eventId(block, startHour * hour, (startHour + 1) * hour, title), title, startHour * hour, (startHour + 1) * hour)

  @Test
  fun `an event id is stable for a block and changes when it moves or is renamed`() {
    val id = PlanCalendarDiff.eventId("b1", 1, 2, "Write")
    assertEquals(id, PlanCalendarDiff.eventId("b1", 1, 2, "Write"))
    assertNotEquals(id, PlanCalendarDiff.eventId("b1", 1, 3, "Write"))
    assertNotEquals(id, PlanCalendarDiff.eventId("b1", 1, 2, "Rewrite"))
    // Google's rule for client-chosen ids: base32hex characters, 5 to 1024 of them.
    assertTrue(Regex("[a-v0-9]{5,1024}").matches(id))
  }

  @Test
  fun `only what differs is written, and writing the same plan twice writes nothing`() {
    val kept = event("b1", 9)
    val added = event("b2", 11)
    val changes = PlanCalendarDiff.changes(listOf(kept, added), setOf(kept.id, "db-stale"))

    assertEquals(listOf(added), changes.insert)
    assertEquals(listOf("db-stale"), changes.delete)
    assertEquals(PlanCalendarDiff.Changes(emptyList(), emptyList()), PlanCalendarDiff.changes(listOf(kept, added), setOf(kept.id, added.id)))
  }

  @Test
  fun `a first write creates the calendar, then inserts the plan`() {
    responder = { call ->
      when {
        call == "POST /calendar/v3/calendars" -> 200 to """{"id":"cal-1"}"""
        call.startsWith("GET ") -> 200 to """{"items":[]}"""
        else -> 200 to "{}"
      }
    }
    val planned = event("b1", 9)

    val id = PlanCalendarSync.run(GooglePlanCalendar("token", baseUrl), null, "Asia/Kolkata", listOf(planned), 0, 100 * hour)

    assertEquals("cal-1", id)
    assertEquals("POST /calendar/v3/calendars", calls[0])
    assertTrue(bodies[0].contains("\"timeZone\":\"Asia/Kolkata\""))
    assertTrue(calls[1].startsWith("GET /calendar/v3/calendars/cal-1/events?timeMin=1970-01-01T00:00:00Z"))
    assertEquals("POST /calendar/v3/calendars/cal-1/events", calls[2])
    assertTrue(bodies[2].contains("\"id\":\"${planned.id}\""))
    assertTrue(bodies[2].contains("\"dateTime\":\"1970-01-01T09:00:00Z\""))
  }

  @Test
  fun `events are read across pages and stale ones are removed, gone already counting as removed`() {
    val kept = event("b1", 9)
    responder = { call ->
      when {
        call.startsWith("GET ") && call.contains("pageToken=p2") -> 200 to """{"items":[{"id":"db-old"}]}"""
        call.startsWith("GET ") -> 200 to """{"items":[{"id":"${kept.id}"}],"nextPageToken":"p2"}"""
        call.startsWith("DELETE ") -> 410 to ""
        else -> 200 to "{}"
      }
    }

    PlanCalendarSync.run(GooglePlanCalendar("token", baseUrl), "cal-1", "UTC", listOf(kept), 0, 100 * hour)

    assertEquals(listOf("GET", "GET", "DELETE"), calls.map { it.substringBefore(' ') })
    assertEquals("DELETE /calendar/v3/calendars/cal-1/events/db-old", calls[2])
  }

  @Test
  fun `an id Google still remembers as deleted is written over instead of refused`() {
    val back = event("b1", 9)
    responder = { call ->
      when {
        call.startsWith("GET ") -> 200 to """{"items":[]}"""
        call.startsWith("POST ") -> 409 to """{"error":"duplicate"}"""
        else -> 200 to "{}"
      }
    }

    PlanCalendarSync.run(GooglePlanCalendar("token", baseUrl), "cal-1", "UTC", listOf(back), 0, 100 * hour)

    assertEquals("PUT /calendar/v3/calendars/cal-1/events/${back.id}", calls.last())
    assertTrue(bodies.last().contains("\"status\":\"confirmed\""))
  }

  @Test
  fun `a calendar the reader deleted is made again`() {
    responder = { call ->
      when {
        call.startsWith("GET /calendar/v3/calendars/gone/") -> 404 to "{}"
        call == "POST /calendar/v3/calendars" -> 200 to """{"id":"cal-2"}"""
        else -> 200 to "{}"
      }
    }

    val id = PlanCalendarSync.run(GooglePlanCalendar("token", baseUrl), "gone", "UTC", listOf(event("b1", 9)), 0, 100 * hour)

    assertEquals("cal-2", id)
    assertEquals("POST /calendar/v3/calendars/cal-2/events", calls.last())
    assertNull(GooglePlanCalendar("token", baseUrl).eventIds("gone", 0, hour))
  }

  @Test
  fun `a refused token surfaces as 401 so the caller can refresh once`() {
    responder = { 401 to """{"error":"invalid_token"}""" }

    val failure =
      assertThrows(PlanCalendarFailure::class.java) {
        PlanCalendarSync.run(GooglePlanCalendar("stale", baseUrl), "cal-1", "UTC", emptyList(), 0, hour)
      }

    assertEquals(401, failure.statusCode)
  }
}
