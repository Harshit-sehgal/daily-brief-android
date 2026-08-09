package com.example.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingKeysTest {

  @Test
  fun `lists round trip`() {
    val boards = listOf("Default", "Work", "Home renovation")
    assertEquals(boards, SettingKeys.decodeList(SettingKeys.encodeList(boards)))
  }

  @Test
  fun `versioned lists preserve delimiters and unicode`() {
    val values = listOf("Sales, APAC", "Line one\nLine two", "Launch 🚀")

    assertEquals(values, SettingKeys.decodeList(SettingKeys.encodeList(values)))
  }

  @Test
  fun `comma separated lists from older versions still load`() {
    assertEquals(
      listOf("To Do", "In Progress", "Done"),
      SettingKeys.decodeList("To Do,In Progress,Done"),
    )
  }

  @Test
  fun `blank and missing values fall through to the caller's default`() {
    assertNull(SettingKeys.decodeList(null))
    assertNull(SettingKeys.decodeList(""))
    assertNull(SettingKeys.decodeList("  \n , "))
    assertNull(SettingKeys.decodeList(SettingKeys.encodeList(emptyList())))
  }

  @Test
  fun `duplicates are dropped so a board cannot be listed twice`() {
    assertEquals(listOf("Work", "Home"), SettingKeys.decodeList("Work\nHome\nWork"))
  }

  @Test
  fun `column keys are scoped per board`() {
    assertEquals("kanban_columns_Work", SettingKeys.columnsForBoard("Work"))
    assertEquals("kanban_columns_Default", SettingKeys.columnsForBoard(SettingKeys.DEFAULT_BOARD))
  }

  @Test
  fun `malformed versioned values fail closed`() {
    assertNull(SettingKeys.decodeList("dailybrief:list:v2|1|20:short"))
    assertNull(SettingKeys.decodeList("dailybrief:list:v2|not-a-count|"))
  }
}
