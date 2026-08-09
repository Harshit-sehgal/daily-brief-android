package com.example

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
import com.example.ui.theme.currentWindowWidth
import com.example.ui.theme.tokensFor
import com.example.ui.viewmodel.BriefingViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    BriefingAndReminderReceiver.createNotificationChannels(this)

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
          LocalUiDensity provides density,
          LocalDensityTokens provides tokensFor(density),
        ) {
          Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
          ) {
            DailyBriefApp(viewModel = viewModel, isDarkTheme = darkTheme)
          }
        }
      }
    }
  }
}
