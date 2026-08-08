package com.example.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.core.ScheduleAnalysis
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BriefingAndReminderReceiver : BroadcastReceiver() {
  companion object {
    private const val TAG = "BriefingReceiver"
    const val CHANNEL_ID_BRIEF = "daily_brief_channel"
    const val CHANNEL_ID_REMINDER = "meeting_reminder_channel"

    const val ACTION_DAILY_BRIEF = "com.example.ACTION_DAILY_BRIEF"
    const val ACTION_MEETING_REMINDER = "com.example.ACTION_MEETING_REMINDER"

    const val EXTRA_EVENT_ID = "extra_event_id"
    const val EXTRA_EVENT_TITLE = "extra_event_title"
    const val EXTRA_EVENT_TIME = "extra_event_time"

    private const val DAILY_BRIEF_NOTIFICATION_ID = 1001

    fun createNotificationChannels(context: Context) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
      val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

      manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID_BRIEF,
            "Daily brief",
            NotificationManager.IMPORTANCE_DEFAULT,
          )
          .apply { description = "A summary of the day, at the time you choose." }
      )
      manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID_REMINDER,
            "Event reminders",
            NotificationManager.IMPORTANCE_HIGH,
          )
          .apply {
            description = "A heads-up shortly before an event starts."
            enableVibration(true)
          }
      )
    }
  }

  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    Log.d(TAG, "Received $action")
    createNotificationChannels(context)

    // Broadcast receivers are killed as soon as onReceive returns; without this
    // the database read below would race the process shutting down.
    val pendingResult = goAsync()
    val appContext = context.applicationContext
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try {
        when (action) {
          ACTION_DAILY_BRIEF -> handleDailyBrief(appContext)
          ACTION_MEETING_REMINDER ->
            showReminder(
              appContext,
              intent.getStringExtra(EXTRA_EVENT_ID).orEmpty(),
              intent.getStringExtra(EXTRA_EVENT_TITLE).orEmpty(),
              intent.getStringExtra(EXTRA_EVENT_TIME).orEmpty(),
            )
          Intent.ACTION_BOOT_COMPLETED,
          Intent.ACTION_MY_PACKAGE_REPLACED -> restoreAlarms(appContext)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed handling $action", e)
      } finally {
        pendingResult.finish()
      }
    }
  }

  private suspend fun handleDailyBrief(context: Context) {
    val repository = BriefingRepository(context)
    val bounds = ScheduleAnalysis.dayBounds(System.currentTimeMillis())
    val events = repository.eventsInRangeOnce(bounds.first, bounds.last)
    val stats = ScheduleAnalysis.statsFor(events)

    val body =
      when {
        stats.total == 0 -> "Nothing scheduled today."
        else ->
          buildString {
            append("${stats.total} ${if (stats.total == 1) "entry" else "entries"}")
            if (stats.urgent > 0) append(" · ${stats.urgent} priority")
            if (stats.conflicts > 0)
              append(" · ${stats.conflicts} ${if (stats.conflicts == 1) "clash" else "clashes"}")
          }
      }

    val title =
      "Today · ${SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(Date())}"
    notify(
      context,
      DAILY_BRIEF_NOTIFICATION_ID,
      CHANNEL_ID_BRIEF,
      title,
      body,
      NotificationCompat.PRIORITY_DEFAULT,
    )

    // Re-arm for tomorrow; a one-shot alarm would otherwise never fire again.
    val hour = repository.readSetting(SettingKeys.BRIEF_HOUR)?.toIntOrNull() ?: 8
    val minute = repository.readSetting(SettingKeys.BRIEF_MINUTE)?.toIntOrNull() ?: 0
    val enabled = repository.readSetting(SettingKeys.DAILY_BRIEF_ENABLED)?.toBooleanStrictOrNull() ?: true
    if (enabled) AlarmScheduler.scheduleDailyBrief(context, hour, minute)
  }

  private fun showReminder(context: Context, id: String, title: String, timeLabel: String) {
    if (title.isBlank()) return
    val body = if (timeLabel.isBlank()) "Starting soon." else "Starts at $timeLabel."
    notify(
      context,
      if (id.isBlank()) title.hashCode() else id.hashCode(),
      CHANNEL_ID_REMINDER,
      title,
      body,
      NotificationCompat.PRIORITY_HIGH,
    )
  }

  /** After a reboot or an app update every alarm has to be laid down again. */
  private suspend fun restoreAlarms(context: Context) {
    val repository = BriefingRepository(context)
    val briefEnabled =
      repository.readSetting(SettingKeys.DAILY_BRIEF_ENABLED)?.toBooleanStrictOrNull() ?: true
    if (briefEnabled) {
      AlarmScheduler.scheduleDailyBrief(
        context,
        repository.readSetting(SettingKeys.BRIEF_HOUR)?.toIntOrNull() ?: 8,
        repository.readSetting(SettingKeys.BRIEF_MINUTE)?.toIntOrNull() ?: 0,
      )
    }

    val remindersEnabled =
      repository.readSetting(SettingKeys.REMINDERS_ENABLED)?.toBooleanStrictOrNull() ?: true
    if (remindersEnabled) {
      val now = System.currentTimeMillis()
      val upcoming = repository.eventsInRangeOnce(now, now + 14 * ScheduleAnalysis.DAY_MS)
      AlarmScheduler.replaceEventReminders(
        context = context,
        previous = emptyList(),
        events = upcoming,
        leadMinutes = repository.readSetting(SettingKeys.REMINDER_LEAD_MINUTES)?.toIntOrNull() ?: 30,
        enabled = true,
      )
    }
  }

  @android.annotation.SuppressLint("MissingPermission")
  private fun notify(
    context: Context,
    id: Int,
    channelId: String,
    title: String,
    body: String,
    priority: Int,
  ) {
    val contentIntent =
      PendingIntent.getActivity(
        context,
        id,
        Intent(context, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val notification =
      NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setPriority(priority)
        .setAutoCancel(true)
        .setContentIntent(contentIntent)
        .build()

    val manager = NotificationManagerCompat.from(context)
    // On Android 13+ posting without the runtime permission throws; the app
    // asks for it from Settings, and a refusal must not crash an alarm.
    if (!manager.areNotificationsEnabled()) {
      Log.d(TAG, "Notifications disabled; skipping $channelId")
      return
    }
    try {
      manager.notify(id, notification)
    } catch (e: SecurityException) {
      Log.w(TAG, "Missing POST_NOTIFICATIONS permission", e)
    }
  }
}
