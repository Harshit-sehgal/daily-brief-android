package com.example.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import com.example.data.model.BriefingEvent
import java.util.Calendar

/**
 * Alarm plumbing for the daily brief and per-event reminders.
 *
 * Everything here uses inexact, doze-friendly alarms — a schedule summary does
 * not justify the exact-alarm permission — and the daily brief re-arms itself
 * from the receiver so it keeps firing beyond the first day.
 */
object AlarmScheduler {
  private const val TAG = "AlarmScheduler"
  private const val DAILY_BRIEF_REQUEST_CODE = 101
  private const val EVENT_REMINDER_REQUEST_CODE = 102
  private const val REMINDER_MAINTENANCE_REQUEST_CODE = 103
  private const val REMINDER_LEDGER = "scheduled_event_reminders"
  private const val REMINDER_IDS = "event_ids"

  private fun alarmManager(context: Context) =
    context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

  private fun dailyBriefIntent(context: Context, flags: Int): PendingIntent? {
    val intent =
      Intent(context, BriefingAndReminderReceiver::class.java).apply {
        action = BriefingAndReminderReceiver.ACTION_DAILY_BRIEF
      }
    return PendingIntent.getBroadcast(context, DAILY_BRIEF_REQUEST_CODE, intent, flags)
  }

  /** Arms the next occurrence of the daily brief. Called again after each fire. */
  fun scheduleDailyBrief(context: Context, hour: Int, minute: Int) {
    val manager = alarmManager(context) ?: return
    val pendingIntent =
      dailyBriefIntent(context, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ?: return

    val target =
      Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis()
        set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        set(Calendar.MINUTE, minute.coerceIn(0, 59))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
      }

    try {
      setInexact(manager, target.timeInMillis, pendingIntent)
      Log.d(TAG, "Daily brief armed for ${target.time}")
    } catch (e: Exception) {
      Log.e(TAG, "Could not schedule the daily brief", e)
    }
  }

  fun cancelDailyBrief(context: Context) {
    val manager = alarmManager(context) ?: return
    dailyBriefIntent(context, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
      manager.cancel(it)
      it.cancel()
    }
  }

  private fun reminderMaintenanceIntent(context: Context, flags: Int): PendingIntent? =
    PendingIntent.getBroadcast(
      context,
      REMINDER_MAINTENANCE_REQUEST_CODE,
      Intent(context, BriefingAndReminderReceiver::class.java).apply {
        action = BriefingAndReminderReceiver.ACTION_REMINDER_MAINTENANCE
      },
      flags,
    )

