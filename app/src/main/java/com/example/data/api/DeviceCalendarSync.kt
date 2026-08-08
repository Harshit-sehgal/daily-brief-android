package com.example.data.api

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource

/** Result of reading the device calendars, including why it may be empty. */
data class CalendarFetch(
  val events: List<BriefingEvent>,
  val permissionGranted: Boolean,
  val error: String? = null,
)

object DeviceCalendarSync {
  private const val TAG = "DeviceCalendarSync"

  fun hasPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
      PackageManager.PERMISSION_GRANTED

  fun fetchDeviceCalendars(context: Context, startTimeMs: Long, endTimeMs: Long): CalendarFetch {
    if (!hasPermission(context)) {
      Log.w(TAG, "Calendar permission not granted.")
      return CalendarFetch(emptyList(), permissionGranted = false)
    }

    val builder: Uri.Builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
    ContentUris.appendId(builder, startTimeMs)
    ContentUris.appendId(builder, endTimeMs)

    val fullProjection =
      arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.DESCRIPTION,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
      )
    val coreProjection =
      arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.DESCRIPTION,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
      )

    var cursor: Cursor? = null
    var error: String? = null
    try {
      cursor =
        context.contentResolver.query(
          builder.build(),
          fullProjection,
          null,
          null,
          "${CalendarContract.Instances.BEGIN} ASC",
        )
    } catch (e: Exception) {
      Log.w(TAG, "Full projection failed, retrying with core columns.", e)
      try {
        cursor =
          context.contentResolver.query(
            builder.build(),
            coreProjection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
          )
      } catch (inner: Exception) {
        Log.e(TAG, "Device calendar query failed.", inner)
        error = "Couldn't read the device calendar"
      }
    }

    if (cursor == null) {
      return CalendarFetch(emptyList(), permissionGranted = true, error = error)
    }

    val events = mutableListOf<BriefingEvent>()
    cursor.use { c ->
      try {
        val idIdx = c.getColumnIndex(CalendarContract.Instances.EVENT_ID)
        val titleIdx = c.getColumnIndex(CalendarContract.Instances.TITLE)
        val descIdx = c.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
        val beginIdx = c.getColumnIndex(CalendarContract.Instances.BEGIN)
        val endIdx = c.getColumnIndex(CalendarContract.Instances.END)
        val locIdx = c.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
        val allDayIdx = c.getColumnIndex(CalendarContract.Instances.ALL_DAY)
        val calNameIdx = c.getColumnIndex(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)

        while (c.moveToNext()) {
          val begin = if (beginIdx >= 0) c.getLong(beginIdx) else continue
          val rawEnd = if (endIdx >= 0) c.getLong(endIdx) else begin
          // Some providers report end <= begin for point-in-time entries.
          val end = if (rawEnd > begin) rawEnd else begin + 30 * 60 * 1000
          val eventId = if (idIdx >= 0) c.getLong(idIdx) else begin
          val title = (if (titleIdx >= 0) c.getString(titleIdx) else null)?.trim().orEmpty()
          val description = (if (descIdx >= 0) c.getString(descIdx) else null)?.trim().orEmpty()
          val location = (if (locIdx >= 0) c.getString(locIdx) else null)?.trim().orEmpty()
          val calendarName = (if (calNameIdx >= 0) c.getString(calNameIdx) else null).orEmpty()
          val isAllDay = allDayIdx >= 0 && c.getInt(allDayIdx) == 1

          val haystack = "$title $description".lowercase()
          events.add(
            BriefingEvent(
              id = "device_${eventId}_$begin",
              title = title.ifEmpty { "Untitled event" },
              startTime = begin,
              endTime = end,
              source = sourceFor(calendarName),
              description = description.ifEmpty { null },
              isDeadline = !isAllDay && DEADLINE_HINTS.any { haystack.contains(it) },
              isUrgent = !isAllDay && URGENT_HINTS.any { haystack.contains(it) },
              location = location.ifEmpty { null },
            )
          )
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error reading device calendar cursor", e)
        error = "Some calendar entries couldn't be read"
      }
    }

    return CalendarFetch(events, permissionGranted = true, error = error)
  }

  private val URGENT_HINTS = listOf("urgent", "asap", "critical", "p0", "blocker")
  private val DEADLINE_HINTS = listOf("deadline", "due ", "due:", "cutoff", "submit by")

  /**
   * Labels the provider rather than guessing an account. Anything we cannot
   * confidently attribute stays a neutral "Device Calendar" instead of being
   * mislabelled as Google.
   */
  private fun sourceFor(calendarDisplayName: String): String {
    val name = calendarDisplayName.lowercase()
    return when {
      name.isEmpty() -> EventSource.DEVICE
      name.contains("samsung") || name.contains("sec_calendar") -> EventSource.SAMSUNG
      name.contains("gmail.com") || name.contains("google") || name.contains("googlemail") ->
        EventSource.GOOGLE
      else -> EventSource.DEVICE
    }
  }

  /**
   * A realistic day the user can load on purpose from Settings. It is never
   * inserted silently — an empty schedule should look empty.
   */
  fun sampleDay(dayStartMs: Long): List<BriefingEvent> {
    fun at(hour: Int, minute: Int = 0) = dayStartMs + (hour * 60L + minute) * 60_000L
    return listOf(
      BriefingEvent(
        id = "sample_standup",
        title = "Team standup",
        startTime = at(9, 30),
        endTime = at(9, 45),
        source = EventSource.SAMPLE,
        description = "Quick round-up with the product team.",
        isDeadline = false,
        isUrgent = false,
      ),
      BriefingEvent(
        id = "sample_review",
        title = "Design review",
        startTime = at(11),
        endTime = at(12),
        source = EventSource.SAMPLE,
        description = "Walk through the new dashboard layouts.",
        isDeadline = false,
        isUrgent = false,
      ),
      BriefingEvent(
        id = "sample_client",
        title = "Client check-in",
        startTime = at(11, 30),
        endTime = at(12, 15),
        source = EventSource.SAMPLE,
        description = "Overlaps the design review — one of them has to move.",
        isDeadline = false,
        isUrgent = true,
      ),
      BriefingEvent(
        id = "sample_ship",
        title = "Ship release notes",
        startTime = at(16),
        endTime = at(17),
        source = EventSource.SAMPLE,
        description = "Final copy due before end of day.",
        isDeadline = true,
        isUrgent = false,
      ),
    )
  }
}
