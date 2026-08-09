package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.prefs.SettingKeys
import com.example.data.repository.BriefingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Restores one-shot alarms after boot, update, or a wall-clock change. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
      intent.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
      intent.action != Intent.ACTION_TIME_CHANGED &&
      intent.action != Intent.ACTION_TIMEZONE_CHANGED
    ) {
      Log.w(TAG, "Ignoring unexpected action ${intent.action}")
      return
    }

    val pendingResult = goAsync()
    val appContext = context.applicationContext
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try {
        if (intent.action == Intent.ACTION_TIME_CHANGED ||
          intent.action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
          // Stored all-day boundaries are normalized to the local zone. Force
          // the next foreground launch to reconcile them in the new clock.
          BriefingRepository(appContext).writeSetting(SettingKeys.LAST_SYNC_AT, "0")
        }
        BriefingAndReminderReceiver().restoreAlarms(appContext)
      } catch (e: Exception) {
        Log.e(TAG, "Could not restore alarms", e)
      } finally {
        pendingResult.finish()
      }
    }
  }

  private companion object {
    const val TAG = "BootReceiver"
  }
}
