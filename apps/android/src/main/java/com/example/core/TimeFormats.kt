package com.example.core

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Date and time strings for the UI.
 *
 * Built from the device locale and the user's 12/24-hour preference rather than
 * hard-coded to US formats, and held as a single remembered instance so list
 * items are not each allocating formatters while scrolling.
 */
@Stable
class TimeFormatter(val is24Hour: Boolean, private val locale: Locale) {
  private fun localized(skeleton: String) =
    SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

  private val timeFmt = localized(if (is24Hour) "Hm" else "hma")
  private val weekdayShortFmt = localized("EEE")
  private val dayOfMonthFmt = localized("d")
  private val monthShortFmt = localized("MMM")
  private val fullDayFmt = localized("EEEEdMMMM")
  private val mediumDayFmt = localized("EEEdMMM")

  fun time(ms: Long): String = timeFmt.format(Date(ms))

  fun range(startMs: Long, endMs: Long): String = "${time(startMs)} – ${time(endMs)}"

  fun weekday(ms: Long): String = weekdayShortFmt.format(Date(ms)).uppercase(locale)

  fun dayOfMonth(ms: Long): String = dayOfMonthFmt.format(Date(ms))

  fun month(ms: Long): String = monthShortFmt.format(Date(ms))

  fun fullDay(ms: Long): String = fullDayFmt.format(Date(ms))

  fun mediumDay(ms: Long): String = mediumDayFmt.format(Date(ms))

  /** "Today" / "Tomorrow" / "Yesterday", falling back to a written date. */
  fun relativeDay(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
    val day = ScheduleAnalysis.startOfDay(ms)
    val today = ScheduleAnalysis.startOfDay(nowMs)
    return when (day) {
      today -> "Today"
      ScheduleAnalysis.startOfDayOffset(today, 1) -> "Tomorrow"
      ScheduleAnalysis.startOfDayOffset(today, -1) -> "Yesterday"
      else -> fullDay(ms)
    }
  }

  /** Compact duration: "45m", "1h", "2h 30m". */
  fun duration(startMs: Long, endMs: Long): String {
    val minutes = ((endMs - startMs).coerceAtLeast(0L) / 60_000L).toInt()
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
      hours == 0 -> "${mins}m"
      mins == 0 -> "${hours}h"
      else -> "${hours}h ${mins}m"
    }
  }
}

@Composable
fun rememberTimeFormatter(): TimeFormatter {
  val context = LocalContext.current
  val is24Hour = DateFormat.is24HourFormat(context)
  val locale = LocalLocale.current.platformLocale
  return remember(is24Hour, locale) { TimeFormatter(is24Hour, locale) }
}

/** Stable machine key for the briefing cache. Always US so it never shifts with locale. */
fun isoDate(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

/** Time-of-day greeting used in the Today header. */
fun greetingFor(ms: Long = System.currentTimeMillis()): String {
  val hour = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)
  return when (hour) {
    in 0..4 -> "Good night"
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
  }
}
