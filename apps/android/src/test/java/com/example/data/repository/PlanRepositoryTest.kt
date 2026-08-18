package com.example.data.repository

import com.example.data.model.PlanDependency
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanRepositoryTest {
  @Test
  fun `hasPath finds direct and transitive dependencies but not disconnected nodes`() {
    val dependencies =
      listOf(
        dependency("a", "b"),
        dependency("b", "c"),
        dependency("unrelated", "island"),
      )

    assertTrue(PlanRepository.hasPath(dependencies, from = "a", to = "b"))
    assertTrue(PlanRepository.hasPath(dependencies, from = "a", to = "c"))
    assertFalse(PlanRepository.hasPath(dependencies, from = "c", to = "a"))
    assertFalse(PlanRepository.hasPath(dependencies, from = "a", to = "island"))
  }

  @Test
  fun `cycle check uses the successor to predecessor path`() {
    val dependencies = listOf(dependency("a", "b"), dependency("b", "c"))

    // Adding c -> a would close a -> b -> c -> a, so the existing a -> c path is decisive.
    assertTrue(PlanRepository.hasPath(dependencies, from = "a", to = "c"))
    // Adding a -> c does not close a cycle because nothing currently reaches a from c.
    assertFalse(PlanRepository.hasPath(dependencies, from = "c", to = "a"))
  }

  @Test
  fun `existing cycles terminate and retain ordinary reachability`() {
    val dependencies =
      listOf(
        dependency("a", "b"),
        dependency("b", "a"),
        dependency("b", "c"),
        dependency("b", "c", id = "duplicate-edge"),
      )

    assertTrue(PlanRepository.hasPath(dependencies, from = "a", to = "c"))
    assertTrue(PlanRepository.hasPath(dependencies, from = "a", to = "a"))
    assertFalse(PlanRepository.hasPath(dependencies, from = "a", to = "missing"))
  }

  private fun dependency(
    predecessorId: String,
    successorId: String,
    id: String = "$predecessorId-$successorId",
  ) =
    PlanDependency(
      id = id,
      boardId = "board",
      predecessorId = predecessorId,
      successorId = successorId,
      createdAt = 1L,
      updatedAt = 1L,
    )
}
