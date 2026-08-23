package com.example.export

/**
 * Draws a [PdfPlanLayout] plan into one tall PNG sheet with the framework Canvas - same
 * dependency-free rule as the PDF export, and same content: the pure layout decides what is on
 * the sheet, this class only puts pixels down.
 *
 * A plan image is a single sheet, not a paginated document: heading, every row in order, and a
 * generated-at footer. Nothing here decides what a row is; that lives in the callers and in
 * [PdfPlanLayout].
 */
object ImagePlanExporter {

  private const val SHEET_WIDTH_PX = 1400
  private const val MARGIN_PX = 48f
  private const val ROW_HEIGHT_PX = 44f
  private const val FOOTER_PX = 40f

  fun render(pages: List<PdfPage>): ByteArray {
    val rows = pages.flatMap { it.rows }
    val heading = pages.firstOrNull()?.heading.orEmpty()
    val height = (MARGIN_PX * 2 + ROW_HEIGHT_PX * (rows.size + 1) + FOOTER_PX).toInt()
    val bitmap = android.graphics.Bitmap.createBitmap(SHEET_WIDTH_PX, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)

    val titlePaint =
      android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 44f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        color = android.graphics.Color.rgb(28, 27, 31)
      }
    val textPaint =
      android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        color = android.graphics.Color.rgb(28, 27, 31)
      }
    val detailPaint =
      android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        color = android.graphics.Color.rgb(73, 69, 79)
      }
    val linePaint =
      android.graphics.Paint().apply {
        strokeWidth = 2f
        color = android.graphics.Color.rgb(200, 195, 205)
      }

    var y = MARGIN_PX + 44f
    canvas.drawText(truncate(heading, titlePaint, SHEET_WIDTH_PX - 2 * MARGIN_PX), MARGIN_PX, y, titlePaint)
    y += 24f
    canvas.drawLine(MARGIN_PX, y, SHEET_WIDTH_PX - MARGIN_PX, y, linePaint)
    y += ROW_HEIGHT_PX
    rows.forEach { row ->
      canvas.drawText(
        truncate(row.title, textPaint, SHEET_WIDTH_PX - 2 * MARGIN_PX),
        MARGIN_PX,
        y,
        textPaint,
      )
      canvas.drawText(
        truncate(row.detail, detailPaint, SHEET_WIDTH_PX - 2 * MARGIN_PX),
        MARGIN_PX + 380f,
        y,
        detailPaint,
      )
      y += ROW_HEIGHT_PX
    }
    if (heading.isNotBlank()) {
      canvas.drawText(
        "Daily Brief plan export · ${pages.firstOrNull()?.generatedAt.orEmpty()}",
        MARGIN_PX,
        y + FOOTER_PX - 8f,
        detailPaint,
      )
    }

    return java.io.ByteArrayOutputStream().use { stream ->
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
      bitmap.recycle()
      stream.toByteArray()
    }
  }

  private fun truncate(text: String, paint: android.graphics.Paint, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    var end = text.length
    while (end > 1 && paint.measureText(text.substring(0, end)) > maxWidth) end--
    return text.substring(0, end).trimEnd() + "..."
  }
}
