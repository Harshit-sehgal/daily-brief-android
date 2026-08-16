package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.example.ui.theme.MinimumTouchTarget
import com.example.ui.theme.Space

/**
 * What an empty screen says, and what it offers to do about it.
 *
 * "Nothing scheduled yet" is a true sentence that leaves a person exactly where they were. Someone
 * who has just installed this and granted nothing meets three empty rooms in a row, and nothing in
 * any of them says which door to open first. An empty state is the one moment the app has a person's
 * full attention with nothing else on screen — so it names the situation in a line, and offers the
 * single next thing, as a real control rather than an instruction to go and find one.
 *
 * The action is optional because some empty states genuinely have no next step: a filtered list with
 * everything hidden is empty by request, and telling that person to capture a task would be wrong.
 */
@Composable
fun EmptyState(
  headline: String,
  supporting: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  tag: String? = null,
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(vertical = Space.lg)
        .let { if (tag != null) it.testTag(tag) else it },
    verticalArrangement = Arrangement.spacedBy(Space.xs),
  ) {
    Text(
      text = headline,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = supporting,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (actionLabel != null && onAction != null) {
      TextButton(
        onClick = onAction,
        modifier = Modifier.heightIn(min = MinimumTouchTarget).testTag("${tag ?: "empty"}_action"),
      ) {
        Text(actionLabel)
      }
    }
  }
}
