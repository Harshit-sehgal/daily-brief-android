package com.example.server.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookExportTest {
  // 2026-10-06T08:30:00Z, a Tuesday.
  private val now = 1_791_275_400_000L
  private val hour = 3_600_000L

  @Test
  fun `the copy reads as a page - plan by day in the reader's zone, then each project`() {
    val text =
      NotebookExport.markdown(
        nowMs = now,
        zoneId = "Asia/Kolkata",
        projects =
          listOf(
            NotebookExport.ProjectPage(
              "Northwind site",
              listOf(
                NotebookExport.TaskLine("Set up analytics", 60, null, done = true, highlighted = false),
                NotebookExport.TaskLine("Rework the hero", 150, now + 48 * hour, done = false, highlighted = true),
                NotebookExport.TaskLine("Book train", null, null, done = false, highlighted = false),
              ),
            ),
            NotebookExport.ProjectPage("Empty project", emptyList()),
          ),
        blocks = listOf(NotebookExport.PlannedBlock("Rework the hero", "Northwind site", now + hour, now + 3 * hour)),
      )

    assertEquals(
      """
      # Daily Brief

      _Updated 6 October 2026, 14:00 (Asia/Kolkata). Daily Brief rewrites this file; changes made here are not read back._

      ## The plan

      ### Tuesday 6 October

      - 15:00–17:00 Rework the hero (Northwind site)

      ## Northwind site

      - [ ] **Rework the hero** — 2h 30m, due 8 Oct
      - [ ] Book train
      - [x] Set up analytics — 1h

      """.trimIndent(),
      text,
    )
  }

  @Test
  fun `blocks that ended more than a day ago are left out and an unknown zone falls back to UTC`() {
    val text =
      NotebookExport.markdown(
        now,
        "Not/AZone",
        emptyList(),
        listOf(NotebookExport.PlannedBlock("Old work", "My Plan", now - 80 * hour, now - 79 * hour)),
      )

    assertTrue(text.contains("(UTC)"))
    assertFalse(text.contains("Old work"))
  }

  @Test
  fun `typed notes follow the projects, newest day first, and blank days are skipped`() {
    val text =
      NotebookExport.markdown(
        now,
        "UTC",
        emptyList(),
        emptyList(),
        listOf(
          NotebookExport.NotePage("2026-10-05", "Older"),
          NotebookExport.NotePage("2026-10-06", "  Ask Priya about the venue  "),
          NotebookExport.NotePage("2026-10-04", "   "),
        ),
      )

    assertTrue(text.endsWith("## Notes\n\n### 2026-10-06\n\nAsk Priya about the venue\n\n### 2026-10-05\n\nOlder\n"))
    assertFalse(text.contains("2026-10-04"))
  }
}
