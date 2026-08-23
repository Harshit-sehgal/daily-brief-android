package com.example.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream

/**
 * Draws a [PdfPlanLayout] into PDF bytes with the framework PdfDocument - no third-party PDF
 * library, so the export stays offline and dependency-free like the rest of the app.
 *
 * This class is deliberately thin: it has no opinions about what a page contains, it only puts
 * the pages on paper. Pagination lives in [PdfPlanLayout], which the unit tests can reach
 * without an Android runtime.
 */
object PdfPlanExporter {

  private const val PAGE_WIDTH_PT = 595f
  private const val PAGE_HEIGHT_PT = 842f
  private const val MARGIN_PT = 40f
  private const val ROW_HEIGHT_PT = 26f
  private const val FOOTER_ROW_PT = 30f

  fun render(pages: List<PdfPage>): ByteArray {
    val document = PdfDocument()
    val titlePaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        color = Color.rgb(28, 27, 31)
      }
    val textPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f
        color = Color.rgb(28, 27, 31)
      }
    val detailPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f
        color = Color.rgb(73, 69, 79)
      }
    val linePaint =
      Paint().apply {
        strokeWidth = 0.5f
        color = Color.rgb(200, 195, 205)
      }

    pages.forEachIndexed { index, page ->
      val pageInfo =
        PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT.toInt(), PAGE_HEIGHT_PT.toInt(), index + 1)
          .create()
      val pdfPage = document.startPage(pageInfo)
      val canvas: Canvas = pdfPage.canvas
      canvas.drawText(page.heading, MARGIN_PT, 60f, titlePaint)
      canvas.drawLine(MARGIN_PT, 68f, PAGE_WIDTH_PT - MARGIN_PT, 68f, linePaint)
      page.rows.forEachIndexed { rowIndex, row ->
        val y = 96f + rowIndex * ROW_HEIGHT_PT
        canvas.drawText(
          truncate(row.title, textPaint, PAGE_WIDTH_PT - 2 * MARGIN_PT),
          MARGIN_PT,
          y,
          textPaint,
        )
        canvas.drawText(
          truncate(row.detail, detailPaint, PAGE_WIDTH_PT - 2 * MARGIN_PT),
          MARGIN_PT + 190f,
          y,
          detailPaint,
        )
      }
      canvas.drawText(
        "${page.generatedAt} · ${index + 1} / ${pages.size}",
        MARGIN_PT,
        PAGE_HEIGHT_PT - FOOTER_ROW_PT,
        detailPaint,
      )
      document.finishPage(pdfPage)
    }

    return ByteArrayOutputStream().use { stream ->
      document.writeTo(stream)
      document.close()
      stream.toByteArray()
    }
  }

  private fun truncate(text: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    var end = text.length
    while (end > 1 && paint.measureText(text.substring(0, end)) > maxWidth) end--
    return text.substring(0, end).trimEnd() + "..."
  }
}
