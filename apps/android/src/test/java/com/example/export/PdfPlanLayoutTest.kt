package com.example.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPlanLayoutTest {

  private fun rows(count: Int): List<PdfRow> =
    (1..count).map { PdfRow("task $it", "detail $it") }

  @Test
  fun `an empty plan still gets a titled page`() {
    val pages = PdfPlanLayout.pages("My plan", "generated", emptyList())

    assertEquals(1, pages.size)
    assertEquals("My plan", pages.single().heading)
    assertTrue(pages.single().rows.isEmpty())
  }

  @Test
  fun `a plan larger than one page splits with continued headings`() {
    val pages = PdfPlanLayout.pages("My plan", "generated", rows(PdfPlanLayout.ROWS_PER_PAGE + 3))

    assertEquals(2, pages.size)
    assertEquals("My plan", pages[0].heading)
    assertEquals("My plan — continued", pages[1].heading)
    assertEquals(PdfPlanLayout.ROWS_PER_PAGE, pages[0].rows.size)
    assertEquals(3, pages[1].rows.size)
  }

  @Test
  fun `rows are never lost in pagination`() {
    val all = rows(60)
    val pages = PdfPlanLayout.pages("My plan", "generated", all)

    assertEquals(3, pages.size)
    assertEquals(all, pages.flatMap { it.rows })
  }
}
