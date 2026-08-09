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

/** Small status pill, e.g. the source of an event or a priority flag. */
@Composable
fun Tag(
  text: String,
  modifier: Modifier = Modifier,
  tone: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
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
fun Dot(tone: Color, modifier: Modifier = Modifier, size: Dp = 6.dp) {
  Box(modifier = modifier.size(size).background(tone, CircleShape).clearAndSetSemantics {})
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
