package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoardDragPolicyTest {
  @Test
  fun `compact edge scrolling is bounded by viewport and list direction`() {
    val args =
      Args(
        viewportLeft = 10f,
        viewportTop = 20f,
        viewportRight = 390f,
        viewportBottom = 700f,
        edgeWidth = 56f,
        maxStep = 36f,
      )

    assertEquals(-36f, args.delta(x = 20f, y = 200f, backward = true), 0f)
    assertEquals(36f, args.delta(x = 380f, y = 200f, forward = true), 0f)
    assertEquals(0f, args.delta(x = 200f, y = 200f, backward = true, forward = true), 0f)
    assertEquals(0f, args.delta(x = 20f, y = 200f, backward = false), 0f)
    assertEquals(0f, args.delta(x = 380f, y = 200f, forward = false), 0f)
    assertEquals(0f, args.delta(x = 400f, y = 200f, forward = true), 0f)
    assertEquals(0f, args.delta(x = 380f, y = 710f, forward = true), 0f)
  }

  @Test
  fun `invalid viewport fails before suggesting movement`() {
    assertThrows(IllegalArgumentException::class.java) {
      BoardLaneAutoScrollPolicy.delta(
        pointerX = 1f,
        pointerY = 1f,
        viewportLeft = 10f,
        viewportTop = 0f,
        viewportRight = 0f,
        viewportBottom = 10f,
        edgeWidth = 2f,
        maxStep = 1f,
        canScrollBackward = true,
        canScrollForward = true,
      )
    }
  }

  private data class Args(
    val viewportLeft: Float,
    val viewportTop: Float,
    val viewportRight: Float,
    val viewportBottom: Float,
    val edgeWidth: Float,
    val maxStep: Float,
  ) {
    fun delta(
      x: Float,
      y: Float,
      backward: Boolean = false,
      forward: Boolean = false,
    ): Float =
      BoardLaneAutoScrollPolicy.delta(
        pointerX = x,
        pointerY = y,
        viewportLeft = viewportLeft,
        viewportTop = viewportTop,
        viewportRight = viewportRight,
        viewportBottom = viewportBottom,
        edgeWidth = edgeWidth,
        maxStep = maxStep,
        canScrollBackward = backward,
        canScrollForward = forward,
      )
  }
}
