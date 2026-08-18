package com.example.core

import com.example.contract.ApiVersionNotSupported
import com.example.contract.PlannerApi
import com.example.contract.PlanningRequest
import com.example.contract.PlanningResult
import com.example.contract.TaskWire
import com.example.contract.WorkScheduleWire
import com.example.contract.WorkScheduleWindowWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The mobile native bridge (WP-M3): one request JSON string in, one result JSON string out.
 *
 * The claim these tests pin: a phone preview cannot disagree with a server run, because the
 * server's planAndStore runs `Mapping.propose(Mapping.toEngine(request, now), request)` and
 * EnginePreview runs exactly that computation with `now = request.nowMs`. The output is
 * encoded with the contract's own Json (the same canonical bytes the golden files pin);
 * whitespace is presentation, so a phone parse and a server parse read identical values.
 */
class EnginePreviewTest {
  private val monday = 1_735_689_600_000L // 2025-01-01T00:00:00Z (Wednesday)
  private val hour = 3_600_000L

  private fun request(
    items: List<TaskWire> = listOf(TaskWire(id = "t1", boardId = "b1", title = "Write schema", rank = 0, effortMinutes = 90)),
  ): PlanningRequest =
    PlanningRequest(
      workspaceId = "ws-1",
      rangeStartMs = monday,
      rangeEndMs = monday + 7 * 24 * hour,
      nowMs = monday,
      items = items,
      schedules = weekdays(),
    )

  private fun wire(request: PlanningRequest): String = PlannerApi.json.encodeToString(request)

  private fun weekdays(): List<WorkScheduleWire> =
    listOf(
      WorkScheduleWire(
        id = "s1",
        name = "Weekdays",
        timeZoneId = "UTC",
        isDefault = true,
        rank = 0,
        windows =
          (1..5).map { d ->
            WorkScheduleWindowWire(id = "w$d", dayOfWeek = d, startMinute = 540, endMinute = 1020, rank = d.toLong())
          },
      ),
    )

  @Test
  fun `previewPlan round-trips a request through the wire`() {
    val result = PlannerApi.json.decodeFromString<PlanningResult>(EnginePreview.previewPlan(wire(request())))

    assertFalse(result.proposals.isEmpty())
    assertEquals("t1", result.proposals.first().itemId)
    assertTrue(result.proposals.first().reason.isNotBlank())
    assertTrue(result.proposals.first().startAt >= monday)
  }

  @Test
  fun `preview equals the canonical encoding of what the server's mapping produces`() {
    val preview = EnginePreview.previewPlan(wire(request()))

    // planAndStore in :server runs this exact expression with now = request.nowMs; the result
    // is what the server would emit through the contract's own Json. Any divergence between
    // the two paths would make a phone preview disagree with a server run.
    val serverSide =
      PlannerApi.json.encodeToString(
        Mapping.propose(Mapping.toEngine(request(), request().nowMs), request()),
      )
    assertEquals(serverSide, preview)
  }

  @Test
  fun `preview is deterministic`() {
    val w = wire(request())
    assertEquals(EnginePreview.previewPlan(w), EnginePreview.previewPlan(w))
  }

  @Test
  fun `a newer contract version is refused before any planning`() {
    // The request constructor itself refuses v != VERSION, so build the v2 wire as text:
    // the bridge must refuse it exactly like a server would.
    val v2Wire = wire(request()).replace("\"v\": 1", "\"v\": 2")
    try {
      EnginePreview.previewPlan(v2Wire)
      fail("expected ApiVersionNotSupported")
    } catch (expected: ApiVersionNotSupported) {
      assertEquals(1, expected.supported)
      assertEquals(2, expected.received)
    }
  }
}