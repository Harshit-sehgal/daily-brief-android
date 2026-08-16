package com.example.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSaveGuardTest {
  @Test
  fun `an event names the field it is waiting on`() {
    assertNull(EditorSaveGuard.forEvent("Design review", hasValidDestination = true))

    val blank = requireNotNull(EditorSaveGuard.forEvent("   ", hasValidDestination = true))
    assertEquals(EditorField.TITLE, blank.field)

    val destination = requireNotNull(EditorSaveGuard.forEvent("Design review", false))
    assertEquals(EditorField.DESTINATION, destination.field)

    // Title comes first: it is the field a person fills first, so it is the one to report first.
    val both = requireNotNull(EditorSaveGuard.forEvent("", hasValidDestination = false))
    assertEquals(EditorField.TITLE, both.field)
  }

  @Test
  fun `a task reports each blocker in a stable order`() {
    fun guard(
      title: String = "Draft the brief",
      destination: Boolean = true,
      parent: Boolean = true,
      effort: Int? = 60,
      milestone: Boolean = false,
    ) = EditorSaveGuard.forTask(title, destination, parent, effort, milestone)

    assertNull(guard())
    assertNull(guard(effort = null, milestone = true))

    assertEquals(EditorField.TITLE, requireNotNull(guard(title = " ")).field)
    assertEquals(EditorField.DESTINATION, requireNotNull(guard(destination = false)).field)
    assertEquals(EditorField.PARENT, requireNotNull(guard(parent = false)).field)
    assertEquals(EditorField.EFFORT, requireNotNull(guard(effort = 0)).field)
    assertEquals(EditorField.EFFORT, requireNotNull(guard(effort = -30)).field)
    assertEquals(EditorField.MILESTONE, requireNotNull(guard(milestone = true)).field)

    // Earlier blockers win, so the person is never sent to fix the last problem first.
    assertEquals(
      EditorField.TITLE,
      requireNotNull(guard(title = "", destination = false, parent = false, effort = 0)).field,
    )
  }

  @Test
  fun `every message is a sentence that says what to do`() {
    val messages =
      listOfNotNull(
        EditorSaveGuard.forEvent("", true),
        EditorSaveGuard.forEvent("x", false),
        EditorSaveGuard.forTask("", true, true, 60, false),
        EditorSaveGuard.forTask("x", false, true, 60, false),
        EditorSaveGuard.forTask("x", true, false, 60, false),
        EditorSaveGuard.forTask("x", true, true, 0, false),
        EditorSaveGuard.forTask("x", true, true, 60, true),
      )
    assertEquals(7, messages.size)
    messages.forEach { blocker ->
      listOf(blocker.message, blocker.summary).forEach { sentence ->
        assertTrue(sentence, sentence.endsWith("."))
        assertTrue(sentence, sentence.first().isUpperCase())
        // A reason a person cannot act on is only an apology.
        assertTrue(sentence, sentence.length > 15)
      }
      // The two registers must not be the same sentence printed twice on one sheet.
      assertTrue(blocker.summary, blocker.summary != blocker.message)
    }
  }
}
