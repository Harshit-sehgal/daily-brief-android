package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.launch

/**
 * Sideways swipe to move a day at a time, the way every calendar app works.
 *
 * The content tracks your finger rather than waiting for you to let go, which is
 * the whole difference between a gesture that works and one that feels broken.
 * Past the commit threshold the page continues off-screen, the day changes, and
 * the new day slides in from the far side — so the direction of travel matches
 * the direction of time.
 *
 * A vertically scrolling child still wins vertical drags: whichever axis crosses
 * touch slop first claims the gesture, so lists inside this box scroll normally.
 */
@Composable
fun DaySwipeBox(
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable BoxScope.() -> Unit,
) {
  val scope = rememberCoroutineScope()
  val haptics = LocalHapticFeedback.current
  val density = LocalDensity.current
  val offsetX = remember { Animatable(0f) }

  val commitPx = with(density) { CommitDistance.toPx() }
  val exitPx = with(density) { ExitDistance.toPx() }

  Box(
    modifier =
      modifier
        .then(
          if (!enabled) Modifier
          else
            Modifier.pointerInput(Unit) {
              detectHorizontalDragGestures(
                onDragStart = { scope.launch { offsetX.stop() } },
                onHorizontalDrag = { change, delta ->
                  change.consume()
                  scope.launch {
                    // Rubber-band past the commit point: the page keeps moving but
                    // gets heavier, which is what tells your thumb it has gone far
                    // enough without needing a label.
                    val next = offsetX.value + delta
                    val damped =
                      if (abs(next) <= commitPx) next
                      else sign(next) * (commitPx + (abs(next) - commitPx) * Resistance)
                    offsetX.snapTo(damped)
                  }
                },
                onDragCancel = { scope.launch { offsetX.animateTo(0f, SettleSpec) } },
                onDragEnd = {
                  val travelled = offsetX.value
                  scope.launch {
                    if (abs(travelled) >= commitPx) {
                      haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                      // Carry the page out the way it was heading...
                      offsetX.animateTo(sign(travelled) * exitPx, ExitSpec)
                      // ...swap the day while nothing is on screen...
                      if (travelled > 0) onPrevious() else onNext()
                      // ...and bring the new one in from the opposite edge.
                      offsetX.snapTo(-sign(travelled) * exitPx)
                      offsetX.animateTo(0f, EnterSpec)
                    } else {
                      offsetX.animateTo(0f, SettleSpec)
                    }
                  }
                },
              )
            }
        )
        .fillMaxSize()
  ) {
    Box(
      modifier =
        Modifier.fillMaxSize().graphicsLayer {
          translationX = offsetX.value
          // Fading on the way out hides the content swap underneath it.
          alpha = (1f - (abs(offsetX.value) / exitPx) * 0.9f).coerceIn(0.1f, 1f)
        },
      content = content,
    )
  }
}

/** How far you have to travel before letting go changes the day. */
private val CommitDistance = 72.dp

/** How far the outgoing page carries on before the swap happens. */
private val ExitDistance = 220.dp

private const val Resistance = 0.35f

private val SettleSpec = tween<Float>(durationMillis = 220)
private val ExitSpec = tween<Float>(durationMillis = 110)
private val EnterSpec = tween<Float>(durationMillis = 190)
