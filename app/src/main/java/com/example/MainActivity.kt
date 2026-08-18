package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.prefs.SettingKeys
import com.example.receiver.BriefingAndReminderReceiver
import com.example.ui.DailyBriefApp
import com.example.ui.theme.DailyBriefTheme
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.LocalUiDensity
import com.example.ui.theme.LocalWindowWidth
import com.example.ui.theme.LocalWindowWidthDp
import com.example.ui.theme.currentWindowHeightDp
import com.example.ui.theme.LocalWindowHeightDp
import com.example.ui.theme.currentWindowWidth
import com.example.ui.theme.currentWindowWidthDp
import com.example.ui.theme.tokensFor
import com.example.ui.viewmodel.BriefingViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    BriefingAndReminderReceiver.createNotificationChannels(this)
    registerLaunchShortcuts(this)

    val initialDestination =
      intent.getStringExtra(EXTRA_DESTINATION)
        ?.takeIf { it in setOf(DestinationShortcut.PLAN, DestinationShortcut.TODAY) }

    setContent {
      val viewModel: BriefingViewModel = viewModel()
      val accentKey by viewModel.themeAccent.collectAsStateWithLifecycle()
      val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
      val density by viewModel.uiDensity.collectAsStateWithLifecycle()

      val systemDark = isSystemInDarkTheme()
      val darkTheme =
        when (themeMode) {
          SettingKeys.THEME_MODE_LIGHT -> false
          SettingKeys.THEME_MODE_DARK -> true
          else -> systemDark
        }

      // Edge-to-edge draws behind the bars, so the icons have to be told which
      // way to contrast whenever the resolved theme changes.
      LaunchedEffect(darkTheme) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
          isAppearanceLightStatusBars = !darkTheme
          isAppearanceLightNavigationBars = !darkTheme
        }
      }

      DailyBriefTheme(accentKey = accentKey, darkTheme = darkTheme) {
        CompositionLocalProvider(
          LocalWindowWidth provides currentWindowWidth(),
          LocalWindowWidthDp provides currentWindowWidthDp(),
          LocalWindowHeightDp provides currentWindowHeightDp(),
          LocalUiDensity provides density,
          LocalDensityTokens provides tokensFor(density),
        ) {
          Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
          ) {
            DailyBriefApp(
              viewModel = viewModel,
              isDarkTheme = darkTheme,
              initialDestination = initialDestination,
            )
          }
        }
      }
    }
  }

  companion object {
    const val EXTRA_DESTINATION = "extra_destination"

    /** The destination values the launch shortcuts carry. */
    object DestinationShortcut {
      const val PLAN = "Plan"
      const val TODAY = "Home"
    }
  }

  /** Two dynamic shortcuts - the Plan and Today - each with a destination deep link. */
  private fun registerLaunchShortcuts(context: Context) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return
    val planIntent =
      Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_DESTINATION, DestinationShortcut.PLAN)
      }
    val todayIntent =
      Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_DESTINATION, DestinationShortcut.TODAY)
      }
    val shortcuts =
      listOf(
        ShortcutInfoCompat.Builder(context, "shortcut_plan")
          .setShortLabel("Plan")
          .setLongLabel("Open the Plan")
          .setIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_daily_brief))
          .setIntent(planIntent)
          .build(),
        ShortcutInfoCompat.Builder(context, "shortcut_today")
          .setShortLabel("Today")
          .setLongLabel("Open Today")
          .setIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_daily_brief))
          .setIntent(todayIntent)
          .build(),
      )
    ShortcutManagerCompat.addDynamicShortcuts(context, shortcuts)
  }
}
