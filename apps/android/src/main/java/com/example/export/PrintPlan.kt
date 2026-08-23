package com.example.export

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.PageRange
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.IOException

/** Sends the same paginated PDF used by export to Android's system print preview. */
object PrintPlan {
  fun print(context: Context, jobName: String, pages: List<PdfPage>) {
    val printManager =
      context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        ?: throw IllegalStateException("Printing is unavailable on this device")
    printManager.print(
      jobName,
      Adapter(pages),
      PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .build(),
    )
  }

  private class Adapter(private val pages: List<PdfPage>) : PrintDocumentAdapter() {
    override fun onLayout(
      oldAttributes: PrintAttributes?,
      newAttributes: PrintAttributes,
      cancellationSignal: CancellationSignal,
      callback: LayoutResultCallback,
      extras: android.os.Bundle?,
    ) {
      if (cancellationSignal.isCanceled) {
        callback.onLayoutCancelled()
        return
      }
      callback.onLayoutFinished(
        PrintDocumentInfo.Builder("daily-brief-plan.pdf")
          .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
          .setPageCount(pages.size)
          .build(),
        oldAttributes != newAttributes,
      )
    }

    override fun onWrite(
      pageRanges: Array<PageRange>,
      destination: ParcelFileDescriptor,
      cancellationSignal: CancellationSignal,
      callback: WriteResultCallback,
    ) {
      if (cancellationSignal.isCanceled) {
        destination.close()
        callback.onWriteCancelled()
        return
      }
      try {
        ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
          output.write(PdfPlanExporter.render(pages))
        }
        if (cancellationSignal.isCanceled) callback.onWriteCancelled()
        else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
      } catch (e: IOException) {
        callback.onWriteFailed(e.message)
      }
    }
  }
}
