package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanExportTest {
  private val scope =
    ExportScope(
      boardName = "Atlas, launch",
      generatedAtMs = 1_760_000_000_000L,
      rangeStartMs = 1_760_000_000_000L,
      rangeEndMs = 1_760_600_000_000L,
      includesUnscheduled = false,
    )

  @Test
  fun `every export states what it covers and what it leaves out`() {
    val header = PlanExport.describe(scope, ::stamp)
    assertTrue(header, header.contains("Atlas, launch"))
    assertTrue(header, header.contains("covers"))
    assertTrue(header, header.contains("excludes tasks with no scheduled time"))
    // The one thing a spreadsheet would otherwise get wrong.
    assertTrue(header, header.contains("empty effort means the task states none, not zero"))

    val everything = PlanExport.describe(scope.copy(rangeStartMs = null, rangeEndMs = null, includesUnscheduled = true), ::stamp)
    assertTrue(everything, everything.contains("every scheduled block"))
    assertTrue(everything, everything.contains("includes tasks with no scheduled time"))
  }

  @Test
  fun `csv quotes what would otherwise break a row and never writes zero for unknown effort`() {
    val csv =
      PlanExport.toCsv(
        items =
          listOf(
            item("a", "Write the \"brief\", carefully", effort = 90, progress = 100),
            item("b", "Line\nbreak", effort = null),
            item("gone", "Archived", effort = 30, archivedAt = 1L),
          ),
        blocks = listOf(block("blk", "a", 1_000L, 2_000L)),
        scope = scope,
        formatIso = ::stamp,
      )
    val lines = csv.trim().lines()
    assertTrue(lines.first().startsWith("# Daily Brief plan export"))
    assertEquals("task,status,priority,effort_minutes,progress_percent,due,blocks,first_block_start,last_block_end", lines[1])

    val first = lines[2]
    assertTrue(first, first.startsWith("\"Write the \"\"brief\"\", carefully\""))
    assertTrue(first, first.contains(",done,"))

    // Unknown effort is an empty cell, and a newline never escapes its row.
    val second = lines[3]
    assertTrue(second, second.contains(",,"))
    assertEquals(4, lines.size)
    assertFalse("archived work is not exported", csv.contains("Archived"))
  }

  @Test
  fun `ics escapes text, carries the completeness note and keeps ownership explicit`() {
    val ics =
      PlanExport.toIcs(
        items = listOf(item("a", "Design; review, phase 1")),
        blocks = listOf(block("blk", "a", 1_760_000_000_000L, 1_760_003_600_000L)),
        scope = scope,
        formatIcsUtc = ::stamp,
      )
    assertTrue(ics.startsWith("BEGIN:VCALENDAR"))
    assertTrue(ics.trim().endsWith("END:VCALENDAR"))
    assertTrue(ics, ics.contains("SUMMARY:Design\\; review\\, phase 1"))
    assertTrue(ics, ics.contains("X-WR-CALDESC:"))
    // A Plan block is not a calendar commitment, and the export says so.
    assertTrue(ics, ics.contains("App-owned Plan block"))
    assertEquals(1, Regex("BEGIN:VEVENT").findAll(ics).count())

    // A block whose task is gone cannot be exported as an orphan event.
    val orphaned =
      PlanExport.toIcs(
        items = emptyList(),
        blocks = listOf(block("blk", "missing", 1L, 2L)),
        scope = scope,
        formatIcsUtc = ::stamp,
      )
    assertEquals(0, Regex("BEGIN:VEVENT").findAll(orphaned).count())
  }

  private fun stamp(value: Long) = "T$value"

  private fun item(
    id: String,
    title: String,
    effort: Int? = 60,
    progress: Int = 0,
    archivedAt: Long? = null,
  ) =
    PlanItem(
      id = id,
      boardId = "board",
      title = title,
      rank = 1,
      effortMinutes = effort,
      progress = progress,
      archivedAt = archivedAt,
      createdAt = 1,
      updatedAt = 1,
    )

  private fun block(id: String, itemId: String, startAt: Long, endAt: Long) =
    PlanBlock(
      id = id,
      planItemId = itemId,
      startAt = startAt,
      endAt = endAt,
      createdAt = 1,
      updatedAt = 1,
    )
}
