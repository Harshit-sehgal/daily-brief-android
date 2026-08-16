package com.example.data.database

import com.example.data.prefs.SettingKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPlanCatalogBuilderTest {
  @Test
  fun `v2 catalog preserves Unicode and delimiter-bearing names with stable ids`() {
    val boardNames = listOf("Sales, APAC", "Line one\nLine two", "Launch 🚀")
    val columnsByBoard =
      mapOf(
        "Sales, APAC" to
          SettingKeys.encodeList(listOf("Needs, review", "等待\nBlocked", "Done ✅")),
        "Line one\nLine two" to SettingKeys.encodeList(listOf("Inbox", "In progress")),
        "Launch 🚀" to SettingKeys.encodeList(listOf("Ideas", "Shipped")),
      )

    fun build() =
      LegacyPlanCatalogBuilder.build(
        encodedBoards = SettingKeys.encodeList(boardNames),
        activeBoardName = "  sales, apac  ",
        encodedColumns = columnsByBoard::get,
        eventDestinations = emptyList(),
      )

    val first = build()
    val second = build()

    assertEquals(first, second)
    assertEquals(boardNames, first.boards.map { it.name })
    assertEquals(
      listOf("Needs, review", "等待\nBlocked", "Done ✅"),
      first.boards.first().columns.map { it.name },
    )
    assertEquals(first.boards.first().id, first.activeBoardId)
    assertEquals(first.boards.map { it.id }, second.boards.map { it.id })
    assertEquals(
      first.boards.flatMap { it.columns }.map { it.id },
      second.boards.flatMap { it.columns }.map { it.id },
    )
  }

  @Test
  fun `normalised Unicode collisions preserve exact boards and their own columns`() {
    val boardNames = listOf("Work", "Ｗｏｒｋ")
    val columns =
      mapOf(
        "Work" to SettingKeys.encodeList(listOf("ASCII only", "Done")),
        "Ｗｏｒｋ" to SettingKeys.encodeList(listOf("Ｆｕｌｌｗｉｄｔｈ only", "Ｄｏｎｅ")),
      )

    fun build(names: List<String>) =
      LegacyPlanCatalogBuilder.build(
        encodedBoards = SettingKeys.encodeList(names),
        activeBoardName = "Ｗｏｒｋ",
        encodedColumns = columns::get,
        eventDestinations = emptyList(),
      )

    val first = build(boardNames)
    val second = build(boardNames)
    val reordered = build(boardNames.reversed())

    assertEquals(first, second)
    assertEquals(boardNames, first.boards.map { it.name })
    assertEquals(2, first.boards.map { it.id }.distinct().size)
    assertEquals(2, first.boards.map { it.nameKey }.distinct().size)
    assertEquals(
      listOf("ASCII only", "Done"),
      first.boards.single { it.name == "Work" }.columns.map { it.name },
    )
    assertEquals(
      listOf("Ｆｕｌｌｗｉｄｔｈ only", "Ｄｏｎｅ"),
      first.boards.single { it.name == "Ｗｏｒｋ" }.columns.map { it.name },
    )
    assertEquals(
      first.boards.associate { it.name to it.id },
      reordered.boards.associate { it.name to it.id },
    )
    assertEquals(first.boards.single { it.name == "Ｗｏｒｋ" }.id, first.activeBoardId)
  }

  @Test
  fun `normalised Unicode collisions preserve exact columns with stable ids`() {
    val columnNames = listOf("Done", "Ｄｏｎｅ")

    fun build() =
      LegacyPlanCatalogBuilder.build(
        encodedBoards = SettingKeys.encodeList(listOf("Work")),
        activeBoardName = "Work",
        encodedColumns = { SettingKeys.encodeList(columnNames) },
        eventDestinations = emptyList(),
      )

    val first = build().boards.single().columns
    val second = build().boards.single().columns

    assertEquals(columnNames, first.map { it.name })
    assertEquals(2, first.map { it.nameKey }.distinct().size)
    assertEquals(first.map { it.id }, second.map { it.id })
  }

  @Test
  fun `generated collision keys cannot shadow an exact legacy name`() {
    val fullWidthWork = "Ｗｏｒｋ"
    val generatedKeyAsName =
      "work#legacy-" +
        fullWidthWork.map { it.code.toString(16).padStart(4, '0') }.joinToString("")
    val boardNames = listOf("Work", fullWidthWork, generatedKeyAsName)

    fun build(names: List<String>) =
      LegacyPlanCatalogBuilder.build(
        encodedBoards = SettingKeys.encodeList(names),
        activeBoardName = generatedKeyAsName,
        encodedColumns = { null },
        eventDestinations = emptyList(),
      )

    val first = build(boardNames)
    val reordered = build(boardNames.reversed())

    assertEquals(boardNames, first.boards.map { it.name })
    assertEquals(boardNames.size, first.boards.map { it.nameKey }.distinct().size)
    assertEquals(boardNames.size, first.boards.map { it.id }.distinct().size)
    assertEquals(
      first.boards.associate { it.name to it.id },
      reordered.boards.associate { it.name to it.id },
    )
    assertEquals(first.boards.single { it.name == generatedKeyAsName }.id, first.activeBoardId)
  }

  @Test
  fun `malformed v2 values fail closed to the default catalog`() {
    val malformed = "dailybrief:list:v2|1|20:short"

    val catalog =
      LegacyPlanCatalogBuilder.build(
        encodedBoards = malformed,
        activeBoardName = null,
        encodedColumns = { malformed },
        eventDestinations = emptyList(),
      )

    val board = catalog.boards.single()
    assertEquals(SettingKeys.DEFAULT_BOARD, board.name)
    assertTrue(board.isDefault)
    assertEquals(SettingKeys.DEFAULT_COLUMNS, board.columns.map { it.name })
    assertEquals(board.id, catalog.activeBoardId)
  }

  @Test
  fun `unknown active board falls back to default and then first board`() {
    val withDefault =
      LegacyPlanCatalogBuilder.build(
        encodedBoards = SettingKeys.encodeList(listOf("Work", SettingKeys.DEFAULT_BOARD)),
        activeBoardName = "Missing",
        encodedColumns = { null },
        eventDestinations = emptyList(),
      )
    val withoutDefault =
      LegacyPlanCatalogBuilder.build(
        encodedBoards = SettingKeys.encodeList(listOf("Work", "Personal")),
        activeBoardName = "Missing",
        encodedColumns = { null },
        eventDestinations = emptyList(),
      )

    assertEquals(
      withDefault.boards.single { it.isDefault }.id,
      withDefault.activeBoardId,
    )
    assertFalse(withoutDefault.boards.any { it.isDefault })
    assertEquals(withoutDefault.boards.first().id, withoutDefault.activeBoardId)
  }

  @Test
  fun `event-only destinations add their board and status without duplicates`() {
    val catalog =
      LegacyPlanCatalogBuilder.build(
        encodedBoards = SettingKeys.encodeList(listOf(SettingKeys.DEFAULT_BOARD)),
        activeBoardName = "Roadmap 🚀",
        encodedColumns = { null },
        eventDestinations =
          listOf(
            "Roadmap 🚀" to "Needs, review",
            "Roadmap 🚀" to "Needs, review",
            "Roadmap 🚀" to "Blocked\nwaiting",
          ),
      )

    val eventBoard = catalog.boards.single { it.name == "Roadmap 🚀" }
    assertEquals(eventBoard.id, catalog.activeBoardId)
    assertEquals(
      SettingKeys.DEFAULT_COLUMNS + listOf("Needs, review", "Blocked\nwaiting"),
      eventBoard.columns.map { it.name },
    )
    assertEquals(eventBoard.columns.size, eventBoard.columns.map { it.nameKey }.distinct().size)
  }
}
