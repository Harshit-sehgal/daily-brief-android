package com.example.widget

import com.example.data.model.BriefingEvent
import com.example.data.model.PlanBlock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TodayWidgetRow(val title: String, val detail: String)

data class TodayWidgetContent(
  val headline: String,
  val rows: List<TodayWidgetRow>,
) {
  val isEmpty: Boolean
    get() = rows.isEmpty()

  companion object {
    const val MAX_ROWS = 4

    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun from(
      now: Long,
      dayStart: Long,
      dayEnd: Long,
      events: List<BriefingEvent>,
      blocks: List<PlanBlock>,
      itemTitles: Map<String, String>,
    ): TodayWidgetContent {
      val entries = mutableListOf<Triple<Long, Long, TodayWidgetRow>>()
      events
        .filter { it.endTime > dayStart && it.startTime < dayEnd && !it.isAllDay }
        .forEach { event ->
          entries +=
            Triple(
              event.startTime,
              event.endTime,
              TodayWidgetRow(
                event.title,
                "${formatTime(event.startTime)} – ${formatTime(event.endTime)}",
              ),
            )
        }
      blocks
        .filter { it.endAt > dayStart && it.startAt < dayEnd }
        .forEach { block ->
          entries +=
            Triple(
              block.startAt,
              block.endAt,
              TodayWidgetRow(
                itemTitles[block.planItemId] ?: "Planned",
                formatTime(block.startAt),
              ),
            )
        }
      val rows =
        entries
          .sortedBy { it.first }
          .filter { (_, endAt) -> endAt > now }
          .map { it.third }
          .take(MAX_ROWS)
      return TodayWidgetContent(
        headline = if (rows.isEmpty()) "Nothing scheduled today" else "Now · Up next",
        rows = rows,
      )
    }

    private fun formatTime(millis: Long): String = timeFormatter.format(Date(millis))
  }
}
