package com.example.core

/**
 * The smallest markdown subset the brief actually uses: headings, bullets,
 * paragraphs and inline bold. Kept as pure functions so the parsing is testable
 * without a composition, and so the renderer stays a handful of lines.
 */
object MiniMarkdown {

  sealed interface Block {
    data class Heading(val text: String) : Block

    data class Bullet(val text: String) : Block

    data class Paragraph(val text: String) : Block
  }

  /** A run of text and whether it is bold. */
  data class Segment(val text: String, val bold: Boolean)

  fun parse(source: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
      val text = paragraph.toString().trim()
      if (text.isNotEmpty()) blocks.add(Block.Paragraph(text))
      paragraph.setLength(0)
    }

    source.lineSequence().forEach { rawLine ->
      val line = rawLine.trim()
      when {
        line.isEmpty() -> flushParagraph()
        line.startsWith("#") -> {
          flushParagraph()
          val text = line.trimStart('#').trim()
          if (text.isNotEmpty()) blocks.add(Block.Heading(text))
        }
        isBullet(line) -> {
          flushParagraph()
          val text = line.drop(1).trim().removePrefix("[ ]").removePrefix("[x]").trim()
          if (text.isNotEmpty()) blocks.add(Block.Bullet(text))
        }
        else -> {
          if (paragraph.isNotEmpty()) paragraph.append(' ')
          paragraph.append(line)
        }
      }
    }
    flushParagraph()
    return blocks
  }

  private fun isBullet(line: String): Boolean =
    (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")) ||
      line == "-" ||
      line == "*"

  /**
   * Splits on `**bold**`. An unclosed marker is treated as literal text rather
   * than swallowing the rest of the line.
   */
  fun segments(text: String): List<Segment> {
    if (!text.contains("**")) return listOf(Segment(text, bold = false))

    val result = mutableListOf<Segment>()
    var index = 0
    while (index < text.length) {
      val open = text.indexOf("**", index)
      if (open < 0) {
        result.addPlain(text.substring(index))
        break
      }
      val close = text.indexOf("**", open + 2)
      if (close < 0) {
        result.addPlain(text.substring(index))
        break
      }
      result.addPlain(text.substring(index, open))
      val emphasised = text.substring(open + 2, close)
      if (emphasised.isNotEmpty()) result.add(Segment(emphasised, bold = true))
      index = close + 2
    }
    return result.ifEmpty { listOf(Segment(text, bold = false)) }
  }

  private fun MutableList<Segment>.addPlain(text: String) {
    if (text.isNotEmpty()) add(Segment(text, bold = false))
  }
}
