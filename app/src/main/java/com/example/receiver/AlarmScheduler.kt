package com.example.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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
        putExtra(BriefingAndReminderReceiver.EXTRA_EVENT_ID, eventId)
        putExtra(BriefingAndReminderReceiver.EXTRA_EVENT_TITLE, event?.title.orEmpty())
        putExtra(BriefingAndReminderReceiver.EXTRA_EVENT_TIME, startLabel)
      }
    return PendingIntent.getBroadcast(context, eventId.hashCode(), intent, flags)
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
    cancelEventReminders(context, previous)
    if (!enabled) return

    val manager = alarmManager(context) ?: return
    val now = System.currentTimeMillis()
    val leadMs = leadMinutes.coerceIn(0, 24 * 60) * 60_000L

    events.forEach { event ->
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
      } catch (e: Exception) {
        Log.e(TAG, "Could not schedule reminder for ${event.title}", e)
      }
    }
  }

  fun cancelEventReminders(context: Context, events: List<BriefingEvent>) {
    val manager = alarmManager(context) ?: return
    events.forEach { cancelEventReminder(manager, context, it.id) }
  }

  fun cancelEventReminder(context: Context, eventId: String) {
    val manager = alarmManager(context) ?: return
    cancelEventReminder(manager, context, eventId)
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

  private fun setInexact(manager: AlarmManager, triggerAtMs: Long, pendingIntent: PendingIntent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
    } else {
      manager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
    }
  }

  /** Formatted with the user's own 12/24-hour preference. */
  private fun formatClock(context: Context, timeMs: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(timeMs))
}
