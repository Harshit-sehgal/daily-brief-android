package com.example.ui.components

import com.example.ui.theme.InlineIconSize
import com.example.ui.theme.Radius
import com.example.ui.viewmodel.SaveBlocker
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalDensityTokens
import com.example.ui.theme.MinimumTouchTarget
import com.example.ui.theme.Space

/*
 * Workspace primitives.
 *
 * Content sits on the page ground and is separated by hairlines rather than being
 * boxed in a card each — the pattern Notion and Obsidian use, and the reason they
 * fit roughly twice as much on a screen. Every measurement comes from the density
 * tokens so one setting genuinely re-proportions the app.
 */

/**
 * A foldable section, headed by a disclosure chevron, a small-caps title and a
 * count. Collapse state is owned by the caller so it can be persisted.
 */
@Composable
fun SectionToggle(
  title: String,
  expanded: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
  count: Int? = null,
  action: @Composable (RowScope.() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val rotation by
    animateFloatAsState(if (expanded) 0f else -90f, tween(140), label = "section_chevron")

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .heightIn(min = maxOf(d.sectionHeaderHeight, MinimumTouchTarget))
          .clip(RoundedCornerShape(Radius.control))
          .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
          .clickable(onClickLabel = if (expanded) "Collapse" else "Expand", role = Role.Button) {
            onToggle()
          }
          .padding(end = Space.xs),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Default.KeyboardArrowDown,
        contentDescription = null,
        tint = scheme.onSurfaceVariant,
        modifier = Modifier.size(InlineIconSize).rotate(rotation),
      )
      Spacer(Modifier.width(2.dp))
      Text(
        text = title.uppercase(),
        fontSize = d.label,
        letterSpacing = 0.9.sp,
        fontWeight = FontWeight.SemiBold,
        color = scheme.onSurfaceVariant,
      )
      if (count != null) {
        Spacer(Modifier.width(Space.sm))
        Text(
          text = count.toString(),
          fontSize = d.label,
          color = scheme.onSurfaceVariant.copy(alpha = 0.65f),
        )
      }
      Spacer(Modifier.weight(1f))
      if (action != null) action()
    }

    AnimatedVisibility(
      visible = expanded,
      enter = fadeIn(tween(120)) + expandVertically(tween(160)),
      exit = fadeOut(tween(90)) + shrinkVertically(tween(140)),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LocalDensityTokens.current.rowGap),
        content = content,
      )
    }
  }
}

/**
 * How wide a gutter has to be to hold a time at the current text size.
 *
 * The density tokens are dp measurements but the text inside them is sp, so a
 * user who scales text up outgrows the designed width and the time gets clipped
 * — and a clipped time reads as a different time, not a near miss. Density
 * decides how much fits on screen; the font-size setting is not negotiable.
 *
 * The widest possible time is measured rather than estimated from a character
 * count, because glyph widths vary by font and weight. Every row measures the
 * same sample, so the column still aligns down the page — which is the only
 * reason the gutter has a fixed width in the first place.
 */
@Composable
internal fun gutterWidthFor(
  designed: Dp,
  textSize: TextUnit,
  samples: List<String> = TimeSamples,
  fontWeight: FontWeight = FontWeight.Medium,
): Dp {
  val measurer = rememberTextMeasurer()
  // Merged the same way Text does it: the theme's letter spacing and font family
  // are part of how wide the string actually lands, and measuring a bare
  // TextStyle silently under-reserves by a pixel per character.
  val style =
    LocalTextStyle.current.merge(TextStyle(fontSize = textSize, fontWeight = fontWeight))
  val width =
    remember(measurer, style, samples, LocalDensity.current) {
      samples.maxOf { measurer.measure(it, style, maxLines = 1).size.width }
    }
  // The measured width is in pixels and the constraint is in dp, so the round
  // trip can land a pixel short of what the text needs. Two dp of slack costs
  // nothing and keeps the last glyph off the edge.
  return maxOf(designed, with(LocalDensity.current) { width.toDp() } + MeasurementSlack)
}

private val MeasurementSlack = 2.dp

/*
 * Which meridiem is wider is a property of the font, not of the alphabet — in
 * Roboto "PM" beats "AM" — so both are measured rather than reasoned about.
 * 24-hour locales ("23:59") are narrower than either and need no sample.
 */

/** What a row gutter has to hold. */
internal val TimeSamples = listOf("00:00 AM", "00:00 PM")

/** The timeline labels whole hours only. */
internal val HourLabelSamples = listOf("12 AM", "12 PM")

