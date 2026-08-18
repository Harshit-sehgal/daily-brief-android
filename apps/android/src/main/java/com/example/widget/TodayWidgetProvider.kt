package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.core.ScheduleAnalysis
import com.example.data.repository.BriefingRepository
import com.example.data.repository.PlanRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The home-screen glance: today's events and plan blocks, merged and capped at four rows.
 *
 * The layout is filled from Room directly — the widget is not a second app and must not keep its
 * own copy of the day's state. Data queries run on an IO scope and the broadcast is held open
 * (goAsync) until the view is rendered, never dropped.
 */
class TodayWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    rebuild(context, appWidgetManager, appWidgetIds)
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == ACTION_REFRESH) {
      val manager = AppWidgetManager.getInstance(context)
      rebuild(context, manager, manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java)))
      return
    }
    super.onReceive(context, intent)
  }

  private fun rebuild(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
    if (appWidgetIds.isEmpty()) return
    val pending = goAsync()
    updateScope.launch {
      try {
        val now = System.currentTimeMillis()
        val dayStart = ScheduleAnalysis.startOfDay(now)
        val dayEnd = ScheduleAnalysis.startOfDayOffset(dayStart, 1)
        val events = BriefingRepository(context).eventsInRangeOnce(dayStart, dayEnd)
        val planRepository = PlanRepository(context)
        val boardId = planRepository.observeActiveBoardId().first()
        val itemTitles = mutableMapOf<String, String>()
        val blocks =
          if (boardId == null) {
            emptyList()
          } else {
            planRepository.observeItems(boardId).first().forEach { item -> itemTitles[item.id] = item.title }
            planRepository.observeBlocks(boardId).first()
          }
        val content = TodayWidgetContent.from(now, dayStart, dayEnd, events, blocks, itemTitles)
        appWidgetIds.forEach { id -> manager.updateAppWidget(id, views(context, content)) }
      } finally {
        pending.finish()
      }
    }
  }

  private fun views(context: Context, content: TodayWidgetContent): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.today_widget)
    views.setTextViewText(R.id.widget_headline, content.headline)
    content.rows.forEachIndexed { index, row ->
      val rowId = ROW_IDS[index]
      views.setTextViewText(rowId, "${row.detail}  ${row.title}")
      views.setViewVisibility(rowId, android.view.View.VISIBLE)
    }
    for (i in content.rows.size until TodayWidgetContent.MAX_ROWS) {
      views.setViewVisibility(ROW_IDS[i], android.view.View.GONE)
    }
    val openApp =
      PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    views.setOnClickPendingIntent(R.id.widget_headline, openApp)
    views.setOnClickPendingIntent(ROW_IDS[0], openApp)
    return views
  }

  companion object {
    const val ACTION_REFRESH = "com.example.dailybrief.action.REFRESH_WIDGET"

    private val updateScope = CoroutineScope(Dispatchers.IO + Job())

    private val ROW_IDS = intArrayOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3, R.id.widget_row_4)
  }
}
