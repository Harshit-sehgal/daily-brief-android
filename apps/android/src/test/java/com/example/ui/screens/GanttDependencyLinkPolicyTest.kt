package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GanttDependencyLinkPolicyTest {
  @Test
  fun firstTapSelectsThePredecessor() {
    val result = GanttDependencyLinkPolicy.select(GanttDependencyLinkState(), "a")

    assertEquals("a", result.state.predecessorId)
    assertNull(result.predecessorId)
    assertNull(result.successorId)
  }

  @Test
  fun tappingTheSameTaskDoesNotCreateASelfEdge() {
    val result = GanttDependencyLinkPolicy.select(GanttDependencyLinkState("a"), "a")

    assertEquals("a", result.state.predecessorId)
    assertNull(result.predecessorId)
    assertNull(result.successorId)
  }

  @Test
  fun secondDifferentTapCompletesThePairInOrder() {
    val result = GanttDependencyLinkPolicy.select(GanttDependencyLinkState("a"), "b")

    assertEquals(GanttDependencyLinkState(), result.state)
    assertEquals("a", result.predecessorId)
    assertEquals("b", result.successorId)
  }
}
