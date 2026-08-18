package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.Space
import com.example.ui.theme.rootTitleSize

/**
 * Compact frame shared by consolidated workspace roots.
 *
 * Search and settings are global, so they stay in one predictable place while
 * the segmented control changes only the representation of the current context.
 */
@Composable
fun WorkspaceRootHeader(
  title: String,
  /**
   * Live state only — the board being planned, the day being read. A standing description of what
   * a root is for costs a line of every screen forever and tells a returning person nothing.
   */
  views: List<String>,
  selectedView: Int,
  onSelectView: (Int) -> Unit,
  onSearch: () -> Unit,
  onSettings: () -> Unit,
  tagPrefix: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  /**
   * Everything a root can do that is not its work.
   *
   * One place for it, in the header, so a screen's own area holds the schedule or the tasks and
   * nothing else. A standing row of secondary controls costs its height on every screen forever and
   * is read once.
   */
  overflow: (@Composable () -> Unit)? = null,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = Space.sm)) {
        Text(
          text = title,
          fontSize = rootTitleSize(),
          fontWeight = FontWeight.SemiBold,
          color = scheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        subtitle?.takeIf(String::isNotBlank)?.let { live ->
          Text(
            text = live,
            fontSize = d.secondary,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        RowIconButton(
          icon = Icons.Default.Search,
          contentDescription = "Search and commands",
          onClick = onSearch,
          modifier = Modifier.testTag("global_search"),
        )
        overflow?.invoke()
        RowIconButton(
          icon = Icons.Default.Settings,
          contentDescription = "Open settings",
          onClick = onSettings,
          modifier = Modifier.testTag("open_settings"),
        )
      }
    }

    Spacer(Modifier.height(Space.xs))
    ViewSwitcher(
      options = views,
      selectedIndex = selectedView,
      onSelect = onSelectView,
      modifier = Modifier.testTag("${tagPrefix}_view_switcher"),
    )
    HorizontalDivider(
      modifier = Modifier.padding(top = Space.sm),
      color = scheme.outlineVariant,
    )
  }
}
