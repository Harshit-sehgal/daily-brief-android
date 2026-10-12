package com.example.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskEditTest {
  private fun parse(json: String) = TaskEdit.parse(Json.decodeFromString<JsonObject>(json), nowMs = 1_000)

  @Test
  fun `only the keys present are changed`() {
    val edit = parse("""{"priority":"high"}""")

    assertEquals(listOf("priority" to "high"), edit.sets)
    assertFalse(edit.leavesPlan)
  }

  @Test
  fun `an explicit null clears an estimate and a due date`() {
    val edit = parse("""{"effortMinutes":null,"dueAt":null}""")

    assertEquals(listOf<Pair<String, Any?>>("effort_minutes" to null, "due_at" to null), edit.sets)
  }

  @Test
  fun `completing stamps the time and takes the task off the plan ahead`() {
    val edit = parse("""{"completed":true}""")

    assertEquals(listOf<Pair<String, Any?>>("completed_at" to 1_000L), edit.sets)
    assertTrue(edit.leavesPlan)
  }

  @Test
  fun `reopening or restoring clears the stamp and leaves the plan alone`() {
    val edit = parse("""{"completed":false,"archived":false}""")

    assertEquals(listOf<Pair<String, Any?>>("completed_at" to null, "archived_at" to null), edit.sets)
    assertFalse(edit.leavesPlan)
  }

  @Test
  fun `a title is trimmed and may not be blank`() {
    assertEquals(listOf("title" to "Call Asha"), parse("""{"title":"  Call Asha "}""").sets)
    assertThrows(IllegalArgumentException::class.java) { parse("""{"title":"   "}""") }
  }

  @Test
  fun `nonsense is refused rather than written`() {
    assertThrows(IllegalArgumentException::class.java) { parse("{}") }
    assertThrows(IllegalArgumentException::class.java) { parse("""{"priority":"shouting"}""") }
    assertThrows(IllegalArgumentException::class.java) { parse("""{"effortMinutes":0}""") }
    assertThrows(IllegalArgumentException::class.java) { parse("""{"effortMinutes":100000}""") }
  }

  @Test
  fun `a day note is refused when its day is not a date or it is too large`() {
    assertEquals(null, DayNote("2026-10-06", "Call Asha").refusalReason())
    assertTrue(DayNote("2026-13-06").refusalReason() != null)
    assertTrue(DayNote("../etc").refusalReason() != null)
    assertTrue(DayNote("2026-10-06", "x".repeat(DayNote.MAX_TEXT + 1)).refusalReason() != null)
  }
}
