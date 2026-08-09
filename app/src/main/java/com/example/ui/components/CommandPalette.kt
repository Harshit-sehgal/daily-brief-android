package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.core.Fuzzy
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.Space

/** One thing the switcher can take you to or do. */
data class PaletteItem(
  val id: String,
  val title: String,
  val group: String,
  val icon: ImageVector,
  val subtitle: String? = null,
  val trailing: String? = null,
  val tone: Color? = null,
  val action: () -> Unit,
)

/**
 * The quick switcher.
 *
 * One control that reaches every date, event, board and action in the app — the
 * thing Obsidian users reach for before the sidebar. It opens focused with the
 * keyboard up, ranks as you type, and closes on the first pick.
 */
@Composable
fun CommandPalette(items: List<PaletteItem>, onDismiss: () -> Unit) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  var query by remember { mutableStateOf("") }
  val focus = remember { FocusRequester() }

  val results =
    remember(query, items) {
      Fuzzy.rank(query, items) { "${it.title} ${it.subtitle.orEmpty()} ${it.group}" }.take(40)
    }
  val grouped = remember(results) { results.groupBy { it.group } }

  LaunchedEffect(Unit) { focus.requestFocus() }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      modifier = Modifier.fillMaxSize().padding(horizontal = Space.md),
      contentAlignment = Alignment.TopCenter,
    ) {
      Surface(
        modifier =
          Modifier.statusBarsPadding()
            .padding(top = Space.lg)
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .testTag("command_palette"),
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow,
        tonalElevation = 6.dp,
        shadowElevation = 18.dp,
      ) {
        // No imePadding here: the palette is pinned to the top, so the keyboard
        // never overlaps it — adding it inserted a keyboard-sized dead area.
        Column {
          OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Jump to a day, event, board or action…", fontSize = d.body) },
            leadingIcon = {
              Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions =
              KeyboardActions(
                onGo = {
                  results.firstOrNull()?.let {
                    it.action()
                    onDismiss()
                  }
                }
              ),
            colors =
              OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
              ),
            modifier =
              Modifier.fillMaxWidth().focusRequester(focus).testTag("palette_query"),
          )

          Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))

          if (results.isEmpty()) {
            Box(
              modifier = Modifier.fillMaxWidth().padding(Space.xl),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = "Nothing matches \"$query\"",
                fontSize = d.body,
                color = scheme.onSurfaceVariant,
              )
            }
          } else {
            LazyColumn(
              modifier = Modifier.fillMaxWidth(),
              contentPadding = PaddingValues(vertical = Space.xs),
            ) {
              grouped.forEach { (group, groupItems) ->
                item(key = "h_$group") {
                  Text(
                    text = group.uppercase(),
                    fontSize = d.label,
                    letterSpacing = 0.9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurfaceVariant,
                    modifier =
                      Modifier.padding(start = Space.lg, end = Space.lg, top = Space.sm, bottom = 2.dp),
                  )
                }
                items(groupItems, key = { it.id }) { item ->
                  PaletteRow(
                    item = item,
                    onPick = {
                      item.action()
                      onDismiss()
                    },
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PaletteRow(item: PaletteItem, onPick: () -> Unit) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(role = Role.Button, onClick = onPick)
        .padding(horizontal = Space.lg, vertical = Space.sm),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = item.icon,
      contentDescription = null,
      tint = item.tone ?: scheme.onSurfaceVariant,
      modifier = Modifier.size(d.icon),
    )
    Spacer(Modifier.width(Space.md))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = item.title,
        fontSize = d.title,
        color = scheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!item.subtitle.isNullOrBlank()) {
        Text(
          text = item.subtitle,
          fontSize = d.secondary,
          color = scheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (item.trailing != null) {
      Spacer(Modifier.width(Space.sm))
      Text(text = item.trailing, fontSize = d.label, color = scheme.onSurfaceVariant)
    }
  }
}