/**
 * One dense row. A fixed left gutter keeps times, markers and titles aligned down
 * the whole page, which is what makes a long list scannable rather than ragged.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceRow(
  title: String,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  gutterText: String? = null,
  gutterSubtext: String? = null,
  marker: Color? = null,
  subtitle: String? = null,
  tags: List<RowTag> = emptyList(),
  trailing: @Composable (RowScope.() -> Unit)? = null,
  showDivider: Boolean = true,
  /** Read out after the title — how a screen reader learns what the marker means. */
  stateDescription: String? = null,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  val clickable =
    if (onClick != null) modifier.clickable(role = Role.Button, onClick = onClick) else modifier
  val base =
    if (stateDescription != null) {
      clickable.semantics { this.stateDescription = stateDescription }
    } else {
      clickable
    }

  Column(modifier = base.fillMaxWidth()) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .heightIn(min = maxOf(d.rowHeight, MinimumTouchTarget))
          .padding(vertical = d.rowPaddingV),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (gutterText != null) {
        Column(modifier = Modifier.width(gutterWidthFor(d.gutter, d.body))) {
          Text(
            text = gutterText,
            fontSize = d.body,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
            maxLines = 1,
            // A clipped time reads as a different time — "11:0" is not 11:00.
            overflow = TextOverflow.Ellipsis,
          )
          if (gutterSubtext != null) {
            Text(
              text = gutterSubtext,
              fontSize = d.label,
              color = scheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      if (marker != null) {
        Box(modifier = Modifier.size(7.dp).background(marker, CircleShape))
        Spacer(Modifier.width(Space.md))
      } else if (gutterText != null) {
        Spacer(Modifier.width(Space.md))
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          fontSize = d.title,
          fontWeight = FontWeight.Medium,
          color = scheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
          Text(
            text = subtitle,
            fontSize = d.secondary,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (tags.isNotEmpty()) {
          Spacer(Modifier.height(3.dp))
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalArrangement = Arrangement.spacedBy(3.dp),
          ) {
            tags.forEach { PropertyChip(it.label, it.tone) }
          }
        }
      }

      if (trailing != null) {
        Spacer(Modifier.width(Space.sm))
        trailing()
      }
    }
    if (showDivider) {
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .padding(start = if (gutterText != null) d.gutter else 0.dp)
            .height(1.dp)
            .background(scheme.outlineVariant)
      )
    }
  }
}

data class RowTag(val label: String, val tone: Color)

/**
 * The priority an event carries, as a phrase.
 *
 * The rows show priority as a coloured marker, which a screen reader cannot see,
 * so the same information has to travel as a state description.
 */
fun priorityState(isDeadline: Boolean, isUrgent: Boolean): String? =
  when {
    isDeadline && isUrgent -> "Deadline, Urgent"
    isDeadline -> "Deadline"
    isUrgent -> "Urgent"
    else -> null
  }

/** Inline tag. Deliberately small — it annotates a row, it does not compete with it. */
@Composable
fun PropertyChip(
  text: String,
  tone: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  val d = LocalDensityTokens.current
  Box(
    modifier =
      Modifier.widthIn(max = 200.dp)
        .background(tone.copy(alpha = 0.13f), RoundedCornerShape(Radius.mark))
        .padding(horizontal = 5.dp, vertical = 1.dp)
  ) {
    Text(
      text = text,
      fontSize = d.label,
      color = tone,
      fontWeight = FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** Notion's "+ New" affordance: a quiet row that becomes the primary way to add. */
@Composable
fun InlineAddRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(min = maxOf(d.rowHeight, MinimumTouchTarget))
        .clip(RoundedCornerShape(Radius.control))
        .clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = d.rowPaddingV),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Default.Add,
      contentDescription = null,
      tint = scheme.onSurfaceVariant,
      modifier = Modifier.size(d.icon),
    )
    Spacer(Modifier.width(Space.sm))
    Text(text = label, fontSize = d.body, color = scheme.onSurfaceVariant)
  }
}

/** Compact segmented control for switching how a list is presented. */
@Composable
fun ViewSwitcher(
  options: List<String>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val d = LocalDensityTokens.current
  val scheme = MaterialTheme.colorScheme
  Row(
    modifier =
      modifier
        .clip(RoundedCornerShape(Radius.control))
        .background(scheme.surfaceContainerHigh)
        .padding(2.dp)
        .selectableGroup(),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    options.forEachIndexed { index, option ->
      val selected = index == selectedIndex
      Box(
        modifier =
          Modifier.clip(RoundedCornerShape(Radius.control))
            .widthIn(min = MinimumTouchTarget)
            .heightIn(min = MinimumTouchTarget)
            .background(if (selected) scheme.surface else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
            .padding(horizontal = 9.dp, vertical = 4.dp)
      ) {
        Text(
          text = option,
          fontSize = d.label,
          fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
          color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** Accessible square button whose icon still follows the current visual density. */
@Composable
fun RowIconButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  val d = LocalDensityTokens.current
  Box(
    modifier =
      modifier
        .size(MinimumTouchTarget)
        .clip(RoundedCornerShape(Radius.control))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = if (enabled) tint else tint.copy(alpha = 0.35f),
      modifier = Modifier.size(d.icon),
    )
  }
}

/**
 * The visible reason a Save button is disabled.
 *
 * Prevention is the right default for an invalid draft, but a dead control with no diagnosis is
 * only half of it. The sentence names the field and the fix, and announces itself when it appears
 * so it reaches someone who is not looking at the button.
 */
@Composable
fun SaveBlockerNotice(blocker: SaveBlocker?, modifier: Modifier = Modifier) {
  if (blocker == null) return
  Text(
    text = blocker.summary,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.error,
    modifier =
      modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
  )
}
