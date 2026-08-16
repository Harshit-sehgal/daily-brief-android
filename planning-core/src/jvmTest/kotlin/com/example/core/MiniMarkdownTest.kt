package com.example.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniMarkdownTest {

  @Test
  fun `headings bullets and paragraphs are separated`() {
    val blocks =
      MiniMarkdown.parse(
        """
        ## Focus
        A quiet morning, then two meetings.

        ## Watch out
        - Client call overlaps the review
        - Release notes are due at 17:00
        """
          .trimIndent()
      )

    assertEquals(
      listOf("Heading:Focus", "Paragraph:A quiet morning, then two meetings."),
      blocks.take(2).map(::describe),
    )
    assertEquals("Heading:Watch out", describe(blocks[2]))
    assertEquals("Bullet:Client call overlaps the review", describe(blocks[3]))
    assertEquals("Bullet:Release notes are due at 17:00", describe(blocks[4]))
    assertEquals(5, blocks.size)
  }

  @Test
  fun `wrapped lines join into one paragraph`() {
    val blocks = MiniMarkdown.parse("one line\nand its continuation\n\nnext block")

    assertEquals(
      listOf("Paragraph:one line and its continuation", "Paragraph:next block"),
      blocks.map(::describe),
    )
  }

  @Test
  fun `heading markers of any depth are stripped`() {
    assertEquals("Heading:Plan", describe(MiniMarkdown.parse("### Plan").single()))
    assertEquals("Heading:Plan", describe(MiniMarkdown.parse("# Plan").single()))
  }

  @Test
  fun `bullet markers and checkboxes are stripped`() {
    assertEquals("Bullet:Ship it", describe(MiniMarkdown.parse("* Ship it").single()))
    assertEquals("Bullet:Ship it", describe(MiniMarkdown.parse("- [ ] Ship it").single()))
  }

  @Test
  fun `a hyphenated word is not a bullet`() {
    assertEquals("Paragraph:end-to-end tests", describe(MiniMarkdown.parse("end-to-end tests").single()))
  }

  @Test
  fun `bold runs are split out`() {
    val segments = MiniMarkdown.segments("Call **Priya** at noon")

    assertEquals(
      listOf("Call " to false, "Priya" to true, " at noon" to false),
      segments.map { it.text to it.bold },
    )
  }

  @Test
  fun `an unclosed bold marker stays literal`() {
    val segments = MiniMarkdown.segments("a **dangling marker")

    assertEquals(listOf("a **dangling marker" to false), segments.map { it.text to it.bold })
  }

  @Test
  fun `plain text is a single run`() {
    assertEquals(1, MiniMarkdown.segments("nothing special here").size)
  }

  private fun describe(block: MiniMarkdown.Block): String =
    when (block) {
      is MiniMarkdown.Block.Heading -> "Heading:${block.text}"
      is MiniMarkdown.Block.Bullet -> "Bullet:${block.text}"
      is MiniMarkdown.Block.Paragraph -> "Paragraph:${block.text}"
    }
}
