package com.example.data.repository

import com.example.data.model.PlanSurface
import com.example.data.model.SavedView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedViewCodecTest {
  @Test
  fun `typed state round trips delimiter quote and unicode values`() {
    val state =
      SavedPlanViewState(
        surface = PlanSurface.GANTT,
        filters = mapOf("owner" to "Rina, \"APAC\"", "label" to "Launch 🚀"),
        grouping = "owner",
        sort = listOf("due:asc", "priority:desc"),
        columns = listOf("title", "owner/team"),
        rangeDays = 30,
        zoom = "comfortable",
        collapsedItemIds = setOf("task_二", "task_1"),
      )

    val encoded = SavedViewCodec.encode(state)
    val decoded = SavedViewCodec.decode(view(encoded, PlanSurface.GANTT))

    assertEquals(state, decoded)
    assertEquals("[\"task_1\", \"task_二\"]", encoded.collapsedIdsJson)
  }

  @Test
  fun `malformed or partially valid JSON fails closed`() {
    val valid = SavedViewCodec.encode(SavedPlanViewState(surface = PlanSurface.BOARD))

    assertNull(SavedViewCodec.decode(view(valid, PlanSurface.BOARD, filters = "{\"a\":\"b\"} trailing")))
    assertNull(SavedViewCodec.decode(view(valid, PlanSurface.BOARD, filters = "{\"a\":true}")))
    assertNull(SavedViewCodec.decode(view(valid, PlanSurface.BOARD, collapsed = "[\"one\",]")))
    assertNull(SavedViewCodec.decode(view(valid, "future_surface")))
  }

  @Test
  fun `unsupported grouping range and zoom fail closed`() {
    val valid = SavedViewCodec.encode(SavedPlanViewState(surface = PlanSurface.GANTT))

    assertNull(SavedViewCodec.decode(view(valid, PlanSurface.GANTT, grouping = "mystery")))
    assertNull(SavedViewCodec.decode(view(valid, PlanSurface.GANTT, range = 365)))
    assertNull(SavedViewCodec.decode(view(valid, PlanSurface.GANTT, zoom = "microscopic")))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `encode rejects blank identifiers before persistence`() {
    SavedViewCodec.encode(
      SavedPlanViewState(surface = PlanSurface.OUTLINE, collapsedItemIds = setOf("task", " "))
    )
  }

  private fun view(
    encoded: EncodedSavedPlanView,
    surface: String,
    filters: String = encoded.filtersJson,
    grouping: String = encoded.grouping,
    range: Int? = encoded.rangeDays,
    zoom: String? = encoded.zoom,
    collapsed: String = encoded.collapsedIdsJson,
  ) =
    SavedView(
      id = "view",
      boardId = "board",
      name = "My view",
      nameKey = "my view",
      surface = surface,
      filtersJson = filters,
      grouping = grouping,
      sortJson = encoded.sortJson,
      columnsJson = encoded.columnsJson,
      rangeDays = range,
      zoom = zoom,
      collapsedIdsJson = collapsed,
      rank = 1L,
      createdAt = 1L,
      updatedAt = 1L,
    )
}