  /** Rolls the bounded reminder horizon forward even when the app stays closed. */
  private fun scheduleReminderMaintenance(context: Context) {
    val manager = alarmManager(context) ?: return
    val pendingIntent =
      reminderMaintenanceIntent(
        context,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      ) ?: return
    val target =
      Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis()
        set(Calendar.HOUR_OF_DAY, 3)
        set(Calendar.MINUTE, 15)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
      }
    try {
      setInexact(manager, target.timeInMillis, pendingIntent)
    } catch (e: Exception) {
      Log.e(TAG, "Could not schedule reminder maintenance", e)
    }
  }

  private fun cancelReminderMaintenance(context: Context) {
    val manager = alarmManager(context) ?: return
    reminderMaintenanceIntent(
        context,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
      )
      ?.let {
        manager.cancel(it)
        it.cancel()
      }
  }

  private fun reminderIntent(
    context: Context,
    event: BriefingEvent?,
    eventId: String,
    startLabel: String,
    flags: Int,
  ): PendingIntent? {
    val intent =
      Intent(context, BriefingAndReminderReceiver::class.java).apply {
        action = BriefingAndReminderReceiver.ACTION_MEETING_REMINDER
        // PendingIntent identity ignores extras. A stable, unique data URI keeps
        // reminders for hash-colliding event IDs from replacing each other.
        data =
          Uri.Builder()
            .scheme("dailybrief")
            .authority("event-reminder")
            .appendPath(eventId)
            .build()
        putExtra(BriefingAndReminderReceiver.EXTRA_EVENT_ID, eventId)
        putExtra(BriefingAndReminderReceiver.EXTRA_EVENT_TITLE, event?.title.orEmpty())
        putExtra(BriefingAndReminderReceiver.EXTRA_EVENT_TIME, startLabel)
      }
    return PendingIntent.getBroadcast(context, EVENT_REMINDER_REQUEST_CODE, intent, flags)
  }

  /**
   * Replaces every reminder with one derived from [events]. Cancelling first
   * means edited or deleted events never fire a stale notification.
   */
  fun replaceEventReminders(
    context: Context,
    previous: List<BriefingEvent>,
    events: List<BriefingEvent>,
    leadMinutes: Int,
    enabled: Boolean,
  ) {
    // The durable ledger includes reminders that no longer have a database row,
    // so source deletions and disabling reminders cannot leave stale alarms live.
    val idsToCancel = scheduledReminderIds(context) + previous.map { it.id }
    idsToCancel.forEach { cancelEventReminder(context, it, updateLedger = false) }
    writeScheduledReminderIds(context, emptySet())
    if (!enabled) {
      cancelReminderMaintenance(context)
      return
    }
    scheduleReminderMaintenance(context)

    val manager = alarmManager(context) ?: return
    val now = System.currentTimeMillis()
    val leadMs = leadMinutes.coerceIn(0, 24 * 60) * 60_000L
    val scheduled = mutableSetOf<String>()

    events.filterNot { it.isAllDay }.forEach { event ->
      val triggerAt = event.startTime - leadMs
      if (triggerAt <= now) return@forEach
      val pendingIntent =
        reminderIntent(
          context,
          event,
          event.id,
          formatClock(context, event.startTime),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return@forEach
      try {
        setInexact(manager, triggerAt, pendingIntent)
        scheduled += event.id
      } catch (e: Exception) {
        Log.e(TAG, "Could not schedule reminder for ${event.title}", e)
      }
    }
    writeScheduledReminderIds(context, scheduled)
  }

  fun cancelEventReminders(context: Context, events: List<BriefingEvent>) {
    val manager = alarmManager(context) ?: return
    events.forEach { cancelEventReminder(manager, context, it.id) }
  }

  fun cancelEventReminder(context: Context, eventId: String) {
    cancelEventReminder(context, eventId, updateLedger = true)
  }

  private fun cancelEventReminder(context: Context, eventId: String, updateLedger: Boolean) {
    alarmManager(context)?.let { cancelEventReminder(it, context, eventId) }
    if (updateLedger) {
      writeScheduledReminderIds(context, scheduledReminderIds(context) - eventId)
    }
  }

  private fun cancelEventReminder(manager: AlarmManager, context: Context, eventId: String) {
    reminderIntent(
        context,
        null,
        eventId,
        "",
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
      )
      ?.let {
        manager.cancel(it)
        it.cancel()
      }
  }

  private fun scheduledReminderIds(context: Context): Set<String> =
    context
      .getSharedPreferences(REMINDER_LEDGER, Context.MODE_PRIVATE)
      .getStringSet(REMINDER_IDS, emptySet())
      ?.toSet()
      .orEmpty()

  private fun writeScheduledReminderIds(context: Context, ids: Set<String>) {
    context
      .getSharedPreferences(REMINDER_LEDGER, Context.MODE_PRIVATE)
      .edit { putStringSet(REMINDER_IDS, ids.toSet()) }
  }

  /** Doze-friendly and, unlike setExact*, needs no special permission. */
  private fun setInexact(manager: AlarmManager, triggerAtMs: Long, pendingIntent: PendingIntent) {
    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
  }

  /** Formatted with the user's own 12/24-hour preference. */
  private fun formatClock(context: Context, timeMs: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(timeMs))
}
