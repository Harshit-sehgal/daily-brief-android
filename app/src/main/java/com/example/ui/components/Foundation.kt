package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.core.MiniMarkdown
import com.example.ui.theme.Space

/** A quiet group label. Sentence case — nothing in this UI shouts. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.padding(bottom = Space.sm),
  )
}

/** The single surface treatment used for every panel in the app. */
@Composable
fun AppCard(
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  outlined: Boolean = false,
  contentPadding: PaddingValues =
    PaddingValues(Space.lg),
  content: @Composable ColumnScope.() -> Unit,
) {
  val shape = MaterialTheme.shapes.large
  val color = MaterialTheme.colorScheme.surfaceContainer
  val border =
    if (outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null

  if (onClick == null) {
    Surface(modifier = modifier, shape = shape, color = color, border = border) {
      Column(Modifier.padding(contentPadding), content = content)
    }
  } else {
    Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = border) {
      Column(Modifier.padding(contentPadding), content = content)
    }
  }
}

/** One number with its label. Three of these make the day summary. */
@Composable
fun StatTile(label: String, value: String, tone: Color, modifier: Modifier = Modifier) {
  AppCard(modifier = modifier, contentPadding = PaddingValues(Space.md)) {
    Text(text = value, style = MaterialTheme.typography.headlineSmall, color = tone)
    Spacer(Modifier.height(2.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** Small status pill, e.g. the source of an event or a priority flag. */
@Composable
fun Tag(text: String, tone: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
  Box(
    modifier =
      modifier
        .background(tone.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
        .padding(horizontal = 6.dp, vertical = 2.dp)
  ) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = tone)
  }
}

/** A coloured dot. Used instead of icons where a single pixel of meaning is enough. */
@Composable
fun Dot(tone: Color, size: Dp = 6.dp, modifier: Modifier = Modifier) {
  Box(modifier = modifier.size(size).background(tone, CircleShape).clearAndSetSemantics {})
}

/**
 * Inline message strip for things the user may want to act on — a missing
 * permission, a failed integration, an out-of-date brief.
 */
@Composable
fun InlineNotice(
  text: String,
  modifier: Modifier = Modifier,
  tone: Color = MaterialTheme.colorScheme.onSurfaceVariant,
  icon: ImageVector? = null,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = tone.copy(alpha = 0.10f),
  ) {
    Row(
      modifier = Modifier.padding(start = Space.md, end = Space.sm, top = Space.sm, bottom = Space.sm),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null) {
        Icon(imageVector = icon, contentDescription = null, tint = tone, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Space.sm))
      }
      Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
      )
      if (actionLabel != null && onAction != null) {
        Spacer(Modifier.width(Space.sm))
        TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = Space.sm)) {
          Text(text = actionLabel, style = MaterialTheme.typography.labelLarge, color = tone)
        }
      }
    }
  }
}

/** Shown wherever a list has nothing in it, always with a way forward. */
@Composable
fun EmptyState(
  title: String,
  message: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  Column(
    modifier = modifier.fillMaxWidth().padding(vertical = Space.xxl, horizontal = Space.lg),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Space.xs))
    Text(
      text = message,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    if (actionLabel != null && onAction != null) {
      Spacer(Modifier.height(Space.sm))
      TextButton(onClick = onAction) { Text(actionLabel) }
    }
  }
}

/** Renders the brief. Headings, bullets, paragraphs and inline bold — nothing else. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
  val blocks = remember(markdown) { MiniMarkdown.parse(markdown) }
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
    blocks.forEachIndexed { index, block ->
      when (block) {
        is MiniMarkdown.Block.Heading -> {
          if (index > 0) Spacer(Modifier.height(Space.sm))
          Text(
            text = block.text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        is MiniMarkdown.Block.Bullet ->
          Row(verticalAlignment = Alignment.Top) {
            Dot(
              tone = MaterialTheme.colorScheme.onSurfaceVariant,
              size = 4.dp,
              modifier = Modifier.padding(top = 8.dp, end = Space.sm),
            )
            InlineMarkdown(block.text, Modifier.weight(1f))
          }
        is MiniMarkdown.Block.Paragraph -> InlineMarkdown(block.text)
      }
    }
  }
}

@Composable
private fun InlineMarkdown(text: String, modifier: Modifier = Modifier) {
  val annotated =
    remember(text) {
      buildAnnotatedString {
        MiniMarkdown.segments(text).forEach { segment ->
          if (segment.bold) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(segment.text) }
          } else {
            append(segment.text)
          }
        }
      }
    }
  Text(
    text = annotated,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurface,
    modifier = modifier,
  )
}
