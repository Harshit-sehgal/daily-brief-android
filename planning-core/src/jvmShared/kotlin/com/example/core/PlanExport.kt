package com.example.core

import com.example.data.model.PlanBlock
import com.example.data.model.PlanItem

/** What an export covers, so a file can never be read as more complete than it is. */
data class ExportScope(
  val boardName: String,
  val generatedAtMs: Long,
  val rangeStartMs: Long?,
  val rangeEndMs: Long?,
  val includesUnscheduled: Boolean,
)

/**
 * Plain-text exports of a plan.
 *
 * Every file starts by saying what it contains and what it leaves out, because a plan taken out of
 * the app loses the thing that made it trustworthy — the app's own refusal to imply certainty it
 * does not have. Unknown effort exports as empty rather than zero, and a range-limited export says
 * so on its first line.
 */
object PlanExport {
  /** One row per task, with its blocks flattened into columns a spreadsheet can read. */
  fun toCsv(
    items: List<PlanItem>,
    blocks: List<PlanBlock>,
    scope: ExportScope,
    formatIso: (Long) -> String,
  ): String {
    val active = items.filter { it.archivedAt == null }.sortedBy { it.rank }
    val byItem = blocks.groupBy(PlanBlock::planItemId)
    return buildString {
      appendLine("# ${describe(scope, formatIso)}")
      appendLine(
        listOf(
            "task",
            "status",
            "priority",
            "effort_minutes",
            "progress_percent",
            "due",
            "blocks",
            "first_block_start",
            "last_block_end",
          )
          .joinToString(",")
      )
      active.forEach { item ->
        val itemBlocks = byItem[item.id].orEmpty().sortedBy(PlanBlock::startAt)
        appendLine(
          listOf(
              csv(item.title),
              csv(if (item.progress == 100) "done" else "open"),
              csv(item.priority),
              item.effortMinutes?.toString().orEmpty(),
              item.progress.toString(),
              item.dueAt?.let { csv(formatIso(it)) }.orEmpty(),
              itemBlocks.size.toString(),
              itemBlocks.firstOrNull()?.let { csv(formatIso(it.startAt)) }.orEmpty(),
              itemBlocks.lastOrNull()?.let { csv(formatIso(it.endAt)) }.orEmpty(),
            )
            .joinToString(",")
        )
      }
    }
  }

  /** Scheduled blocks as calendar events, so a plan can be read by anything that reads ICS. */
  fun toIcs(
    items: List<PlanItem>,
    blocks: List<PlanBlock>,
    scope: ExportScope,
    formatIcsUtc: (Long) -> String,
  ): String {
    val titles = items.filter { it.archivedAt == null }.associate { it.id to it.title }
    val exported = blocks.filter { it.planItemId in titles }.sortedBy(PlanBlock::startAt)
    return buildString {
      appendLine("BEGIN:VCALENDAR")
      appendLine("VERSION:2.0")
      appendLine("PRODID:-//Daily Brief//Plan export//EN")
      appendLine("X-WR-CALDESC:${icsText(describe(scope, formatIcsUtc))}")
      exported.forEach { block ->
        appendLine("BEGIN:VEVENT")
        appendLine("UID:${block.id}@daily-brief")
        appendLine("DTSTAMP:${formatIcsUtc(scope.generatedAtMs)}")
        appendLine("DTSTART:${formatIcsUtc(block.startAt)}")
        appendLine("DTEND:${formatIcsUtc(block.endAt)}")
        appendLine("SUMMARY:${icsText(titles.getValue(block.planItemId))}")
        // The distinction the app is built on has to survive the export.
        appendLine("DESCRIPTION:${icsText("App-owned Plan block, not a calendar commitment.")}")
        appendLine("END:VEVENT")
      }
      appendLine("END:VCALENDAR")
    }
  }

  /** The completeness statement every export carries. */
  fun describe(scope: ExportScope, format: (Long) -> String): String =
    buildString {
      append("Daily Brief plan export · ${scope.boardName} · generated ${format(scope.generatedAtMs)}")
      if (scope.rangeStartMs != null && scope.rangeEndMs != null) {
        append(" · covers ${format(scope.rangeStartMs)} to ${format(scope.rangeEndMs)}")
      } else {
        append(" · covers every scheduled block")
      }
      append(
        if (scope.includesUnscheduled) {
          " · includes tasks with no scheduled time"
        } else {
          " · excludes tasks with no scheduled time"
        }
      )
      append(" · empty effort means the task states none, not zero")
    }

  private fun csv(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
      "\"" + value.replace("\"", "\"\"").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ') +
        "\""
    } else {
      value
    }

  private fun icsText(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace(";", "\\;")
      .replace(",", "\\,")
      .replace("\r\n", "\\n")
      .replace("\n", "\\n")
      .replace("\r", "\\n")
}
