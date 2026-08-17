package com.example.export

/**
 * A page-sized slice of the plan document, laid out purely.
 *
 * Everything about what fits on a page and how rows are split is decided here, without touching
 * the Android graphics stack, so the pagination can be unit-tested the way the engine is. The
 * renderer only draws what this lays out.
 */
data class PdfRow(val title: String, val detail: String)

data class PdfPage(val heading: String, val rows: List<PdfRow>)

object PdfPlanLayout {
  /** A4 portrait at 10pt rows minus margins, generous enough for a week's plan. */
  const val ROWS_PER_PAGE = 26

  fun pages(
    title: String,
    generatedAt: String,
    rows: List<PdfRow>,
  ): List<PdfPage> {
    if (rows.isEmpty()) return listOf(PdfPage(title, emptyList()))
    return rows.chunked(ROWS_PER_PAGE).mapIndexed { index, slice ->
      PdfPage(
        heading = if (index == 0) title else "$title — continued",
        rows = slice,
      )
    }
  }
}
