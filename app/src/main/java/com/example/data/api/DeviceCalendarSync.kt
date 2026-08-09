package com.example.data.api

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.core.ScheduleAnalysis
import com.example.data.model.BriefingEvent
import com.example.data.model.EventSource
import java.util.TimeZone

/** Outcome of pushing a local edit back to the device calendar. */
sealed interface WriteBack {
  /** Not a device-calendar event; nothing to push. */
  data object NotApplicable : WriteBack

  data object Success : WriteBack

  /** Deliberately left local — the reason is safe to show the user. */
  data class Skipped(val reason: String) : WriteBack

  data class Failed(val reason: String) : WriteBack
}

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
        CalendarContract.Instances.ALL_DAY,
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
          val rawBegin = if (beginIdx >= 0) c.getLong(beginIdx) else continue
          val rawEnd = if (endIdx >= 0) c.getLong(endIdx) else rawBegin
          val isAllDay = allDayIdx >= 0 && c.getInt(allDayIdx) == 1
          val (begin, end) =
            if (isAllDay) {
              ScheduleAnalysis.normalizeAllDayUtcRange(rawBegin, rawEnd)
            } else {
              // Some providers report end <= begin for point-in-time entries.
              rawBegin to
                (if (rawEnd > rawBegin) rawEnd else rawBegin + 30 * 60 * 1000)
            }
          val eventId = if (idIdx >= 0) c.getLong(idIdx) else rawBegin
          val title = (if (titleIdx >= 0) c.getString(titleIdx) else null)?.trim().orEmpty()
          val description = (if (descIdx >= 0) c.getString(descIdx) else null)?.trim().orEmpty()
          val location = (if (locIdx >= 0) c.getString(locIdx) else null)?.trim().orEmpty()
          val calendarName = (if (calNameIdx >= 0) c.getString(calNameIdx) else null).orEmpty()
          val haystack = "$title $description".lowercase()
          events.add(
            BriefingEvent(
              // Keep the provider's UTC boundary in the ID so a timezone change
              // does not create a second copy of the same all-day instance.
              id = "device_${eventId}_$rawBegin",
              title = title.ifEmpty { "Untitled event" },
              startTime = begin,
              endTime = end,
              source = sourceFor(calendarName),
              description = description.ifEmpty { null },
              isDeadline = !isAllDay && DEADLINE_HINTS.any { haystack.contains(it) },
              isUrgent = !isAllDay && URGENT_HINTS.any { haystack.contains(it) },
              isAllDay = isAllDay,
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

  fun hasWritePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
      PackageManager.PERMISSION_GRANTED

  /**
   * Provider row id encoded in our event id (`device_<eventId>_<begin>`).
   * The begin time is part of the id so recurring instances stay distinct.
   */
  internal fun providerEventId(id: String): Long? {
    if (!id.startsWith("device_")) return null
    val rest = id.removePrefix("device_")
    val split = rest.lastIndexOf('_')
    if (split <= 0) return null
    return rest.substring(0, split).toLongOrNull()
  }

  /**
   * Pushes a hand edit back to the calendar the event came from.
   *
   * Repeating and all-day events are deliberately left alone: changing them
   * through the Events row rewrites the whole series or corrupts the UTC day
   * boundary, which is not what someone editing one entry expects.
   */
  fun writeBack(context: Context, event: BriefingEvent): WriteBack {
    if (event.source !in EventSource.DEVICE_WRITABLE) return WriteBack.NotApplicable
    val rowId = providerEventId(event.id) ?: return WriteBack.Failed("Unrecognised calendar entry")
    if (!hasWritePermission(context)) {
      return WriteBack.Skipped("Calendar write access is off — saved here only")
    }
    if (event.isAllDay) {
      return WriteBack.Skipped("All-day events stay local — edit them in your calendar app")
    }
    if (isRecurring(context, rowId)) {
      return WriteBack.Skipped("Repeating events stay local — edit the series in your calendar app")
    }

    return try {
      val values =
        ContentValues().apply {
          put(CalendarContract.Events.TITLE, event.title)
          put(CalendarContract.Events.DESCRIPTION, event.description.orEmpty())
          put(CalendarContract.Events.EVENT_LOCATION, event.location.orEmpty())
          put(CalendarContract.Events.DTSTART, event.startTime)
          put(CalendarContract.Events.DTEND, event.endTime)
          put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
      val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, rowId)
      val rows = context.contentResolver.update(uri, values, null, null)
      if (rows > 0) WriteBack.Success else WriteBack.Failed("The calendar rejected the change")
    } catch (e: SecurityException) {
      Log.w(TAG, "No permission to write the calendar", e)
      WriteBack.Skipped("Calendar write access is off — saved here only")
    } catch (e: Exception) {
      Log.e(TAG, "Calendar write-back failed", e)
      WriteBack.Failed("Couldn't update the calendar")
    }
  }

  /** Removes the event from the calendar it came from. */
  fun deleteFromProvider(context: Context, eventId: String): WriteBack {
    val rowId = providerEventId(eventId) ?: return WriteBack.NotApplicable
    if (!hasWritePermission(context)) {
      return WriteBack.Skipped("Calendar write access is off — removed here only")
    }
    return try {
      val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, rowId)
      val rows = context.contentResolver.delete(uri, null, null)
      if (rows > 0) WriteBack.Success else WriteBack.Failed("The calendar rejected the deletion")
    } catch (e: Exception) {
      Log.e(TAG, "Calendar delete failed", e)
      WriteBack.Failed("Couldn't remove it from the calendar")
    }
  }

  private fun isRecurring(context: Context, rowId: Long): Boolean =
    try {
      context.contentResolver
        .query(
          ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, rowId),
          arrayOf(CalendarContract.Events.RRULE, CalendarContract.Events.RDATE),
          null,
          null,
          null,
        )
        ?.use { c ->
          if (!c.moveToFirst()) false
          else (0..1).any { i -> !c.getString(i).isNullOrBlank() }
        } ?: false
    } catch (e: Exception) {
      Log.w(TAG, "Could not read recurrence; treating as repeating to be safe", e)
      true
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
    fun at(hour: Int, minute: Int = 0) =
      ScheduleAnalysis.withTimeOfDay(dayStartMs, hour, minute)
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
